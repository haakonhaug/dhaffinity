package io.github.haakon.dhaffinity.client.gpu;

/**
 * Adaptive speed limit for how many finished LOD sections are handed to the renderer per frame.
 *
 * <p>Distant Horizons on dedicated cores finishes terrain in bursts; each section that becomes
 * renderable costs the render thread and GPU something in the frame it appears. Metering the
 * hand-off ("Setup" tasks, one per section) turns a burst into a stream. The budget adapts like
 * TCP: it grows slowly while frames are smooth and halves when frames hitch — but only if DH
 * actually published sections recently, so unrelated stutter (autosave, shader compiles) does
 * not throttle DH forever. When no frames are being rendered there is nothing to protect and the
 * pacer lets everything through.
 *
 * <p>Lock-free by construction: the render thread is the only writer of the frame-side state
 * ({@link #onFrame(long)}), the upload thread the only writer of the admission-side state
 * ({@link #tryAdmit(long)}); the two sides exchange a handful of volatile fields. Nothing here can
 * park the render thread behind another thread, whatever cores that thread runs on.
 */
public final class UploadPacer {

	/** How the budget is chosen. */
	public enum Mode { AUTO, OFF, FIXED }

	static final int FLOOR = 1;
	static final int CAP = 64;
	static final double INITIAL_BUDGET = 4;
	static final int CLEAN_FRAMES_FOR_INCREASE = 10;
	static final int HISTORY = 4;
	static final int HITCHES_FOR_DECREASE = 2;
	static final long HITCH_NANOS = 33_000_000L;
	static final double HITCH_FACTOR = 2.5;
	/** No frame tick for this long = nothing is being rendered (menu, loading); a real frame is never this slow. */
	static final long CLOCK_STALE_NANOS = 1_000_000_000L;
	private static final double AVG_ALPHA = 0.05;

	// Configuration: written from any thread (config change), read by both sides.
	private volatile Mode mode = Mode.AUTO;
	private volatile int fixedBudget = 4;
	private volatile boolean resetRequested;

	// Render-thread state (single writer: onFrame).
	private double budget = INITIAL_BUDGET;
	private long lastTick;
	private double avgFrameNanos;
	private int cleanStreak;
	private final boolean[] hitchHistory = new boolean[HISTORY];
	private int historyIndex;

	// Published render thread -> upload thread. allowance is written before frameSeq so a reader that
	// sees the new frame also sees its allowance.
	private volatile int allowance = (int) INITIAL_BUDGET;
	private volatile long frameSeq;
	private volatile long lastTickNanos;

	// Upload-thread state (single writer: tryAdmit).
	private long admittedFrameSeq = -1;
	private int admittedThisFrame;
	private boolean deniedThisFrame;

	// Published for the other side / status (each has exactly one writer).
	private volatile long lastAdmittedFrameSeq = -1; // -1 = never
	private volatile long totalAdmitted;
	private volatile long totalDelays;
	private volatile long backoffs;
	private volatile long increases;
	private volatile double publishedBudget = INITIAL_BUDGET;
	private volatile double publishedAvgNanos;

	/** Idempotent; only a real change resets the controller (applied on the next frame tick). */
	public void configure(Mode mode, int fixedBudget) {
		int wanted = mode == Mode.FIXED ? Math.max(FLOOR, Math.min(CAP, fixedBudget)) : this.fixedBudget;
		if (this.mode == mode && this.fixedBudget == wanted) {
			return;
		}
		this.fixedBudget = wanted;
		this.mode = mode;
		if (mode == Mode.AUTO) {
			// The render thread applies the reset to its private state on the next tick; publish the
			// reset value right away so budget()/tryAdmit see it immediately.
			resetRequested = true;
			publishedBudget = INITIAL_BUDGET;
			allowance = (int) INITIAL_BUDGET;
		}
	}

	/** Render-thread frame tick: adapt the budget and open the next frame's allowance. Never blocks. */
	public void onFrame(long nowNanos) {
		if (resetRequested) {
			resetRequested = false;
			budget = INITIAL_BUDGET;
			cleanStreak = 0;
		}
		long seq = frameSeq;
		if (lastTick != 0) {
			long delta = nowNanos - lastTick;
			// A gap longer than the stale threshold is a clock restart (menu, loading), not a frame.
			if (delta > 0 && delta <= CLOCK_STALE_NANOS) {
				double avg = avgFrameNanos == 0 ? delta : avgFrameNanos;
				boolean hitch = delta > Math.max(HITCH_NANOS, avg * HITCH_FACTOR);
				if (!hitch) {
					// Learn the normal pace from clean frames only, so sustained stutter cannot raise
					// the threshold until it looks normal.
					avgFrameNanos = avgFrameNanos == 0 ? delta : avgFrameNanos + AVG_ALPHA * (delta - avgFrameNanos);
				}
				long lastAdmitted = lastAdmittedFrameSeq;
				boolean admittedRecently = lastAdmitted >= 0 && seq - lastAdmitted < HISTORY;
				record(hitch, admittedRecently);
			}
		}
		lastTick = nowNanos;
		publishedBudget = budget;
		publishedAvgNanos = avgFrameNanos;
		allowance = (int) Math.max(FLOOR, Math.floor(budget));
		lastTickNanos = nowNanos;
		frameSeq = seq + 1;
	}

	private void record(boolean hitch, boolean admittedRecently) {
		hitchHistory[historyIndex] = hitch;
		historyIndex = (historyIndex + 1) % HISTORY;
		if (mode != Mode.AUTO) {
			return;
		}
		int hitches = 0;
		for (int i = 0; i < HISTORY; i++) {
			if (hitchHistory[i]) {
				hitches++;
			}
		}
		if (hitch) {
			cleanStreak = 0;
			if (hitches >= HITCHES_FOR_DECREASE && admittedRecently) {
				double next = Math.max(FLOOR, budget / 2);
				if (next < budget) {
					budget = next;
					backoffs++;
				}
				// Forget the hitches we already reacted to so one burst causes one backoff.
				java.util.Arrays.fill(hitchHistory, false);
			}
		} else if (++cleanStreak >= CLEAN_FRAMES_FOR_INCREASE) {
			cleanStreak = 0;
			if (budget < CAP) {
				budget = Math.min(CAP, budget + 1);
				increases++;
			}
		}
	}

	/** Upload thread: may one more section be handed over in this frame? Records the admission when yes. */
	public boolean tryAdmit(long nowNanos) {
		Mode m = mode;
		long tick = lastTickNanos;
		if (m == Mode.OFF || tick == 0 || nowNanos - tick > CLOCK_STALE_NANOS) {
			totalAdmitted++;
			return true; // nothing is being rendered, or pacing is off
		}
		long seq = frameSeq;
		if (seq != admittedFrameSeq) {
			admittedFrameSeq = seq;
			admittedThisFrame = 0;
			deniedThisFrame = false;
		}
		int allow = m == Mode.FIXED ? Math.max(FLOOR, Math.min(CAP, fixedBudget)) : allowance;
		if (admittedThisFrame >= allow) {
			if (!deniedThisFrame) {
				deniedThisFrame = true;
				totalDelays++; // once per frame in which the budget held something back
			}
			return false;
		}
		admittedThisFrame++;
		lastAdmittedFrameSeq = seq;
		totalAdmitted++;
		return true;
	}

	public double budget() {
		return mode == Mode.FIXED ? fixedBudget : publishedBudget;
	}

	public Mode mode() {
		return mode;
	}

	public String describe() {
		Mode m = mode;
		if (m == Mode.OFF) {
			return "off (sections are handed over as soon as they are ready)";
		}
		StringBuilder sb = new StringBuilder(m == Mode.AUTO ? "auto" : "fixed");
		sb.append(" — budget ").append(m == Mode.FIXED ? fixedBudget : (int) Math.floor(publishedBudget)).append(" sections/frame");
		if (m == Mode.AUTO) {
			sb.append(" (backoffs ").append(backoffs).append(", increases ").append(increases).append(", avg frame ")
					.append(String.format("%.1f", publishedAvgNanos / 1_000_000.0)).append(" ms)");
		}
		sb.append(" | admitted ").append(totalAdmitted).append(", frames at limit ").append(totalDelays);
		long tick = lastTickNanos;
		if (tick == 0 || System.nanoTime() - tick > CLOCK_STALE_NANOS) {
			sb.append(" | frame clock idle (not pacing)");
		}
		return sb.toString();
	}

	// Test hooks
	long backoffs() {
		return backoffs;
	}

	long increases() {
		return increases;
	}
}
