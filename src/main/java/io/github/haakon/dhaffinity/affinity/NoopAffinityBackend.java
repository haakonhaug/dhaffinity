package io.github.haakon.dhaffinity.affinity;

/** Backend for platforms without support: reports the CPU count and does nothing else. */
public final class NoopAffinityBackend implements AffinityBackend {

	private static final long[] EMPTY = new long[0];

	private final String name;
	private final int cpuCount;

	public NoopAffinityBackend(String reason) {
		this.name = "unsupported (" + reason + ")";
		this.cpuCount = Math.min(64, Math.max(1, Runtime.getRuntime().availableProcessors()));
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public int cpuCount() {
		return cpuCount;
	}

	@Override
	public long allCpusMask() {
		return MaskFormat.lowBits(cpuCount);
	}

	@Override
	public long currentThreadId() {
		return 0;
	}

	@Override
	public long[] enumerateThreadIds() {
		return EMPTY;
	}

	@Override
	public long getThreadAffinity(long tid) {
		return 0;
	}

	@Override
	public Result reconcile(long tid, long desiredMask) {
		return Result.FAILED;
	}

	@Override
	public boolean setCurrentThreadAffinity(long mask) {
		return false;
	}

	@Override
	public boolean supportsProcessAffinity() {
		return false;
	}

	@Override
	public long getProcessAffinity() {
		return 0;
	}

	@Override
	public boolean setProcessAffinity(long mask) {
		return false;
	}

	@Override
	public int lastError() {
		return 0;
	}
}
