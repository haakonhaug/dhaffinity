package io.github.haakon.dhaffinity.client.diag;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the stack sampler cannot see: JVM-level stalls. Uses Flight Recorder (present in every JDK
 * and in the Zulu/Temurin JREs launchers ship) for the duration of a profile only:
 * <ul>
 *   <li>ZGC allocation stalls — a thread blocked because the collector could not keep up with the
 *       allocation rate. Invisible to the GC "pause" beans and to a Java stack sampler.</li>
 *   <li>Monitor enters / parks longer than a threshold on the render thread, with the stack.</li>
 *   <li>Stop-the-world GC pauses.</li>
 * </ul>
 * Everything is best-effort: if Flight Recorder is unavailable the profile simply lacks this section.
 */
final class JfrStallRecorder {

	private static final long THRESHOLD_MS = 8;

	private final String renderThreadName;
	private RecordingStream stream;
	private volatile boolean failed;
	private String failure = "";

	private final AtomicLong allocStalls = new AtomicLong();
	private final AtomicLong allocStallNanos = new AtomicLong();
	private final AtomicLong renderAllocStalls = new AtomicLong();
	private final AtomicLong renderAllocStallNanos = new AtomicLong();
	private final AtomicLong gcPauses = new AtomicLong();
	private final AtomicLong gcPauseNanos = new AtomicLong();
	private final Map<String, long[]> renderBlocks = new HashMap<>(); // where -> {count, nanos}

	JfrStallRecorder(Thread renderThread) {
		this.renderThreadName = renderThread == null ? "Render thread" : renderThread.getName();
	}

	/** Start recording; returns false (and records why) if Flight Recorder cannot be used here. */
	boolean start() {
		try {
			RecordingStream rs = new RecordingStream();
			rs.setReuse(false);
			rs.enable("jdk.ZAllocationStall").withoutStackTrace();
			rs.enable("jdk.GCPhasePause").withoutStackTrace();
			rs.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(THRESHOLD_MS)).withStackTrace();
			rs.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(THRESHOLD_MS)).withStackTrace();
			rs.enable("jdk.JavaMonitorWait").withThreshold(Duration.ofMillis(THRESHOLD_MS)).withStackTrace();
			rs.onEvent("jdk.ZAllocationStall", e -> {
				long nanos = e.getDuration().toNanos();
				allocStalls.incrementAndGet();
				allocStallNanos.addAndGet(nanos);
				if (isRenderThread(e.getThread())) {
					renderAllocStalls.incrementAndGet();
					renderAllocStallNanos.addAndGet(nanos);
				}
			});
			rs.onEvent("jdk.GCPhasePause", e -> {
				gcPauses.incrementAndGet();
				gcPauseNanos.addAndGet(e.getDuration().toNanos());
			});
			rs.onEvent("jdk.JavaMonitorEnter", e -> recordBlock("monitor", e));
			rs.onEvent("jdk.ThreadPark", e -> recordBlock("park", e));
			rs.onEvent("jdk.JavaMonitorWait", e -> recordBlock("wait", e));
			rs.onError(t -> {
				failed = true;
				failure = String.valueOf(t);
			});
			rs.startAsync();
			stream = rs;
			return true;
		} catch (Throwable t) { // NoClassDefFoundError without jdk.jfr, IllegalStateException, security
			failed = true;
			failure = t.toString();
			stream = null;
			return false;
		}
	}

	private boolean isRenderThread(RecordedThread t) {
		return t != null && renderThreadName.equals(t.getJavaName());
	}

	private void recordBlock(String kind, RecordedEvent e) {
		if (!isRenderThread(e.getThread())) {
			return;
		}
		String where = kind + " @ " + topFrame(e.getStackTrace());
		synchronized (renderBlocks) {
			long[] v = renderBlocks.computeIfAbsent(where, k -> new long[2]);
			v[0]++;
			v[1] += e.getDuration().toNanos();
		}
	}

	private static String topFrame(RecordedStackTrace st) {
		if (st == null) {
			return "?";
		}
		for (RecordedFrame f : st.getFrames()) {
			String cls = f.getMethod().getType().getName();
			if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")) {
				continue; // skip the JDK plumbing (Object.wait, LockSupport.park, ...) to the caller
			}
			int dot = cls.lastIndexOf('.');
			return (dot < 0 ? cls : cls.substring(dot + 1)) + "." + f.getMethod().getName();
		}
		return "?";
	}

	/** Stop recording (waits briefly for the stream to flush) and summarise. */
	List<String> stopAndReport() {
		RecordingStream rs = stream;
		stream = null;
		if (rs != null) {
			try {
				rs.close();
				rs.awaitTermination(Duration.ofMillis(1500));
			} catch (Throwable ignored) {
				// summary below uses whatever arrived
			}
		}
		List<String> lines = new ArrayList<>();
		if (rs == null || failed) {
			lines.add("JVM stalls: Flight Recorder unavailable (" + failure + ")");
			return lines;
		}
		lines.add(String.format(Locale.ROOT, "JVM during the profile: GC pauses %d (%.1f ms) | ZGC allocation stalls %d (%.1f ms), of which on the render thread %d (%.1f ms)",
				gcPauses.get(), gcPauseNanos.get() / 1e6, allocStalls.get(), allocStallNanos.get() / 1e6,
				renderAllocStalls.get(), renderAllocStallNanos.get() / 1e6));
		synchronized (renderBlocks) {
			if (renderBlocks.isEmpty()) {
				lines.add("Render thread never blocked on a lock/park for >" + THRESHOLD_MS + " ms.");
			} else {
				List<Map.Entry<String, long[]>> sorted = new ArrayList<>(renderBlocks.entrySet());
				sorted.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
				StringBuilder sb = new StringBuilder("Render thread blocked >" + THRESHOLD_MS + " ms:");
				int shown = 0;
				for (Map.Entry<String, long[]> e : sorted) {
					if (shown++ == 4) {
						break;
					}
					sb.append(' ').append(e.getKey()).append(" x").append(e.getValue()[0])
							.append(String.format(Locale.ROOT, " (%.1f ms)", e.getValue()[1] / 1e6)).append(';');
				}
				lines.add(sb.toString());
			}
		}
		return lines;
	}
}
