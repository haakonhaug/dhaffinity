package io.github.haakon.dhaffinity.client.gpu;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Decides which of Distant Horizons' render-thread tasks are moved to the GPU upload thread, and
 * tracks the feature's state machine. Deliberately free of OpenGL/Minecraft types so it can be
 * unit-tested; {@link GpuUploadWorker} owns the actual context and thread.
 *
 * <p>Only the tasks that touch buffer objects — creation, upload, deletion — are redirected.
 * Buffer objects are shared between OpenGL contexts; everything else (framebuffers, shaders,
 * VAOs, screen updates) stays on the render thread untouched.
 */
public final class GpuUploadOffloader {

	/** Lifecycle of the off-thread upload path. */
	public enum State {
		/** Feature disabled in config. */
		OFF,
		/** Enabled; the shared context has not been created yet (needs one pass of the render thread). */
		STARTING,
		/** Worker thread and context are live; buffer tasks are redirected. */
		READY,
		/** Context creation or the worker failed, or the game is shutting down; everything stays on the render thread. */
		FAILED,
		/** The rendering API is not OpenGL (Vulkan path); nothing to do. */
		UNSUPPORTED
	}

	/**
	 * DH task names (see LodBufferContainer, GLVertexBuffer) that CREATE or UPLOAD buffer objects.
	 * Deletion tasks ("LodBufferContainer Close", "GLBuffer destroyAsync", "GLBuffer phantom
	 * destroy") deliberately stay on the render thread: DH zeroes a buffer's id under its draw lock
	 * when closing it, and doing that anywhere but between frames would blank a section for one
	 * frame (flicker). Deletes are cheap; the copies are what hurt.
	 */
	static final Set<String> REDIRECTED_TASKS = Set.of(
			"LodBufferContainer Setup",
			"LodBufferContainer VBO Upload",
			"LodBufferContainer IBO Upload",
			"Global IBO Creation");

	/** Name of the no-op task pushed through DH's own queue to publish worker writes to the render thread. */
	public static final String PUBLISH_TASK = "dhaffinity publish";
	/** Name used when queued worker tasks are handed back to the render thread after a failure. */
	public static final String HANDBACK_TASK = "dhaffinity handback";

	/** Worker GL errors tolerated before the feature turns itself off. */
	static final int MAX_GL_ERRORS = 20;

	private volatile State state = State.OFF;
	private volatile String failureReason = "";
	private final AtomicLong redirected = new AtomicLong();
	private final AtomicLong passedThrough = new AtomicLong();
	private final AtomicInteger glErrors = new AtomicInteger();

	public State state() {
		return state;
	}

	public String failureReason() {
		return failureReason;
	}

	public long redirectedCount() {
		return redirected.get();
	}

	public long passedThroughCount() {
		return passedThrough.get();
	}

	public int glErrorCount() {
		return glErrors.get();
	}

	/** Called whenever the config is (re)applied. */
	public void setEnabled(boolean enabled) {
		if (!enabled) {
			if (state == State.STARTING || state == State.READY) {
				state = State.OFF;
			}
			return;
		}
		if (state == State.OFF) {
			state = State.STARTING;
		}
	}

	/** The rendering API is not OpenGL; the feature is permanently inert. */
	public void markUnsupported(String reason) {
		state = State.UNSUPPORTED;
		failureReason = reason;
	}

	public void markReady() {
		if (state == State.STARTING) {
			state = State.READY;
		}
	}

	public void markFailed(String reason) {
		if (state != State.UNSUPPORTED) {
			state = State.FAILED;
			failureReason = reason;
		}
	}

	/**
	 * Decide for one queued task. {@code true} means "send it to the upload thread"; the caller
	 * must then cancel DH's own queueing. {@code startWorker} is invoked (once per STARTING pass)
	 * so the caller can kick off context creation on the render thread.
	 */
	public boolean shouldRedirect(String taskName, BooleanSupplier startWorker) {
		State s = state;
		if (s == State.STARTING) {
			if (!startWorker.getAsBoolean()) {
				passedThrough.incrementAndGet();
				return false;
			}
			s = state; // startWorker may have moved us to READY synchronously (tests) or left STARTING
		}
		if (s != State.READY || taskName == null || !REDIRECTED_TASKS.contains(taskName)) {
			passedThrough.incrementAndGet();
			return false;
		}
		redirected.incrementAndGet();
		return true;
	}

	/** Record OpenGL errors observed on the worker; too many and the feature switches itself off. */
	public boolean recordGlErrors(int count) {
		if (count <= 0) {
			return false;
		}
		int total = glErrors.addAndGet(count);
		if (total >= MAX_GL_ERRORS && state == State.READY) {
			markFailed("too many OpenGL errors on the upload thread (" + total + ")");
			return true;
		}
		return false;
	}

	public String describe() {
		return switch (state) {
			case OFF -> "off";
			case STARTING -> "starting (waiting for the render thread to create the shared context)";
			case READY -> "on (experimental) — redirected " + redirected.get() + " buffer tasks, GL errors " + glErrors.get();
			case FAILED -> "FAILED, uploads back on the render thread: " + failureReason;
			case UNSUPPORTED -> "unavailable: " + failureReason;
		};
	}
}
