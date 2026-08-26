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
 * <p>GL-free and thread-safe: the render thread calls {@link #onFrame(long)}, the upload thread
 * calls {@link #tryAdmit(long)}; the worker waits on its own monitor, which the frame tick notifies.
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

	private Mode mode = Mode.AUTO;
	private int fixedBudget = 4;
	private double budget = INITIAL_BUDGET;
	private int admittedThisFrame;
	private boolean deniedThisFrame;
	private long lastTickNanos;
	private double avgFrameNanos;
	private int cleanStreak;
	private final boolean[] hitchHistory = new boolean[HISTORY];
	private final int[] admittedHistory = new int[HISTORY];
	private int historyIndex;
	private long totalAdmitted;
	private long totalDelays;
	private long backoffs;
	private long increases;

	/** Idempotent: called on every diverted task with the current config; only a real change resets the controller. */
	public synchronized void configure(Mode mode, int fixedBudget) {
		int wanted = mode == Mode.FIXED ? Math.max(FLOOR, Math.min(CAP, fixedBudget)) : this.fixedBudget;
		if (this.mode == mode && this.fixedBudget == wanted) {
			return;
		}
		this.mode = mode;
		this.fixedBudget = wanted;
		if (mode == Mode.AUTO) {
			budget = INITIAL_BUDGET;
			cleanStreak = 0;
		}
	}

	/** Render-thread frame tick: adapt the budget and open the next frame's allowance. */
	public synchronized void onFrame(long nowNanos) {
		if (lastTickNanos != 0) {
			long delta = nowNanos - lastTickNanos;
			// A gap longer than the stale threshold is a clock restart (menu, loading), not a frame.
			if (delta > 0 && delta <= CLOCK_STALE_NANOS) {
				double avg = avgFrameNanos == 0 ? delta : avgFrameNanos;
				boolean hitch = delta > Math.max(HITCH_NANOS, avg * HITCH_FACTOR);
				if (!hitch) {
					// Learn the normal pace from clean frames only, so sustained stutter cannot raise
					// the threshold until it looks normal.
					avgFrameNanos = avgFrameNanos == 0 ? delta : avgFrameNanos + AVG_ALPHA * (delta - avgFrameNanos);
				}
				record(hitch);
			}
		}
		lastTickNanos = nowNanos;
		admittedThisFrame = 0;
		deniedThisFrame = false;
	}

	private void record(boolean hitch) {
		hitchHistory[historyIndex] = hitch;
		historyIndex = (historyIndex + 1) % HISTORY;
		admittedHistory[historyIndex] = 0; // the slot the coming frame will count into
		if (mode != Mode.AUTO) {
			return;
		}
		int hitches = 0;
		int admitted = 0;
		for (int i = 0; i < HISTORY; i++) {
			if (hitchHistory[i]) {
				hitches++;
			}
			admitted += admittedHistory[i];
		}
		if (hitch) {
			cleanStreak = 0;
			if (hitches >= HITCHES_FOR_DECREASE && admitted > 0) {
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

	/** May one more section be handed over in this frame? Records the admission when yes. */
	public synchronized boolean tryAdmit(long nowNanos) {
		if (mode == Mode.OFF || lastTickNanos == 0 || nowNanos - lastTickNanos > CLOCK_STALE_NANOS) {
			totalAdmitted++;
			return true; // nothing is being rendered, or pacing is off
		}
		int allowance = mode == Mode.FIXED ? fixedBudget : (int) Math.max(FLOOR, Math.floor(budget));
		if (admittedThisFrame >= allowance) {
			if (!deniedThisFrame) {
				deniedThisFrame = true;
				totalDelays++; // once per frame in which the budget held something back
			}
			return false;
		}
		admittedThisFrame++;
		admittedHistory[historyIndex] = admittedHistory[historyIndex] + 1;
		totalAdmitted++;
		return true;
	}

	public synchronized double budget() {
		return mode == Mode.FIXED ? fixedBudget : budget;
	}

	public synchronized Mode mode() {
		return mode;
	}

	public synchronized String describe() {
		if (mode == Mode.OFF) {
			return "off (sections are handed over as soon as they are ready)";
		}
		StringBuilder sb = new StringBuilder(mode == Mode.AUTO ? "auto" : "fixed");
		sb.append(" — budget ").append(mode == Mode.FIXED ? fixedBudget : (int) Math.floor(budget)).append(" sections/frame");
		if (mode == Mode.AUTO) {
			sb.append(" (backoffs ").append(backoffs).append(", increases ").append(increases).append(", avg frame ")
					.append(String.format("%.1f", avgFrameNanos / 1_000_000.0)).append(" ms)");
		}
		sb.append(" | admitted ").append(totalAdmitted).append(", frames at limit ").append(totalDelays);
		if (lastTickNanos == 0 || System.nanoTime() - lastTickNanos > CLOCK_STALE_NANOS) {
			sb.append(" | frame clock idle (not pacing)");
		}
		return sb.toString();
	}

	// Test hooks
	synchronized long backoffs() {
		return backoffs;
	}

	synchronized long increases() {
		return increases;
	}
}
