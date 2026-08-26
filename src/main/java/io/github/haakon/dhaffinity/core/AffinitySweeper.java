package io.github.haakon.dhaffinity.core;

import io.github.haakon.dhaffinity.affinity.AffinityBackend;
import io.github.haakon.dhaffinity.affinity.MaskFormat;
import io.github.haakon.dhaffinity.config.AffinityConfig;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Background reconciliation loop.
 *
 * <p>Every sweep enumerates the native threads of the process and makes sure each one has the
 * mask it should have according to the <em>current</em> configuration: registered DH workers get
 * their pool's mask, the main thread gets {@code mainThreadMask}, everything else gets
 * {@code gameMask}. The current mask is read first and only written when it differs, so in the
 * steady state a sweep is one thread snapshot plus a read per thread and no writes at all.
 * Because it reconciles against desired state rather than remembering what it pinned, it
 * self-heals when something else (Process Lasso, a process-mask reset) changes affinities, and
 * OS thread-ID reuse cannot confuse it. Threads the OS refuses are retried with a growing
 * backoff instead of being blacklisted, again because IDs get reused.
 *
 * <p>Runs on its own daemon thread at minimum priority, itself pinned to {@code gameMask}, and
 * never touches the render thread.
 */
public final class AffinitySweeper implements Runnable {

	/** Immutable snapshot of counters, replaced atomically after every sweep. */
	public record Stats(
			long sweeps,
			long lastThreads,
			long lastDhThreads,
			long lastCorrected,
			long lastUnchanged,
			long lastSkipped,
			long lastGone,
			long lastFailed,
			long lastDurationMicros,
			/** Maximum after the first (cold, class-loading) sweep. */
			long maxDurationMicros,
			long totalCorrected,
			long totalFailed,
			long processMaskResets,
			long lastSweepEpochMillis) {

		static final Stats NONE = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}

	private static final int MAX_FAILURE_LOGS = 5;
	private static final int FIRST_RETRY_SWEEPS = 8;
	private static final int MAX_RETRY_SWEEPS = 64;

	private final DhAffinity core;
	private final Logger log;
	private final long startNanos = System.nanoTime();
	private final Object wake = new Object();
	/** TID → sweep number at which it may be retried. Sweeper thread only. */
	private final Map<Long, Long> retryAtSweep = new HashMap<>();
	private final Map<Long, Integer> failStreak = new HashMap<>();
	private volatile Stats stats = Stats.NONE;
	private volatile boolean stopped;
	/** Set when the config changes: threads that failed with the old mask must be offered the new one. */
	private volatile boolean resetBackoff;
	private boolean poked;
	private boolean warnedProcessMask;
	private boolean warnedNoThreads;
	private int failureLogs;
	private int sweepErrorLogs;
	private long totalCorrected;
	private long totalFailed;
	private long processMaskResets;
	private long maxDurationMicros;
	private Thread thread;

	AffinitySweeper(DhAffinity core, Logger log) {
		this.core = core;
		this.log = log;
	}

	/** Start the daemon thread. */
	synchronized void start() {
		if (thread != null) {
			return;
		}
		thread = new Thread(this, "DHAffinity-Sweeper");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	void stop() {
		stopped = true;
		poke();
	}

	/** Ask for a sweep as soon as possible instead of waiting for the next interval. */
	public void poke() {
		synchronized (wake) {
			poked = true;
			wake.notifyAll();
		}
	}

	/** The configuration changed: forget failure backoffs and sweep as soon as possible. */
	public void configChanged() {
		resetBackoff = true;
		poke();
	}

	public Stats stats() {
		return stats;
	}

	public boolean isAlive() {
		Thread t = thread;
		return t != null && t.isAlive();
	}

	@Override
	public void run() {
		try {
			AffinityConfig cfg = core.config();
			if (cfg.gameMask != 0) {
				// The mod's own thread always belongs with the game threads, whatever else is managed.
				core.backend().setCurrentThreadAffinity(cfg.gameMask);
			}
		} catch (Throwable t) {
			log.error("DH Affinity: could not pin the sweeper thread itself; continuing", t);
		}
		while (!stopped) {
			try {
				sweepOnce();
			} catch (Throwable t) {
				if (sweepErrorLogs++ < 3) {
					log.error("DH Affinity: sweep failed", t);
				}
			}
			long interval = currentIntervalMs();
			synchronized (wake) {
				if (!poked && !stopped) {
					try {
						wake.wait(interval);
					} catch (InterruptedException e) {
						return;
					}
				}
				poked = false;
			}
		}
	}

	long currentIntervalMs() {
		AffinityConfig cfg = core.config();
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
		return elapsedMs < cfg.startupWindowMs ? cfg.startupSweepMs : cfg.steadySweepMs;
	}

	/** One reconciliation pass. Package-visible so tests can drive it without the thread. */
	void sweepOnce() {
		AffinityConfig cfg = core.config();
		if (!cfg.enabled) {
			return;
		}
		AffinityBackend backend = core.backend();
		ThreadRegistry registry = core.registry();
		long t0 = System.nanoTime();
		Stats previous = stats;
		long sweepNo = previous.sweeps() + 1;
		if (resetBackoff) {
			resetBackoff = false;
			retryAtSweep.clear();
			failStreak.clear();
		}

		reconcileProcessMask(cfg, backend);

		long[] tids;
		Map<Long, ThreadRegistry.DhThread> registryOnly = null;
		if (cfg.manageNonDhThreads) {
			tids = backend.enumerateThreadIds();
			if (tids.length == 0) {
				if (!warnedNoThreads) {
					warnedNoThreads = true;
					log.warn("DH Affinity: thread enumeration returned nothing (backend {}, error {}); only DH workers can be managed until it works again.",
							backend.name(), backend.lastError());
				}
				// Keep at least the DH workers right; the enumeration is retried next sweep.
				List<ThreadRegistry.DhThread> snapshot = registry.snapshot();
				registryOnly = new HashMap<>(snapshot.size() * 2);
				tids = new long[snapshot.size()];
				for (int i = 0; i < tids.length; i++) {
					tids[i] = snapshot.get(i).tid();
					registryOnly.put(tids[i], snapshot.get(i));
				}
			}
		} else {
			// Only DH workers are managed: no need for a (system-wide, on Windows) thread snapshot.
			// Work from one consistent snapshot so a worker that exits meanwhile is never reclassified.
			List<ThreadRegistry.DhThread> snapshot = registry.snapshot();
			registryOnly = new HashMap<>(snapshot.size() * 2);
			tids = new long[snapshot.size()];
			for (int i = 0; i < tids.length; i++) {
				tids[i] = snapshot.get(i).tid();
				registryOnly.put(tids[i], snapshot.get(i));
			}
		}

		int corrected = 0;
		int unchanged = 0;
		int skipped = 0;
		int gone = 0;
		int failed = 0;
		int dhSeen = 0;
		long mainTid = core.mainThreadTid();
		for (long tid : tids) {
			ThreadRegistry.DhThread dh = registryOnly != null ? registryOnly.get(tid) : registry.get(tid);
			if (dh == null && registryOnly != null) {
				skipped++;
				continue; // cannot happen (snapshot-derived), kept as a guard
			}
			long desired;
			if (dh != null) {
				dhSeen++;
				desired = cfg.maskForDhPool(dh.pool());
			} else if (tid == mainTid && mainTid != 0) {
				desired = cfg.mainThreadMask;
			} else {
				desired = cfg.gameMask;
			}
			if (desired == 0) {
				skipped++;
				continue;
			}
			Long retryAt = retryAtSweep.get(tid);
			if (retryAt != null && sweepNo < retryAt) {
				skipped++;
				continue;
			}
			AffinityBackend.Result result = backend.reconcile(tid, desired);
			if (result == AffinityBackend.Result.CHANGED && dh == null) {
				// A DH worker may have registered between our registry read and the write; re-check.
				ThreadRegistry.DhThread late = registry.get(tid);
				if (late != null) {
					long lateMask = cfg.maskForDhPool(late.pool());
					if (lateMask != 0) {
						desired = lateMask;
						result = backend.reconcile(tid, lateMask);
					}
				}
			}
			switch (result) {
				case UNCHANGED -> {
					unchanged++;
					clearFailure(tid);
				}
				case CHANGED -> {
					corrected++;
					clearFailure(tid);
					if (cfg.logPins) {
						log.info("DH Affinity: pinned thread {} -> {} (CPUs {}) [{}]", tid, MaskFormat.toHex(desired),
								MaskFormat.toCpuList(desired), dh != null ? "DH " + dh.pool() : tid == mainTid ? "main thread" : "game");
					}
				}
				case GONE -> {
					gone++;
					clearFailure(tid);
					if (dh != null && registryOnly != null) {
						registry.unregisterIfSame(tid, dh);
					}
				}
				case FAILED -> {
					failed++;
					int streak = failStreak.merge(tid, 1, Integer::sum);
					long backoff = Math.min(MAX_RETRY_SWEEPS, (long) FIRST_RETRY_SWEEPS << Math.min(streak - 1, 3));
					retryAtSweep.put(tid, sweepNo + backoff);
					if (failureLogs < MAX_FAILURE_LOGS) {
						failureLogs++;
						log.warn("DH Affinity: could not set affinity of thread {} to {} (OS error {}); retrying in {} sweeps{}", tid,
								MaskFormat.toHex(desired), backend.lastError(), backoff,
								failureLogs == MAX_FAILURE_LOGS ? "; further failures will not be logged" : "");
					}
				}
			}
		}

		if (!retryAtSweep.isEmpty()) {
			Set<Long> alive = new HashSet<>(tids.length * 2);
			for (long tid : tids) {
				alive.add(tid);
			}
			retryAtSweep.keySet().retainAll(alive);
			failStreak.keySet().retainAll(alive);
		}

		long durationMicros = (System.nanoTime() - t0) / 1_000L;
		totalCorrected += corrected;
		totalFailed += failed;
		if (previous.sweeps() > 0) {
			maxDurationMicros = Math.max(maxDurationMicros, durationMicros);
		}
		stats = new Stats(sweepNo, tids.length, dhSeen, corrected, unchanged, skipped, gone, failed,
				durationMicros, maxDurationMicros, totalCorrected, totalFailed, processMaskResets, System.currentTimeMillis());
	}

	private void clearFailure(long tid) {
		if (!retryAtSweep.isEmpty()) {
			retryAtSweep.remove(tid);
			failStreak.remove(tid);
		}
	}

	private void reconcileProcessMask(AffinityConfig cfg, AffinityBackend backend) {
		if (!cfg.manageProcessAffinity || !backend.supportsProcessAffinity()) {
			return;
		}
		long all = backend.allCpusMask();
		long process = backend.getProcessAffinity();
		if (process == 0 || process == all) {
			return;
		}
		boolean ok = backend.setProcessAffinity(all);
		processMaskResets++;
		// Widening resets every thread to the process mask (Windows semantics); retry previously failed threads.
		retryAtSweep.clear();
		failStreak.clear();
		if (!warnedProcessMask) {
			warnedProcessMask = true;
			log.warn("DH Affinity: the process affinity mask was {} (CPUs {}) instead of all CPUs ({}); {}. "
							+ "Something outside the game (Process Lasso?) is restricting javaw. Remove that rule: "
							+ "while it is active DH threads cannot be moved to CPUs {}.",
					MaskFormat.toHex(process), MaskFormat.toCpuList(process), MaskFormat.toCpuList(all),
					ok ? "widened it" : "widening it FAILED (OS error " + backend.lastError() + ")",
					MaskFormat.toCpuList(cfg.dhMask));
		} else if (cfg.logPins) {
			log.info("DH Affinity: process affinity mask was reset to {} again; widened it ({}).",
					MaskFormat.toHex(process), ok ? "ok" : "failed");
		}
	}
}
