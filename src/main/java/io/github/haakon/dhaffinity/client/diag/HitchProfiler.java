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

	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		FrameStats.INSTANCE.removeListener(this);
		List<String> report = buildReport();
		java.util.Arrays.fill(samples, null); // ~100 MB of stack traces otherwise stays referenced
		Consumer<List<String>> r = reporter;
		reporter = null;
		for (String line : report) {
			DhAffinity.LOG.info("[profile] {}", line);
		}
		if (r != null) {
			r.accept(report);
		}
	}

	private void sampleLoop(int myGeneration) {
		Thread target = renderThread;
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
		if (!running) {
			return;
		}
		frames++;
		if (hitch) {
			hitchFrames++;
		}
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
			} else {
				samplesOutside++;
				outsideCategories.merge(cls[0], 1, Integer::sum);
			}
		}
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
		if (glCall != null) {
			category = "OpenGL driver call inside " + category;
		}
		String where = shortName(owner.getClassName()) + "." + owner.getMethodName();
		return new String[] {category, glCall != null ? where + " -> " + glCall : where};
	}

	private static String shortName(String cls) {
		int dot = cls.lastIndexOf('.');
		return dot < 0 ? cls : cls.substring(dot + 1);
	}

	/** Coarse owner buckets; vanilla classes are matched by their runtime (intermediary) names. */
	static String ownerCategory(String cls) {
		if (cls.startsWith("com.seibel.distanthorizons")) {
			if (cls.contains("RenderThreadTaskHandler") || cls.contains("glObject")) {
				return "DH GL tasks on render thread (buffer create/delete)";
			}
			if (cls.contains(".render") || cls.contains("Render")) {
				return "DH rendering";
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
		lines.add(String.format(Locale.ROOT, "Render-thread profile: %d frames, %d hitch frames, %d samples inside hitches, %d outside.",
				frames, hitchFrames, samplesInHitches, samplesOutside));
		if (samplesInHitches == 0) {
			lines.add("No samples fell inside hitch frames — either no hitches happened or the sampler could not keep up.");
		} else {
			lines.add("Inside hitches the render thread was in:");
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
		return lines;
	}
}
