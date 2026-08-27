package io.github.haakon.dhaffinity.client.diag;

import io.github.haakon.dhaffinity.core.DhAffinity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Answers "what was the render thread doing during the hitches?" without an external profiler.
 * A sampler thread grabs the render thread's stack ~250 times a second; every frame that
 * {@link FrameStats} classifies as a hitch attributes the samples taken during it to a coarse
 * category (DH rendering, vanilla chunk building, shader compile, driver call, this mod, …) and
 * the most frequent concrete method in that category. Sampling another thread's stack briefly
 * stops it, so this is an on-demand diagnostic, not something that runs all the time.
 */
public final class HitchProfiler implements FrameStats.FrameListener {

	public static final HitchProfiler INSTANCE = new HitchProfiler();

	private static final long SAMPLE_INTERVAL_NANOS = 4_000_000L;
	private static final int RING = 8192; // ~32 s of samples

	private final long[] sampleTimes = new long[RING];
	private final StackTraceElement[][] samples = new StackTraceElement[RING][];
	private final byte[] sampleStates = new byte[RING]; // 0 runnable, 1 blocked on a lock, 2 waiting/parked
	private int generation;
	private int head;
	private long sampleCount;
	private volatile boolean running;
	private volatile Thread renderThread;
	private long endNanos;
	private int frames;
	private int hitchFrames;
	private long samplesInHitches;
	private long samplesOutside;
	private final Map<String, Integer> hitchCategories = new HashMap<>();
	private final Map<String, Map<String, Integer>> hitchDetails = new HashMap<>();
	private final Map<String, Integer> outsideCategories = new HashMap<>();
	/** Frame-time histogram for the window: <16, 16-33, 33-50, 50-100, 100-250, >250 ms. */
	private final int[] histogram = new int[6];
	private static final long[] HISTOGRAM_EDGES_MS = {16, 33, 50, 100, 250};
	private final long[] longestNanos = new long[5];
	private final String[] longestWhat = new String[5];
	private JfrStallRecorder jfr;
	/** Frames that began before the profile did (the command's own frame) are not counted. */
	private long profileStartNanos;
	private Consumer<List<String>> reporter;

	private HitchProfiler() {}

	public boolean isRunning() {
		return running;
	}

	/** Start (from the render thread) for {@code seconds}; the report goes to {@code reporter} when done. */
	public synchronized boolean start(int seconds, Consumer<List<String>> reporter) {
		if (running) {
			return false;
		}
		this.renderThread = Thread.currentThread();
		this.reporter = reporter;
		this.endNanos = System.nanoTime() + seconds * 1_000_000_000L;
		head = 0;
		sampleCount = 0;
		frames = 0;
		hitchFrames = 0;
		samplesInHitches = 0;
		samplesOutside = 0;
		hitchCategories.clear();
		hitchDetails.clear();
		outsideCategories.clear();
		java.util.Arrays.fill(histogram, 0);
		java.util.Arrays.fill(longestNanos, 0);
		java.util.Arrays.fill(longestWhat, null);
		jfr = null;
		profileStartNanos = System.nanoTime();
		running = true;
		generation++;
		int myGeneration = generation;
		FrameStats.INSTANCE.addListener(this);
		Thread sampler = new Thread(() -> sampleLoop(myGeneration), "DHAffinity-Profiler");
		sampler.setDaemon(true);
		sampler.setPriority(Thread.NORM_PRIORITY);
		sampler.start();
		return true;
	}

	public void stop() {
		List<String> report;
		JfrStallRecorder recorder;
		Consumer<List<String>> r;
		synchronized (this) {
			if (!running) {
				return;
			}
			running = false;
			FrameStats.INSTANCE.removeListener(this);
			report = buildReport();
			recorder = jfr;
			jfr = null;
			java.util.Arrays.fill(samples, null); // ~100 MB of stack traces otherwise stays referenced
			r = reporter;
			reporter = null;
		}
		// Stopping Flight Recorder waits for its buffers to drain: never do that on the render thread.
		Thread finisher = new Thread(() -> {
			if (recorder != null) {
				try {
					report.addAll(recorder.stopAndReport());
				} catch (Throwable t) {
					report.add("JVM stalls: Flight Recorder summary failed (" + t + ")");
				}
			}
			for (String line : report) {
				DhAffinity.LOG.info("[profile] {}", line);
			}
			if (r != null) {
				r.accept(report);
			}
		}, "DHAffinity-Profiler-Report");
		finisher.setDaemon(true);
		finisher.start();
	}

	private void sampleLoop(int myGeneration) {
		Thread target = renderThread;
		// Flight Recorder's first start costs hundreds of milliseconds: do it here, never on the render thread.
		JfrStallRecorder recorder = null;
		try {
			recorder = new JfrStallRecorder(target);
			recorder.start(); // a failed start is kept: the report then says why the JVM section is missing
		} catch (Throwable t) { // no jdk.jfr module at all
			recorder = null;
		}
		boolean published = false;
		synchronized (this) {
			if (generation == myGeneration && running) {
				jfr = recorder;
				published = true;
			}
		}
		if (!published && recorder != null) {
			recorder.stopAndReport(); // the profile was stopped (or replaced) while JFR was starting: do not leak the stream
		}
		while (running && target != null) {
			long now = System.nanoTime();
			synchronized (this) {
				if (generation != myGeneration) {
					return; // a newer profile replaced this sampler
				}
			}
			if (now >= endNanos) {
				stop();
				return;
			}
			Thread.State state = target.getState();
			StackTraceElement[] stack = target.getStackTrace();
			synchronized (this) {
				if (generation != myGeneration) {
					return;
				}
				sampleTimes[head] = now;
				samples[head] = stack;
				sampleStates[head] = state == Thread.State.BLOCKED ? (byte) 1
						: state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING ? (byte) 2 : (byte) 0;
				head = (head + 1) % RING;
				sampleCount++;
			}
			LockSupport.parkNanos(SAMPLE_INTERVAL_NANOS);
		}
	}

	@Override
	public synchronized void onFrame(long startNanos, long endNanos, boolean hitch) {
		if (!running || startNanos < profileStartNanos) {
			return; // not running, or the frame that issued the command
		}
		frames++;
		if (hitch) {
			hitchFrames++;
		}
		long duration = endNanos - startNanos;
		int bucket = 0;
		while (bucket < HISTOGRAM_EDGES_MS.length && duration > HISTOGRAM_EDGES_MS[bucket] * 1_000_000L) {
			bucket++;
		}
		histogram[bucket]++;
		Map<String, Integer> frameCategories = hitch ? new HashMap<>() : null;
		int n = (int) Math.min(sampleCount, RING);
		for (int i = 0; i < n; i++) {
			int idx = ((head - 1 - i) % RING + RING) % RING;
			long t = sampleTimes[idx];
			if (t < startNanos) {
				break; // older than this frame
			}
			if (t > endNanos) {
				continue;
			}
			StackTraceElement[] stack = samples[idx];
			if (stack == null || stack.length == 0) {
				continue;
			}
			String[] cls = classify(stack);
			if (sampleStates[idx] == 1) {
				cls[1] = cls[1] + " (BLOCKED on a lock)";
			} else if (sampleStates[idx] == 2) {
				cls[1] = cls[1] + " (waiting)";
			}
			if (hitch) {
				samplesInHitches++;
				hitchCategories.merge(cls[0], 1, Integer::sum);
				hitchDetails.computeIfAbsent(cls[0], k -> new HashMap<>()).merge(cls[1], 1, Integer::sum);
				frameCategories.merge(cls[0] + " — " + cls[1], 1, Integer::sum);
			} else {
				samplesOutside++;
				outsideCategories.merge(cls[0], 1, Integer::sum);
			}
		}
		if (hitch) {
			rememberLongest(duration, frameCategories);
		}
	}

	/** Keep the five longest spike frames with what dominated each, so storms have names. */
	private void rememberLongest(long duration, Map<String, Integer> categories) {
		int slot = -1;
		for (int i = 0; i < longestNanos.length; i++) {
			if (duration > longestNanos[i] && (slot < 0 || longestNanos[i] < longestNanos[slot])) {
				slot = i;
			}
		}
		if (slot < 0) {
			return;
		}
		String what = categories.isEmpty() ? "(no samples)"
				: categories.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("?");
		longestNanos[slot] = duration;
		longestWhat[slot] = what;
	}

	private static boolean isPlumbing(String className) {
		return className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.")
				|| className.startsWith("org.lwjgl.system") || className.startsWith("com.google.common");
	}

	/** {category, concrete method} for one sample. */
	static String[] classify(StackTraceElement[] stack) {
		StackTraceElement top = null;
		for (StackTraceElement e : stack) {
			if (!isPlumbing(e.getClassName())) {
				top = e;
				break;
			}
		}
		if (top == null) {
			return new String[] {"JVM internals", stack[0].getClassName() + "." + stack[0].getMethodName()};
		}
		String glCall = null;
		StackTraceElement owner = top;
		if (top.getClassName().startsWith("org.lwjgl.")) {
			glCall = top.getMethodName();
			owner = null;
			for (StackTraceElement e : stack) {
				if (!isPlumbing(e.getClassName()) && !e.getClassName().startsWith("org.lwjgl.")) {
					owner = e;
					break;
				}
			}
			if (owner == null) {
				return new String[] {"OpenGL driver call", "org.lwjgl." + glCall};
			}
		}
		String category = ownerCategory(owner.getClassName());
		if (category.equals(DH_TASKS) || category.equals(DH_RENDERING) || category.equals("DH other")) {
			// DH's draw loop and its queued GL tasks share the same buffer classes; only the task drain is
			// "DH GL tasks", everything else under a DH class in the frame is drawing.
			boolean inTaskDrain = false;
			for (StackTraceElement e : stack) {
				if (e.getClassName().contains("RenderThreadTaskHandler")) {
					inTaskDrain = true;
					break;
				}
			}
			category = inTaskDrain ? DH_TASKS : category.equals(DH_TASKS) ? DH_RENDERING : category;
		}
		if (glCall != null) {
			boolean audio = glCall.startsWith("nal") || glCall.startsWith("al");
			category = (audio ? "OpenAL (sound) driver call inside " : "OpenGL driver call inside ") + category;
		}
		String where = shortName(owner.getClassName()) + "." + owner.getMethodName();
		return new String[] {category, glCall != null ? where + " -> " + glCall : where};
	}

	private static String shortName(String cls) {
		int dot = cls.lastIndexOf('.');
		return dot < 0 ? cls : cls.substring(dot + 1);
	}

	/** Coarse owner buckets; vanilla classes are matched by their runtime (intermediary) names. */
	static final String DH_TASKS = "DH GL tasks on render thread (buffer create/delete)";
	static final String DH_RENDERING = "DH rendering (draw calls)";

	static String ownerCategory(String cls) {
		if (cls.startsWith("com.seibel.distanthorizons")) {
			if (cls.contains("RenderThreadTaskHandler")) {
				return DH_TASKS;
			}
			if (cls.contains("glObject") || cls.contains(".render") || cls.contains("Render")) {
				return DH_RENDERING;
			}
			return "DH other";
		}
		if (cls.startsWith("io.github.haakon.dhaffinity")) {
			return "DH Affinity mod";
		}
		if (cls.startsWith("net.irisshaders") || cls.startsWith("net.coderbot")) {
			return "Iris shaders";
		}
		if (cls.startsWith("net.caffeinemc") || cls.startsWith("me.jellysquid")) {
			return "Sodium";
		}
		String simple = shortName(cls);
		int dollar = simple.indexOf('$');
		String outer = dollar < 0 ? simple : simple.substring(0, dollar);
		switch (outer) {
			case "class_846", "class_9810", "class_11516", "class_11517", "class_6850", "class_287":
				return "vanilla chunk building/upload";
			case "class_761", "class_4599":
				return "vanilla world rendering (LevelRenderer)";
			case "class_8679", "class_769":
				return "vanilla chunk visibility/culling";
			case "class_10151", "class_5944", "class_10141", "class_279":
				return "shader compile/load";
			case "class_1060", "class_1092":
				return "texture/model loading";
			case "class_10865", "class_10860", "class_10866":
				return "vanilla GPU commands (Blaze3D)";
			case "class_757":
				return "vanilla frame rendering (GameRenderer)";
			case "class_310":
				return "Minecraft main loop / tick";
			case "class_638":
				return "client world tick";
			case "class_898":
				return "entity rendering";
			case "class_702":
				return "particles";
			case "class_1140":
				return "sound engine";
			case "class_765":
				return "light texture";
			default:
				break;
		}
		if (cls.startsWith("net.minecraft.") || cls.startsWith("com.mojang.")) {
			return "vanilla other (" + outer + ")";
		}
		String[] parts = cls.split("\\.");
		return (parts.length >= 2 ? parts[0] + "." + parts[1] : cls) + " (other mod)";
	}

	private List<String> buildReport() {
		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "Render-thread profile: %d frames, %d spike frames (>2x avg, >8 ms), %d samples inside spikes, %d outside.",
				frames, hitchFrames, samplesInHitches, samplesOutside));
		if (samplesInHitches == 0) {
			lines.add("No samples fell inside spike frames — either no spikes happened or the sampler could not keep up.");
		} else {
			lines.add("Inside spike frames the render thread was in:");
			List<Map.Entry<String, Integer>> sorted = new ArrayList<>(hitchCategories.entrySet());
			sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
			int shown = 0;
			for (Map.Entry<String, Integer> e : sorted) {
				if (shown++ >= 8) {
					break;
				}
				Map<String, Integer> detail = hitchDetails.getOrDefault(e.getKey(), Map.of());
				String top = detail.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("?");
				lines.add(String.format(Locale.ROOT, "  %3d%%  %s — mostly %s", Math.round(100.0 * e.getValue() / samplesInHitches), e.getKey(), top));
			}
		}
		if (samplesOutside > 0) {
			List<Map.Entry<String, Integer>> sorted = new ArrayList<>(outsideCategories.entrySet());
			sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
			StringBuilder sb = new StringBuilder("For comparison, smooth frames: ");
			int shown = 0;
			for (Map.Entry<String, Integer> e : sorted) {
				if (shown++ >= 3) {
					break;
				}
				sb.append(Math.round(100.0 * e.getValue() / samplesOutside)).append("% ").append(e.getKey()).append("; ");
			}
			lines.add(sb.toString());
		}
		lines.add(String.format(Locale.ROOT, "Frame times: <16 ms %d | 16-33 %d | 33-50 %d | 50-100 %d | 100-250 %d | >250 ms %d",
				histogram[0], histogram[1], histogram[2], histogram[3], histogram[4], histogram[5]));
		StringBuilder longest = new StringBuilder("Longest frames:");
		Integer[] order = {0, 1, 2, 3, 4};
		java.util.Arrays.sort(order, (a, b) -> Long.compare(longestNanos[b], longestNanos[a]));
		int listed = 0;
		for (int i : order) {
			if (longestNanos[i] == 0) {
				continue;
			}
			longest.append(listed++ == 0 ? " " : "; ").append(String.format(Locale.ROOT, "%.0f ms (%s)", longestNanos[i] / 1e6, longestWhat[i]));
		}
		if (listed > 0) {
			lines.add(longest.toString());
		}
		return lines;
	}
}
