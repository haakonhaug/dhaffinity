package io.github.haakon.dhaffinity.affinity;

/**
 * Platform layer: everything the mod needs from the OS to read and write CPU affinity.
 *
 * <p>Masks are 64-bit bitmaps of logical CPUs (bit {@code n} = logical CPU {@code n}). A mask of
 * {@code 0} is never a valid affinity, so {@code 0} doubles as the "unknown / failed" value for
 * every query method.
 */
public interface AffinityBackend {

	/** Outcome of {@link #reconcile(long, long)}. */
	enum Result {
		/** The thread already had the desired mask; nothing was written. */
		UNCHANGED,
		/** The thread's mask was changed to the desired mask. */
		CHANGED,
		/** The thread no longer exists (it exited between enumeration and reconciliation). */
		GONE,
		/** The OS refused; see {@link #lastError()}. */
		FAILED
	}

	/** Human-readable backend name for logs and the status command. */
	String name();

	/** Number of logical CPUs the backend can address (at most 64). */
	int cpuCount();

	/** Mask with one bit set for every logical CPU in {@link #cpuCount()}. */
	long allCpusMask();

	/** Native (OS) ID of the calling thread, or {@code 0} if unsupported. */
	long currentThreadId();

	/** Native IDs of every thread in this process; empty if unsupported. */
	long[] enumerateThreadIds();

	/**
	 * Name of the given thread as the OS sees it (the JVM publishes Java thread names to the OS),
	 * or {@code null} if unknown or unsupported. Linux truncates names to 15 characters, so
	 * callers match by prefix.
	 */
	default String threadName(long tid) {
		return null;
	}

	/** Current mask of the given thread, or {@code 0} if unknown. */
	long getThreadAffinity(long tid);

	/** Ensure the given thread runs with exactly {@code desiredMask}; writes only when it differs. */
	Result reconcile(long tid, long desiredMask);

	/** Pin the calling thread; returns {@code false} on failure. */
	boolean setCurrentThreadAffinity(long mask);

	/** Whether the OS has a process-wide affinity mask that constrains all threads (Windows). */
	boolean supportsProcessAffinity();

	/** Process-wide mask, or {@code 0} if unsupported/unknown. */
	long getProcessAffinity();

	/** Set the process-wide mask; returns {@code false} if unsupported or refused. */
	boolean setProcessAffinity(long mask);

	/**
	 * Last OS error code (errno / GetLastError) observed by the <em>calling thread</em>'s most
	 * recent failed call on this backend, for diagnostics; {@code 0} if that thread has not seen
	 * a failure. Errors from other threads are never reported here.
	 */
	int lastError();
}
