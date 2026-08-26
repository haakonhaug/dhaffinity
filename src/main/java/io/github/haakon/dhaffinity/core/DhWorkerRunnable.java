package io.github.haakon.dhaffinity.core;

import io.github.haakon.dhaffinity.affinity.AffinityBackend;
import io.github.haakon.dhaffinity.config.AffinityConfig;

/**
 * Wraps the Runnable of every thread created by DH's thread factory. The first thing the new
 * thread does is register its native ID (and pool) and, if the configuration says so, pin
 * itself; the sweeper keeps it on the right CPUs afterwards using the configuration current at
 * each sweep, so a reload applies to running workers too. Registration is undone when the
 * thread's work finishes, so a reused OS thread ID is never mistaken for a DH thread.
 *
 * <p>Everything before {@code original.run()} is guarded: the wrapped Runnable is a
 * ThreadPoolExecutor worker, and a throw before it starts would permanently cost DH a pool slot.
 */
final class DhWorkerRunnable implements Runnable {

	private final DhAffinity core;
	private final Runnable original;

	DhWorkerRunnable(DhAffinity core, Runnable original) {
		this.core = core;
		this.original = original;
	}

	@Override
	public void run() {
		long tid = 0;
		boolean registered = false;
		try {
			AffinityBackend backend = core.backend();
			tid = backend.currentThreadId();
			String name = Thread.currentThread().getName();
			if (tid == 0) {
				core.onWorkerWithoutTid(name);
			} else {
				String pool = DhPools.poolName(name);
				// Register before pinning so a concurrent sweep already treats this TID as DH.
				core.registry().register(tid, name, pool);
				registered = true;
				AffinityConfig cfg = core.config();
				long desired = cfg.enabled ? cfg.maskForDhPool(pool) : 0;
				if (desired != 0) {
					boolean ok = backend.setCurrentThreadAffinity(desired);
					core.onWorkerStarted(tid, name, pool, desired, ok);
				}
			}
		} catch (Throwable t) {
			core.onWrapperFailure(t);
		}
		try {
			original.run();
		} finally {
			if (registered) {
				core.registry().unregister(tid);
			}
		}
	}

	/** For diagnostics/tests. */
	Runnable original() {
		return original;
	}
}
