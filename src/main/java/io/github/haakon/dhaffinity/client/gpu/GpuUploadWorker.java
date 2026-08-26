package io.github.haakon.dhaffinity.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import io.github.haakon.dhaffinity.affinity.AffinityBackend;
import io.github.haakon.dhaffinity.affinity.MaskFormat;
import io.github.haakon.dhaffinity.config.AffinityConfig;
import io.github.haakon.dhaffinity.core.DhAffinity;
import io.github.haakon.dhaffinity.mixin.DhAffinityMixinPlugin;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A dedicated "DH-GPU Upload" thread that owns a hidden GLFW window whose OpenGL context shares
 * objects with Minecraft's. Distant Horizons' buffer tasks (create / upload / delete) run here
 * instead of on the render thread, so a burst of finished LODs no longer costs frame time.
 *
 * <p>Correctness rules (OpenGL 4.6 §5.3 "Propagating changes to objects"): a change made on this
 * context is visible to the render thread once (1) our commands have completed — we wait on a
 * fence right after every upload, before DH's own future completes and the render thread may
 * bind the buffer — and (2) the render thread binds the object, which it does for every draw.
 * Deletion tasks are NOT diverted: DH zeroes a buffer's id under its draw lock when closing it,
 * and that must keep happening between frames on the render thread, or a section would vanish
 * for one frame. Only creation and the expensive copies run here.
 *
 * <p>Any failure (window creation, context, too many GL errors) turns the feature off and
 * leaves DH's original render-thread path untouched.
 */
public final class GpuUploadWorker {

	public static final GpuUploadWorker INSTANCE = new GpuUploadWorker();

	private static final Logger LOG = DhAffinity.LOG;
	private static final String THREAD_NAME = "DH-GPU Upload Thread[0]";
	private static final long FENCE_TIMEOUT_NANOS = 1_000_000_000L;
	private static final int FENCE_RETRIES = 5;

	/** A diverted DH task with the name DH gave it (the name decides pacing). */
	private record Task(String name, Runnable runnable) {}

	private static final String SETUP_TASK = "LodBufferContainer Setup";
	private static final Task POISON = new Task("poison", () -> {});

	private final GpuUploadOffloader offloader = new GpuUploadOffloader();
	private final UploadPacer pacer = new UploadPacer();
	/** Guards both queues; also the monitor the worker waits on for new tasks / frame ticks. */
	private final Object lock = new Object();
	/** Uploads, index buffers, global IBO, poison: never gated, always drained first. */
	private final ArrayDeque<Task> ready = new ArrayDeque<>();
	/** One "Setup" per section: admitted by the pacer only when nothing else is waiting. */
	private final ArrayDeque<Task> setups = new ArrayDeque<>();
	private final AtomicBoolean startRequested = new AtomicBoolean();
	private final AtomicBoolean publishPending = new AtomicBoolean();
	private final AtomicLong tasksRun = new AtomicLong();
	private final AtomicLong taskNanos = new AtomicLong();
	private final AtomicLong fences = new AtomicLong();
	private final AtomicLong fenceNanos = new AtomicLong();
	private final AtomicLong taskErrors = new AtomicLong();
	private volatile int queueHighWater;
	private volatile Thread thread;
	private volatile long windowHandle;
	private volatile boolean contextReady;
	/** Cleared before the queue is drained on fail-over/shutdown so a late producer hands its task back. */
	private volatile boolean accepting;
	private volatile boolean fencedThisTask;
	private volatile boolean shutdownHooked;
	/** Incremented after every worker task; read by the render thread each frame (happens-before edge). */
	private volatile long completedSequence;
	/** The config instance the offloader/pacer were last configured from; re-read only when it changes. */
	private final AtomicReference<AffinityConfig> lastConfigured = new AtomicReference<>();
	/** Queue size mirror so status never takes {@link #lock} on the render thread. */
	private final AtomicInteger queued = new AtomicInteger();
	/** Idle sleep between polls when nothing is admitted; a producer or the frame tick unparks earlier. */
	private static final long PARK_NANOS = 2_000_000L;
	/** GL_DEBUG_OUTPUT_SYNCHRONOUS: Sodium enables it on Minecraft's context as an NVIDIA workaround; mirror it here. */
	private static final int GL_DEBUG_OUTPUT_SYNCHRONOUS = 0x8242;

	private GpuUploadWorker() {}

	/** Mixin entry point: take the task if it should run on the upload thread. */
	public boolean tryRedirect(String name, Runnable task) {
		AffinityConfig cfg = DhAffinity.get().config();
		AffinityConfig seen = lastConfigured.get();
		if (cfg != seen && lastConfigured.compareAndSet(seen, cfg)) {
			// Reconfigure only when the config object changed (save/reload), not on every DH submission:
			// this is the hot path from DH's worker threads. Re-read after winning the race so a thread
			// holding an older object never re-applies stale settings over newer ones.
			cfg = DhAffinity.get().config();
			lastConfigured.set(cfg);
			offloader.setEnabled(cfg.enabled && cfg.offThreadGpuUpload);
			pacer.configure(cfg.uploadPacing.equals("off") ? UploadPacer.Mode.OFF : cfg.uploadPacingFixed > 0 ? UploadPacer.Mode.FIXED : UploadPacer.Mode.AUTO,
					cfg.uploadPacingFixed);
		}
		if (task == null || !offloader.shouldRedirect(name, this::requestStart)) {
			return false;
		}
		if (!DhAffinityMixinPlugin.gpuHooksAvailable()) {
			// The GLBuffer fence hook turned out to be missing (it is checked when that class loads,
			// which is after the worker may already be running): without the fence we must not divert.
			fail("the Distant Horizons upload method we fence changed; uploads stay on the render thread");
			return false;
		}
		Task entry = new Task(name, task);
		synchronized (lock) {
			if (!accepting) {
				return false; // the worker stopped: run it where it was meant to run
			}
			(SETUP_TASK.equals(name) ? setups : ready).add(entry);
			int size = ready.size() + setups.size();
			queued.set(size);
			if (size > queueHighWater) {
				queueHighWater = size;
			}
		}
		wake();
		return true;
	}

	/** Wake the worker without taking any monitor (safe from the render thread and from DH's workers). */
	private void wake() {
		Thread t = thread;
		if (t != null) {
			LockSupport.unpark(t);
		}
	}

	/** Render-thread task budget to enforce while uploads are NOT off-loaded; 0 = leave DH's default. */
	public long renderThreadBudgetNanos() {
		AffinityConfig cfg = DhAffinity.get().config();
		if (!cfg.enabled || offloader.state() == GpuUploadOffloader.State.READY) {
			return 0;
		}
		return cfg.renderThreadTaskBudgetMs > 0 ? cfg.renderThreadTaskBudgetMs * 1_000_000L : 0;
	}

	private boolean requestStart() {
		Thread worker = thread;
		if (worker != null && worker.isAlive() && contextReady) {
			// Re-enabled from the config after being switched off: the worker never went away.
			offloader.markReady();
			return true;
		}
		if (startRequested.compareAndSet(false, true)) {
			if (!DhAffinityMixinPlugin.gpuHooksAvailable()) {
				offloader.markUnsupported("this Distant Horizons version changed its GL task handler");
				return true;
			}
			try {
				// GLFW windows must be created on the main (render) thread; execute() queues from elsewhere.
				Minecraft.getInstance().execute(this::createContextOnRenderThread);
			} catch (Throwable t) {
				fail("could not schedule context creation: " + t);
			}
		}
		return true;
	}

	private void createContextOnRenderThread() {
		try {
			if (!RenderSystem.isOnRenderThread()) {
				fail("context creation did not run on the render thread");
				return;
			}
			try {
				// Creates DH's GL proxy on the render thread (it must not be first created on ours) and
				// throws when DH is not using its OpenGL renderer.
				GLProxy.getInstance();
			} catch (IllegalStateException e) {
				offloader.markUnsupported("Distant Horizons is not using its OpenGL renderer (" + e.getMessage() + ")");
				return;
			}
			if (Boolean.getBoolean("dhaffinity.gpuUpload.failContext")) {
				fail("forced failure for testing (-Ddhaffinity.gpuUpload.failContext=true)");
				return;
			}
			long mcWindow = Minecraft.getInstance().getWindow().handle();
			if (mcWindow == MemoryUtil.NULL) {
				fail("Minecraft window handle is null");
				return;
			}
			GLFW.glfwDefaultWindowHints();
			GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
			GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
			GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
			GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
			long handle;
			try {
				handle = GLFW.glfwCreateWindow(1, 1, "DH Affinity GPU upload", MemoryUtil.NULL, mcWindow);
			} finally {
				GLFW.glfwDefaultWindowHints();
			}
			if (handle == MemoryUtil.NULL) {
				fail("glfwCreateWindow returned NULL for the shared context");
				return;
			}
			windowHandle = handle;
			if (!shutdownHooked) {
				shutdownHooked = true;
				ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
			}
			accepting = true;
			Thread t = new Thread(this::run, THREAD_NAME);
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY);
			thread = t;
			t.start();
		} catch (Throwable t) {
			fail("context creation threw " + t);
		}
	}

	private void fail(String reason) {
		offloader.markFailed(reason);
		LOG.error("DH Affinity: off-thread GPU upload disabled — {}. Uploads stay on the render thread.", reason);
	}

	private void run() {
		DhAffinity core = DhAffinity.get();
		AffinityBackend backend = core.backend();
		long tid = 0;
		boolean registered = false;
		try {
			GLFW.glfwMakeContextCurrent(windowHandle);
			GL.createCapabilities();
			if (GL.getCapabilities().GL_KHR_debug) {
				// Sodium forces synchronous debug output on Minecraft's context to disable NVIDIA's threaded
				// optimisation; a shared context without it would get the driver's extra worker thread back.
				GL11.glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
			}
			// Core profile: element-array-buffer binds are VAO state and need a VAO bound; DH's upload code
			// saves and restores whatever VAO is current, so a private one keeps strict drivers happy.
			int vao = GL30.glGenVertexArrays();
			GL30.glBindVertexArray(vao);
			String version = GL11.glGetString(GL11.GL_VERSION);
			tid = backend.currentThreadId();
			if (tid != 0) {
				core.registry().register(tid, THREAD_NAME, AffinityConfig.GPU_UPLOAD_POOL);
				registered = true;
				AffinityConfig cfg = core.config();
				long mask = cfg.enabled ? cfg.maskForDhPool(AffinityConfig.GPU_UPLOAD_POOL) : 0;
				if (mask != 0) {
					backend.setCurrentThreadAffinity(mask);
				}
			}
			contextReady = true;
			offloader.markReady();
			LOG.info("DH Affinity: off-thread GPU upload ready (shared OpenGL context [{}], thread {} -> CPUs {}).", version, tid,
					MaskFormat.toCpuList(core.config().maskForDhPool(AffinityConfig.GPU_UPLOAD_POOL)));
		} catch (Throwable t) {
			fail("upload thread could not initialise its context: " + t);
			if (registered) {
				core.registry().unregister(tid);
			}
			return;
		}
		try {
			while (true) {
				Task task;
				boolean stop = false;
				synchronized (lock) {
					task = ready.poll();
					if (task == null && !setups.isEmpty() && pacer.tryAdmit(System.nanoTime())) {
						// Nothing else to do: admit one section's Setup if the pacer allows it this frame.
						// Uploads always go first so admitted sections finish (and appear) before more start.
						task = setups.poll();
					}
					if (task == null && !accepting) {
						stop = true;
					}
					queued.set(ready.size() + setups.size());
				}
				if (task == null && !stop) {
					// Sleep until a producer or the frame tick unparks us; bounded so we stay fail-open.
					LockSupport.parkNanos(this, PARK_NANOS);
					continue;
				}
				if (task == null || task == POISON) {
					handBackQueuedTasks();
					return;
				}
				long t0 = System.nanoTime();
				fencedThisTask = false;
				try {
					task.runnable().run();
				} catch (Throwable t) {
					// DH's own task lambdas catch Exception; anything escaping is an Error or a broken
					// assumption about this thread. Either way this path is not safe to keep using.
					taskErrors.incrementAndGet();
					LOG.error("DH Affinity: a DH buffer task threw on the upload thread; switching uploads back to the render thread.", t);
					fail("a DH buffer task threw on the upload thread: " + t);
					handBackQueuedTasks();
					return;
				}
				boolean idle;
				synchronized (lock) {
					idle = ready.isEmpty() && setups.isEmpty();
				}
				if (!fencedThisTask && idle) {
					GL11.glFlush(); // creation-only tasks issued commands too; do not leave them buffered
				}
				taskNanos.addAndGet(System.nanoTime() - t0);
				tasksRun.incrementAndGet();
				completedSequence++;
				publishToRenderThread();
				int errors = drainGlErrors();
				if (errors > 0 && offloader.recordGlErrors(errors)) {
					LOG.error("DH Affinity: off-thread GPU upload disabled after {} OpenGL errors on the upload thread; uploads return to the render thread.",
							offloader.glErrorCount());
					handBackQueuedTasks();
					return;
				}
				if (offloader.state() == GpuUploadOffloader.State.FAILED) {
					handBackQueuedTasks();
					return;
				}
			}
		} finally {
			contextReady = false;
			if (offloader.state() != GpuUploadOffloader.State.FAILED) {
				fail("upload thread exited");
			}
			handBackQueuedTasks();
			try {
				GL11.glFinish();
				GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
			} catch (Throwable ignored) {
				// context already gone
			}
			if (registered) {
				core.registry().unregister(tid);
			}
		}
	}

	/**
	 * Game shutdown (render thread): stop diverting, let the worker finish what it has, release its
	 * context, then destroy the hidden window before Minecraft terminates GLFW.
	 */
	private void shutdown() {
		Thread worker = thread;
		if (worker == null) {
			return;
		}
		offloader.markFailed("game shutting down");
		synchronized (lock) {
			accepting = false;
			ready.add(POISON);
		}
		wake();
		try {
			worker.join(1_500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		long handle = windowHandle;
		if (!worker.isAlive() && handle != MemoryUtil.NULL) {
			windowHandle = MemoryUtil.NULL;
			try {
				GLFW.glfwDestroyWindow(handle);
			} catch (Throwable t) {
				LOG.debug("DH Affinity: could not destroy the upload window", t);
			}
		}
	}

	/**
	 * After the feature switched itself off, whatever is still queued here must run on the render
	 * thread — in order, before anything DH queues next — so a Close never overtakes its Upload.
	 */
	private void handBackQueuedTasks() {
		List<Task> pending = new ArrayList<>();
		synchronized (lock) {
			accepting = false;
			pending.addAll(ready);
			pending.addAll(setups);
			ready.clear();
			setups.clear();
			queued.set(0);
		}
		wake();
		int count = 0;
		for (Task task : pending) {
			if (task != POISON) {
				handBack(task);
				count++;
			}
		}
		if (count > 0) {
			LOG.info("DH Affinity: handed {} queued buffer tasks back to the render thread.", count);
		}
	}

	private static void handBack(Task task) {
		try {
			RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread(GpuUploadOffloader.HANDBACK_TASK, task.runnable());
		} catch (Throwable t) {
			LOG.error("DH Affinity: could not hand a queued buffer task back to the render thread", t);
		}
	}

	/**
	 * Called by the render thread every frame (mixin): the volatile read publishes all earlier
	 * worker writes, and the tick drives the pacer's frame clock and adaptation.
	 */
	public void acknowledgeOnRenderThread() {
		long ignored = completedSequence;
		if (offloader.state() == GpuUploadOffloader.State.READY) {
			pacer.onFrame(System.nanoTime());
			wake(); // never a monitor here: DH's workers must not be able to park the render thread
		}
	}

	/**
	 * Called (via mixin) right after DH finished issuing an upload. Blocks this thread until the
	 * GPU has completed our commands, so by the time DH marks the buffer usable the render thread
	 * can safely draw it.
	 */
	public void afterUploadOnWorker() {
		if (Thread.currentThread() != thread) {
			return;
		}
		fencedThisTask = true;
		long t0 = System.nanoTime();
		long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		if (sync == MemoryUtil.NULL) {
			GL11.glFinish();
		} else {
			try {
				int flags = GL32.GL_SYNC_FLUSH_COMMANDS_BIT;
				int result = GL32.GL_TIMEOUT_EXPIRED;
				for (int i = 0; i < FENCE_RETRIES && result == GL32.GL_TIMEOUT_EXPIRED; i++) {
					result = GL32.glClientWaitSync(sync, flags, FENCE_TIMEOUT_NANOS);
					flags = 0;
				}
				if (result == GL32.GL_WAIT_FAILED || result == GL32.GL_TIMEOUT_EXPIRED) {
					GL11.glFinish();
				}
			} finally {
				GL32.glDeleteSync(sync);
			}
		}
		fences.incrementAndGet();
		fenceNanos.addAndGet(System.nanoTime() - t0);
	}

	/**
	 * Push a no-op through DH's own render-thread queue. The render thread drains that queue
	 * before rendering, and the enqueue/poll pair gives it a happens-before edge over everything
	 * this thread wrote (DH completes its futures on our thread, so plain fields such as
	 * {@code LodRenderSection.renderBufferContainer} would otherwise be published unsafely).
	 */
	private void publishToRenderThread() {
		// Belt and braces next to the volatile sequence: one no-op per task through DH's own queue,
		// which the render thread drains before rendering (enqueue/poll on the ConcurrentLinkedQueue
		// is itself a happens-before edge). Coalesced while the render thread has not drained yet.
		if (publishPending.compareAndSet(false, true)) {
			try {
				RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread(GpuUploadOffloader.PUBLISH_TASK, () -> {
					publishPending.set(false);
					long ignored = completedSequence;
				});
			} catch (Throwable t) {
				publishPending.set(false);
			}
		}
	}

	private static int drainGlErrors() {
		int count = 0;
		while (count < 16 && GL11.glGetError() != GL11.GL_NO_ERROR) {
			count++;
		}
		return count;
	}

	private int queuedCount() {
		return queued.get();
	}

	public List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		StringBuilder sb = new StringBuilder("GPU upload thread: ").append(offloader.describe());
		long n = tasksRun.get();
		if (n > 0) {
			sb.append(" | tasks ").append(n).append(", avg ").append(String.format("%.2f", taskNanos.get() / 1_000_000.0 / n)).append(" ms")
					.append(", queue now ").append(queuedCount()).append(" (peak ").append(queueHighWater).append(')');
			long f = fences.get();
			if (f > 0) {
				sb.append(" | GPU waits ").append(f).append(", avg ").append(String.format("%.2f", fenceNanos.get() / 1_000_000.0 / f)).append(" ms");
			}
			if (taskErrors.get() > 0) {
				sb.append(" | task exceptions ").append(taskErrors.get());
			}
		}
		lines.add(sb.toString());
		if (offloader.state() == GpuUploadOffloader.State.READY) {
			lines.add("Upload pacing: " + pacer.describe());
		}
		long budget = renderThreadBudgetNanos();
		if (budget > 0) {
			lines.add("Render-thread DH task budget: " + (budget / 1_000_000L) + " ms per frame (override)");
		}
		return lines;
	}
}
