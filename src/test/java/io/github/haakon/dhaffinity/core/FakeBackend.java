package io.github.haakon.dhaffinity.core;

import io.github.haakon.dhaffinity.affinity.AffinityBackend;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** In-memory OS model: threads with masks, an optional Windows-style process mask. */
final class FakeBackend implements AffinityBackend {

	final Map<Long, Long> threads = new LinkedHashMap<>();
	final Map<Long, String> names = new LinkedHashMap<>();
	final Set<Long> failing = new HashSet<>();
	long allMask = 0xFFFF_FFFFL; // 32 CPUs
	boolean windowsStyleProcessMask = true;
	long processMask = 0xFFFF_FFFFL;
	long currentTid = 1;
	boolean throwOnCurrentTid;
	boolean emptyEnumeration;
	int writes;
	int processWrites;
	int enumerations;
	int lastError;
	/** Invoked before every reconcile; lets tests interleave registry changes mid-sweep. */
	Runnable beforeReconcile;

	void addThread(long tid, long mask) {
		threads.put(tid, mask);
	}

	void addThread(long tid, long mask, String name) {
		threads.put(tid, mask);
		if (name != null) {
			names.put(tid, name);
		}
	}

	@Override
	public String threadName(long tid) {
		return names.get(tid);
	}

	@Override
	public String name() {
		return "fake";
	}

	@Override
	public int cpuCount() {
		return Long.bitCount(allMask);
	}

	@Override
	public long allCpusMask() {
		return allMask;
	}

	@Override
	public long currentThreadId() {
		if (throwOnCurrentTid) {
			throw new UnsatisfiedLinkError("simulated JNA failure");
		}
		return currentTid;
	}

	@Override
	public long[] enumerateThreadIds() {
		enumerations++;
		if (emptyEnumeration) {
			return new long[0];
		}
		return threads.keySet().stream().mapToLong(Long::longValue).toArray();
	}

	@Override
	public long getThreadAffinity(long tid) {
		return threads.getOrDefault(tid, 0L);
	}

	@Override
	public Result reconcile(long tid, long desiredMask) {
		if (beforeReconcile != null) {
			beforeReconcile.run();
		}
		Long current = threads.get(tid);
		if (current == null) {
			return Result.GONE;
		}
		if (current == desiredMask) {
			return Result.UNCHANGED;
		}
		if (failing.contains(tid) || (windowsStyleProcessMask && (desiredMask & ~processMask) != 0)) {
			lastError = 87;
			return Result.FAILED;
		}
		threads.put(tid, desiredMask);
		writes++;
		return Result.CHANGED;
	}

	@Override
	public boolean setCurrentThreadAffinity(long mask) {
		return reconcile(currentTid, mask) != Result.FAILED;
	}

	@Override
	public boolean supportsProcessAffinity() {
		return windowsStyleProcessMask;
	}

	@Override
	public long getProcessAffinity() {
		return windowsStyleProcessMask ? processMask : 0;
	}

	@Override
	public boolean setProcessAffinity(long mask) {
		if (!windowsStyleProcessMask) {
			return false;
		}
		processMask = mask;
		processWrites++;
		// Windows resets every thread of the process to the new process mask.
		threads.replaceAll((tid, old) -> mask);
		return true;
	}

	@Override
	public int lastError() {
		return lastError;
	}
}
