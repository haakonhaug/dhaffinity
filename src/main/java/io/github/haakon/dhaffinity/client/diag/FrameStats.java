package io.github.haakon.dhaffinity.client.diag;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Cheap per-frame bookkeeping so {@code /dhaffinity status} can say whether the game is hitching
 * and whether garbage collection is the reason. Sixty one-second buckets; the frame hook runs
 * once per rendered frame in a world and costs a few nanoseconds.
 */
public final class FrameStats {

	public static final FrameStats INSTANCE = new FrameStats();

	static final int BUCKETS = 60;
	/** Absolute floors; a frame also has to be well above the recent average to count as a hitch. */
	static final long HITCH_NANOS = 33_000_000L;
	static final long SEVERE_NANOS = 50_000_000L;
	static final double HITCH_FACTOR = 2.5;
	static final double SEVERE_FACTOR = 4.0;
	private static final double AVG_ALPHA = 0.05;

	private final int[] frames = new int[BUCKETS];
	private final int[] hitches = new int[BUCKETS];
	private final int[] severe = new int[BUCKETS];
	private final long[] worstNanos = new long[BUCKETS];
	private final long[] gcCount = new long[BUCKETS];
	private final long[] gcMillis = new long[BUCKETS];
	private final long[] bucketSecond = new long[BUCKETS];
	private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
	private long lastFrameNanos;
	/** Exponential moving average of the frame time, so thresholds follow the game's own pace (e.g. 32 fps with shaders). */
	private double avgFrameNanos;
	private long lastGcCount;
	private long lastGcMillis;
	private long lastGcSampleSecond = -1;
	private volatile boolean registered;
	private final List<FrameListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Notified at the end of every frame with its span and hitch classification. */
	public interface FrameListener {
		void onFrame(long startNanos, long endNanos, boolean hitch);
	}

	public void addListener(FrameListener listener) {
		listeners.add(listener);
	}

	public void removeListener(FrameListener listener) {
		listeners.remove(listener);
	}

	private FrameStats() {}

	/** Hook the per-frame event; safe to call once from the client entrypoint. */
	public void register() {
		if (registered) {
			return;
		}
		registered = true;
		WorldRenderEvents.START_MAIN.register(context -> onFrame(System.nanoTime()));
	}

	/** Package-visible for tests: {@code nowNanos} is monotonic time. */
	synchronized void onFrame(long nowNanos) {
		long second = nowNanos / 1_000_000_000L;
		int b = (int) (second % BUCKETS);
		if (bucketSecond[b] != second) {
			bucketSecond[b] = second;
			frames[b] = 0;
			hitches[b] = 0;
			severe[b] = 0;
			worstNanos[b] = 0;
			gcCount[b] = 0;
			gcMillis[b] = 0;
		}
		boolean hitchFrame = false;
		long frameStart = lastFrameNanos;
		if (lastFrameNanos != 0) {
			long delta = nowNanos - lastFrameNanos;
			if (delta < 5_000_000_000L) {
				frames[b]++;
				double avg = avgFrameNanos == 0 ? delta : avgFrameNanos;
				if (delta > Math.max(HITCH_NANOS, avg * HITCH_FACTOR)) {
					hitches[b]++;
					hitchFrame = true;
				}
				if (delta > Math.max(SEVERE_NANOS, avg * SEVERE_FACTOR)) {
					severe[b]++;
				}
				if (delta > worstNanos[b]) {
					worstNanos[b] = delta;
				}
				if (delta <= Math.max(HITCH_NANOS, avg * HITCH_FACTOR)) {
					avgFrameNanos = avgFrameNanos == 0 ? delta : avgFrameNanos + AVG_ALPHA * (delta - avgFrameNanos);
				}
			}
		}
		lastFrameNanos = nowNanos;
		if (frameStart != 0 && !listeners.isEmpty()) {
			for (FrameListener l : listeners) {
				try {
					l.onFrame(frameStart, nowNanos, hitchFrame);
				} catch (Throwable ignored) {
					// diagnostics must never affect the frame
				}
			}
		}
		if (second != lastGcSampleSecond) {
			boolean gap = lastGcSampleSecond >= 0 && second - lastGcSampleSecond > 5;
			lastGcSampleSecond = second;
			sampleGc(b, gap);
		}
	}

	/** {@code gap}: no frames for a while (menu, loading) — re-baseline instead of attributing the gap's GC to now. */
	private void sampleGc(int b, boolean gap) {
		long count = 0;
		long millis = 0;
		for (GarbageCollectorMXBean bean : gcBeans) {
			if (!isPauseBean(bean)) {
				continue; // "ZGC ... Cycles" / "G1 Concurrent GC" report concurrent work, not stop-the-world time
			}
			long c = bean.getCollectionCount();
			long t = bean.getCollectionTime();
			if (c > 0) {
				count += c;
			}
			if (t > 0) {
				millis += t;
			}
		}
		if (!gap && (lastGcCount != 0 || lastGcMillis != 0)) {
			gcCount[b] += Math.max(0, count - lastGcCount);
			gcMillis[b] += Math.max(0, millis - lastGcMillis);
		}
		lastGcCount = count;
		lastGcMillis = millis;
	}

	/** Beans whose collection time is actual pause time (ZGC/Shenandoah "Cycles" and G1's concurrent bean are not). */
	static boolean isPauseBean(GarbageCollectorMXBean bean) {
		String name = bean.getName();
		return !name.contains("Cycles") && !name.contains("Concurrent");
	}

	/** Test hook: inject GC deltas without MXBeans. */
	synchronized void recordGc(long nowNanos, long count, long millis) {
		int b = (int) ((nowNanos / 1_000_000_000L) % BUCKETS);
		gcCount[b] += count;
		gcMillis[b] += millis;
	}

	/** Summary over the last 60 seconds. */
	public synchronized Summary summary(long nowNanos) {
		long nowSecond = nowNanos / 1_000_000_000L;
		int f = 0;
		int h = 0;
		int s = 0;
		long worst = 0;
		long gcs = 0;
		long gcMs = 0;
		int seconds = 0;
		for (int i = 0; i < BUCKETS; i++) {
			if (bucketSecond[i] == 0 || nowSecond - bucketSecond[i] >= BUCKETS) {
				continue;
			}
			seconds++;
			f += frames[i];
			h += hitches[i];
			s += severe[i];
			worst = Math.max(worst, worstNanos[i]);
			gcs += gcCount[i];
			gcMs += gcMillis[i];
		}
		return new Summary(f, h, s, worst / 1_000_000.0, gcs, gcMs, seconds);
	}

	/** Last-60-second numbers; {@code seconds} = how many one-second buckets hold data. */
	public record Summary(int frames, int hitches, int severe, double worstMs, long gcCollections, long gcMillis, int seconds) {
		public double fps() {
			return seconds == 0 ? 0 : frames / (double) seconds;
		}
	}

	public List<String> statusLines() {
		return statusLines(System.nanoTime());
	}

	List<String> statusLines(long nowNanos) {
		List<String> lines = new ArrayList<>();
		Summary s = summary(nowNanos);
		if (s.frames() == 0) {
			lines.add("Frames (last 60 s): none rendered in a world yet");
		} else {
			lines.add("Frames (last " + s.seconds() + " s): " + s.frames() + " (~" + String.format("%.1f", s.fps()) + " fps) | hitches (>2.5x avg, >33 ms): " + s.hitches()
					+ ", severe (>4x avg, >50 ms): " + s.severe() + " | avg frame " + String.format("%.1f", avgFrameNanos / 1_000_000.0)
					+ " ms, worst " + String.format("%.1f", s.worstMs()) + " ms");
		}
		StringBuilder gc = new StringBuilder("GC (last 60 s): ").append(s.gcCollections()).append(" collections, ")
				.append(s.gcMillis()).append(" ms paused");
		List<String> names = new ArrayList<>();
		for (GarbageCollectorMXBean bean : gcBeans) {
			if (isPauseBean(bean)) {
				names.add(bean.getName());
			}
		}
		gc.append(" [").append(String.join(", ", names)).append("] | heap max ")
				.append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB");
		lines.add(gc.toString());
		if (s.hitches() > 0 && s.gcMillis() > 0 && s.gcMillis() * 1.0 / Math.max(1, s.hitches()) > 15) {
			lines.add("Hint: stop-the-world GC time is high relative to hitches — see the GC section of the README.");
		}
		return lines;
	}
}
