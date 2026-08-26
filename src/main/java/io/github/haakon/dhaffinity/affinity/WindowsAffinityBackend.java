package io.github.haakon.dhaffinity.affinity;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTRByReference;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Windows implementation on top of the JNA that Minecraft already ships.
 *
 * <p>Thread enumeration uses a Toolhelp32 thread snapshot (system-wide by design of the API,
 * filtered to our PID). THREADENTRY32 is read through a raw buffer rather than a JNA
 * {@code Structure} to keep the per-entry cost minimal, because the snapshot contains every
 * thread on the machine.
 *
 * <p>A thread id taken from the snapshot may be reissued to a thread of another process before
 * we open it, so every opened handle is verified with {@code GetProcessIdOfThread} before use.
 *
 * <p>Only processor group 0 is handled (masks are 64-bit). That covers every consumer CPU.
 */
public final class WindowsAffinityBackend implements AffinityBackend {

	/** kernel32 functions that jna-platform 5.17's {@link Kernel32} does not declare. */
	private interface Kernel32Ext extends StdCallLibrary {
		Kernel32Ext INSTANCE = Native.load("kernel32", Kernel32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

		/** Returns the previous mask, or 0 on failure. */
		ULONG_PTR SetThreadAffinityMask(HANDLE hThread, ULONG_PTR dwThreadAffinityMask);

		boolean GetThreadGroupAffinity(HANDLE hThread, WinNT.GROUP_AFFINITY groupAffinity);

		/** Owning process id of an open thread handle, or 0 on failure (Vista+). */
		int GetProcessIdOfThread(HANDLE hThread);

		/** Raw-buffer variants of Thread32First/Next; {@code entry} points at a THREADENTRY32. */
		boolean Thread32First(HANDLE hSnapshot, Pointer entry);

		boolean Thread32Next(HANDLE hSnapshot, Pointer entry);
	}

	private static final long[] EMPTY = new long[0];
	private static final int THREAD_ACCESS = WinNT.THREAD_QUERY_INFORMATION | WinNT.THREAD_SET_INFORMATION;
	/** sizeof(THREADENTRY32): dwSize, cntUsage, th32ThreadID, th32OwnerProcessID, tpBasePri, tpDeltaPri, dwFlags. */
	private static final int THREADENTRY32_SIZE = 28;
	private static final int OFFSET_THREAD_ID = 8;
	private static final int OFFSET_OWNER_PID = 12;
	private static final int ERROR_INVALID_PARAMETER = 87;

	private final Kernel32 k32;
	private final Kernel32Ext ext;
	private final int pid;
	private final long allCpusMask;
	private final int cpuCount;
	/** Per-thread so a failure on the sweeper does not show up as the status command's error. */
	private final ThreadLocal<Integer> lastError = ThreadLocal.withInitial(() -> 0);

	public WindowsAffinityBackend() {
		this.k32 = Kernel32.INSTANCE;
		this.ext = Kernel32Ext.INSTANCE;
		this.pid = k32.GetCurrentProcessId();

		long systemMask = 0;
		long processMask = 0;
		ULONG_PTRByReference process = new ULONG_PTRByReference();
		ULONG_PTRByReference system = new ULONG_PTRByReference();
		if (k32.GetProcessAffinityMask(k32.GetCurrentProcess(), process, system)) {
			systemMask = system.getValue().longValue();
			processMask = process.getValue().longValue();
		}
		if (systemMask == 0) {
			WinBase.SYSTEM_INFO info = new WinBase.SYSTEM_INFO();
			k32.GetSystemInfo(info);
			systemMask = info.dwActiveProcessorMask.longValue();
			if (systemMask == 0) {
				systemMask = MaskFormat.lowBits(info.dwNumberOfProcessors.intValue());
			}
		}
		if (systemMask == 0) {
			systemMask = MaskFormat.lowBits(Runtime.getRuntime().availableProcessors());
		}
		this.allCpusMask = systemMask;
		this.cpuCount = Long.bitCount(systemMask);

		probeExtSymbols(processMask == 0 ? systemMask : processMask);
	}

	/**
	 * Touch the {@link Kernel32Ext} symbols that are newer than the Toolhelp ones (Vista/Win7+)
	 * once, so a missing export surfaces here as an {@link UnsatisfiedLinkError} (JNA binds
	 * lazily). {@code Backends.create} turns that into a {@link NoopAffinityBackend} instead of
	 * failing later on the sweeper.
	 *
	 * <p>The write is a no-op: it re-applies the mask the current thread already has (or the
	 * process mask if that cannot be read). A thread outside processor group 0 is left alone,
	 * because a group-0 bitmap would land on different CPUs there. Return values are deliberately
	 * ignored; only symbol resolution matters here.
	 */
	private void probeExtSymbols(long fallbackMask) {
		HANDLE self = k32.GetCurrentThread(); // pseudo-handle, never closed
		WinNT.GROUP_AFFINITY affinity = new WinNT.GROUP_AFFINITY();
		boolean read = ext.GetThreadGroupAffinity(self, affinity);
		long current = read && affinity.mask != null ? affinity.mask.longValue() : 0;
		if (!read || (affinity.group == 0 && current != 0)) {
			ext.SetThreadAffinityMask(self, new ULONG_PTR(current != 0 ? current : fallbackMask));
		}
		ext.GetProcessIdOfThread(self);
	}

	@Override
	public String name() {
		return "Windows (kernel32 via JNA)";
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
		return Integer.toUnsignedLong(k32.GetCurrentThreadId());
	}

	@Override
	public long[] enumerateThreadIds() {
		HANDLE snapshot = k32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new DWORD(0));
		if (snapshot == null || WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
			lastError.set(k32.GetLastError());
			return EMPTY;
		}
		try {
			Memory entry = new Memory(THREADENTRY32_SIZE);
			entry.clear();
			entry.setInt(0, THREADENTRY32_SIZE);
			long[] out = new long[256];
			int n = 0;
			if (!ext.Thread32First(snapshot, entry)) {
				lastError.set(k32.GetLastError());
				return EMPTY;
			}
			do {
				if (entry.getInt(OFFSET_OWNER_PID) == pid) {
					if (n == out.length) {
						out = java.util.Arrays.copyOf(out, n * 2);
					}
					out[n++] = Integer.toUnsignedLong(entry.getInt(OFFSET_THREAD_ID));
				}
			} while (ext.Thread32Next(snapshot, entry));
			return java.util.Arrays.copyOf(out, n);
		} finally {
			k32.CloseHandle(snapshot);
		}
	}

	@Override
	public long getThreadAffinity(long tid) {
		HANDLE handle = k32.OpenThread(THREAD_ACCESS, false, (int) tid);
		if (handle == null) {
			lastError.set(k32.GetLastError());
			return 0;
		}
		try {
			if (!ownedByUs(handle)) {
				return 0;
			}
			return queryMask(handle);
		} finally {
			k32.CloseHandle(handle);
		}
	}

	@Override
	public Result reconcile(long tid, long desiredMask) {
		HANDLE handle = k32.OpenThread(THREAD_ACCESS, false, (int) tid);
		if (handle == null) {
			int err = k32.GetLastError();
			lastError.set(err);
			// 87: no such thread. 5: the id now belongs to another process (a thread of our own
			// process is not access-denied to us). Either way the thread we enumerated is gone.
			// 87 = the id no longer exists. 5 (access denied) cannot come from one of our own live threads
			// unless a driver interferes; report it as FAILED so it is retried with backoff and logged.
			return err == ERROR_INVALID_PARAMETER ? Result.GONE : Result.FAILED;
		}
		try {
			if (!ownedByUs(handle)) {
				return Result.GONE;
			}
			if (queryMask(handle) == desiredMask) {
				return Result.UNCHANGED;
			}
			long previous = ext.SetThreadAffinityMask(handle, new ULONG_PTR(desiredMask)).longValue();
			if (previous == 0) {
				lastError.set(k32.GetLastError());
				return Result.FAILED;
			}
			return Result.CHANGED;
		} finally {
			k32.CloseHandle(handle);
		}
	}

	/**
	 * Whether an open thread handle still belongs to this process. A Toolhelp thread id can be
	 * reissued to another process between the snapshot and {@code OpenThread}; such a thread (and
	 * one whose owner cannot be queried at all) is treated as gone.
	 */
	private boolean ownedByUs(HANDLE handle) {
		int owner = ext.GetProcessIdOfThread(handle);
		if (owner == 0) {
			lastError.set(k32.GetLastError());
			return false;
		}
		return owner == pid;
	}

	/** Current mask of an open thread handle, or 0 if it cannot be read (or is not in group 0). */
	private long queryMask(HANDLE handle) {
		WinNT.GROUP_AFFINITY affinity = new WinNT.GROUP_AFFINITY();
		if (!ext.GetThreadGroupAffinity(handle, affinity)) {
			lastError.set(k32.GetLastError());
			return 0;
		}
		if (affinity.group != 0 || affinity.mask == null) {
			return 0;
		}
		return affinity.mask.longValue();
	}

	@Override
	public boolean setCurrentThreadAffinity(long mask) {
		long previous = ext.SetThreadAffinityMask(k32.GetCurrentThread(), new ULONG_PTR(mask)).longValue();
		if (previous == 0) {
			lastError.set(k32.GetLastError());
			return false;
		}
		return true;
	}

	@Override
	public boolean supportsProcessAffinity() {
		return true;
	}

	@Override
	public long getProcessAffinity() {
		ULONG_PTRByReference process = new ULONG_PTRByReference();
		ULONG_PTRByReference system = new ULONG_PTRByReference();
		if (!k32.GetProcessAffinityMask(k32.GetCurrentProcess(), process, system)) {
			lastError.set(k32.GetLastError());
			return 0;
		}
		return process.getValue().longValue();
	}

	@Override
	public boolean setProcessAffinity(long mask) {
		if (!k32.SetProcessAffinityMask(k32.GetCurrentProcess(), new ULONG_PTR(mask))) {
			lastError.set(k32.GetLastError());
			return false;
		}
		return true;
	}

	@Override
	public int lastError() {
		return lastError.get();
	}
}
