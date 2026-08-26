package io.github.haakon.dhaffinity.affinity;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Linux implementation using {@code sched_getaffinity}/{@code sched_setaffinity} from libc.
 *
 * <p>Linux has no process-wide mask; affinity is per thread and inherited at {@code clone()}.
 * Threads are enumerated from {@code /proc/self/task}.
 */
public final class LinuxAffinityBackend implements AffinityBackend {

	private interface LibC extends Library {
		LibC INSTANCE = Native.load("c", LibC.class);

		int sched_setaffinity(int pid, NativeLong cpusetsize, Pointer mask);

		int sched_getaffinity(int pid, NativeLong cpusetsize, Pointer mask);
	}

	/** glibc extension, present since 2.30; resolved lazily so older libc still works. */
	private interface LibCGetTid extends Library {
		int gettid();
	}

	private static final long[] EMPTY = new long[0];
	/** sizeof(cpu_set_t) in glibc: 1024 CPUs. */
	private static final int CPU_SET_SIZE = 128;
	private static final int ESRCH = 3;
	private static final Path TASK_DIR = Paths.get("/proc/self/task");
	private static final Path ONLINE_CPUS = Paths.get("/sys/devices/system/cpu/online");
	/** Effective cpuset of the root cgroup: v2 first, then the v1 controller mount. */
	private static final Path[] EFFECTIVE_CPUSET = {
		Paths.get("/sys/fs/cgroup/cpuset.cpus.effective"),
		Paths.get("/sys/fs/cgroup/cpuset/cpuset.effective_cpus")
	};

	private final LibC libc;
	private final LibCGetTid getTid;
	private final long allCpusMask;
	private final int cpuCount;
	/** Per-thread so a failure on the sweeper does not show up as the status command's error. */
	private final ThreadLocal<Integer> lastError = ThreadLocal.withInitial(() -> 0);

	public LinuxAffinityBackend() {
		this.libc = LibC.INSTANCE;
		LibCGetTid tidLib = null;
		try {
			tidLib = Native.load("c", LibCGetTid.class);
			tidLib.gettid(); // probe: throws UnsatisfiedLinkError if the symbol is missing
		} catch (Throwable t) {
			tidLib = null;
		}
		this.getTid = tidLib;

		long mask = readCpuListMask(ONLINE_CPUS);
		if (mask == 0) {
			mask = MaskFormat.lowBits(Runtime.getRuntime().availableProcessors());
		}
		// A cpuset (container / systemd slice) may forbid some online CPUs; the kernel would
		// silently drop them from any mask we write, so do not offer them in the first place.
		long cpuset = readEffectiveCpuset();
		if (cpuset != 0 && (mask & cpuset) != 0) {
			mask &= cpuset;
		}
		// The constructing (main) thread's own mask already reflects any deeper cpuset (systemd
		// slice) or a taskset launch; honour it so the sweeper never asks for CPUs the kernel drops.
		long inherited = readAffinity(0);
		if (inherited != 0 && (mask & inherited) != 0) {
			mask &= inherited;
		}
		this.allCpusMask = mask;
		this.cpuCount = Long.bitCount(mask);
	}

	/** Raw sched_getaffinity for a task (0 = calling thread); 0 on failure. */
	private long readAffinity(int tid) {
		Memory set = new Memory(CPU_SET_SIZE);
		set.clear();
		if (libc.sched_getaffinity(tid, new NativeLong(CPU_SET_SIZE), set) < 0) {
			return 0;
		}
		return set.getLong(0);
	}

	/** First readable, non-empty cpuset file as a mask; 0 when there is none (no cgroup cpuset). */
	private static long readEffectiveCpuset() {
		for (Path path : EFFECTIVE_CPUSET) {
			long mask = readCpuListMask(path);
			if (mask != 0) {
				return mask;
			}
		}
		return 0;
	}

	/**
	 * Parses a sysfs/cgroup CPU list such as {@code 0-31} or {@code 0-3,8-11} into a mask; CPUs
	 * above 63 are ignored. Returns 0 if the file is missing, empty or malformed.
	 */
	private static long readCpuListMask(Path file) {
		try {
			String list = Files.readString(file, StandardCharsets.US_ASCII).trim();
			long mask = 0;
			for (String part : list.split(",")) {
				part = part.trim();
				if (part.isEmpty()) {
					continue;
				}
				int dash = part.indexOf('-');
				int from = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
				int to = Integer.parseInt(dash < 0 ? part : part.substring(dash + 1));
				for (int cpu = from; cpu <= to && cpu < 64; cpu++) {
					mask |= 1L << cpu;
				}
			}
			return mask;
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	@Override
	public String name() {
		return "Linux (libc sched_setaffinity via JNA)";
	}

	@Override
	public int cpuCount() {
		return cpuCount;
	}

	@Override
	public long allCpusMask() {
		return allCpusMask;
	}

	@Override
	public long currentThreadId() {
		if (getTid != null) {
			return Integer.toUnsignedLong(getTid.gettid());
		}
		try {
			// /proc/thread-self -> "<pid>/task/<tid>"
			String link = Files.readSymbolicLink(Paths.get("/proc/thread-self")).toString();
			return Long.parseLong(link.substring(link.lastIndexOf('/') + 1));
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	@Override
	public long[] enumerateThreadIds() {
		String[] names = TASK_DIR.toFile().list();
		if (names == null) {
			return EMPTY;
		}
		long[] out = new long[names.length];
		int n = 0;
		for (String name : names) {
			try {
				long tid = Long.parseLong(name); // parse first: the index side effect must not outlive a throw
				out[n++] = tid;
			} catch (NumberFormatException ignored) {
				// not a task directory
			}
		}
		return n == out.length ? out : java.util.Arrays.copyOf(out, n);
	}

	@Override
	public String threadName(long tid) {
		try {
			String name = Files.readString(TASK_DIR.resolve(Long.toString(tid)).resolve("comm"), StandardCharsets.UTF_8).trim();
			return name.isEmpty() ? null : name;
		} catch (IOException | RuntimeException e) {
			return null; // the thread exited, or /proc is unavailable
		}
	}

	@Override
	public long getThreadAffinity(long tid) {
		Memory set = new Memory(CPU_SET_SIZE);
		set.clear();
		if (libc.sched_getaffinity((int) tid, new NativeLong(CPU_SET_SIZE), set) < 0) {
			lastError.set(Native.getLastError());
			return 0;
		}
		return set.getLong(0);
	}

	@Override
	public Result reconcile(long tid, long desiredMask) {
		Memory set = new Memory(CPU_SET_SIZE);
		set.clear();
		if (libc.sched_getaffinity((int) tid, new NativeLong(CPU_SET_SIZE), set) < 0) {
			int err = Native.getLastError();
			lastError.set(err);
			return err == ESRCH ? Result.GONE : Result.FAILED;
		}
		// The kernel reports the effective set (desired ∩ online), so compare within the online CPUs.
		if ((set.getLong(0) & allCpusMask) == (desiredMask & allCpusMask)) {
			return Result.UNCHANGED;
		}
		return apply((int) tid, desiredMask) ? Result.CHANGED : (lastError.get() == ESRCH ? Result.GONE : Result.FAILED);
	}

	@Override
	public boolean setCurrentThreadAffinity(long mask) {
		return apply(0, mask);
	}

	private boolean apply(int tid, long mask) {
		Memory set = new Memory(CPU_SET_SIZE);
		set.clear();
		set.setLong(0, mask);
		if (libc.sched_setaffinity(tid, new NativeLong(CPU_SET_SIZE), set) < 0) {
			lastError.set(Native.getLastError());
			return false;
		}
		return true;
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
		return lastError.get();
	}

	/** Exposed for tests: {@code Cpus_allowed_list} of a task as reported by procfs. */
	public static String readCpusAllowedList(long tid) throws IOException {
		File status = TASK_DIR.resolve(Long.toString(tid)).resolve("status").toFile();
		for (String line : Files.readAllLines(status.toPath(), StandardCharsets.US_ASCII)) {
			if (line.startsWith("Cpus_allowed_list:")) {
				return line.substring("Cpus_allowed_list:".length()).trim();
			}
		}
		throw new IOException("Cpus_allowed_list not found in " + status);
	}
}
