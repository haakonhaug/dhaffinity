package io.github.haakon.dhaffinity.affinity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real syscalls; only runs on Linux with at least two CPUs. */
class LinuxAffinityBackendTest {

	private static LinuxAffinityBackend backend() {
		assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("linux"), "Linux only");
		LinuxAffinityBackend b = new LinuxAffinityBackend();
		assumeTrue(b.cpuCount() >= 2, "needs 2+ CPUs");
		return b;
	}

	@Test
	void reportsCpusAndCurrentThreadId() {
		LinuxAffinityBackend b = backend();
		assertEquals(Long.bitCount(b.allCpusMask()), b.cpuCount());
		long tid = b.currentThreadId();
		assertNotEquals(0, tid);
		assertTrue(Arrays.stream(b.enumerateThreadIds()).anyMatch(t -> t == tid), "enumeration includes the current thread");
	}

	@Test
	void pinsCurrentThreadAndReadsItBack() throws Exception {
		LinuxAffinityBackend b = backend();
		long tid = b.currentThreadId();
		long original = b.getThreadAffinity(tid);
		assertNotEquals(0, original);
		try {
			assertTrue(b.setCurrentThreadAffinity(0b10));
			assertEquals("1", LinuxAffinityBackend.readCpusAllowedList(tid));
			assertEquals(0b10, b.getThreadAffinity(tid));
			assertEquals(AffinityBackend.Result.UNCHANGED, b.reconcile(tid, 0b10));
		} finally {
			b.setCurrentThreadAffinity(original);
		}
	}

	@Test
	void reconcilesAnotherThreadFromOutside() throws Exception {
		LinuxAffinityBackend b = backend();
		long upper = b.allCpusMask() & ~1L; // everything but CPU 0
		AtomicLong otherTid = new AtomicLong();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread other = new Thread(() -> {
			otherTid.set(b.currentThreadId());
			started.countDown();
			try {
				release.await();
			} catch (InterruptedException ignored) {
			}
		}, "affinity-test-other");
		other.start();
		started.await();
		try {
			long tid = otherTid.get();
			assertEquals(AffinityBackend.Result.CHANGED, b.reconcile(tid, 1L));
			assertEquals("0", LinuxAffinityBackend.readCpusAllowedList(tid));
			assertEquals(AffinityBackend.Result.UNCHANGED, b.reconcile(tid, 1L));
			assertEquals(AffinityBackend.Result.CHANGED, b.reconcile(tid, upper));
			assertEquals(MaskFormat.toCpuList(upper), LinuxAffinityBackend.readCpusAllowedList(tid));
		} finally {
			release.countDown();
			other.join();
		}
		// After the thread has exited its id is gone. Thread.join() returns when the Java thread is
		// terminated; the native thread can outlive it by a moment, so poll briefly.
		AffinityBackend.Result result = AffinityBackend.Result.CHANGED;
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (result != AffinityBackend.Result.GONE && System.nanoTime() < deadline) {
			result = b.reconcile(otherTid.get(), 1L);
			Thread.sleep(5);
		}
		assertEquals(AffinityBackend.Result.GONE, result);
		// A thread id that cannot exist is reported as gone, not as a failure.
		assertEquals(AffinityBackend.Result.GONE, b.reconcile(Integer.MAX_VALUE, 1L));
	}

	@Test
	void lastErrorIsPerThread() throws Exception {
		LinuxAffinityBackend b = backend();
		assertEquals(0, b.lastError(), "fresh backend has no error on this thread");
		AtomicInteger helperError = new AtomicInteger(-1);
		Thread helper = new Thread(() -> {
			// pid_max is at most 2^22 on Linux, so this id can never exist: ESRCH.
			b.reconcile(Integer.MAX_VALUE, 1L);
			helperError.set(b.lastError());
		}, "affinity-test-error");
		helper.start();
		helper.join();
		assertEquals(3, helperError.get(), "the failing thread sees its own errno (ESRCH)");
		assertEquals(0, b.lastError(), "another thread's failure must not leak into this thread");
	}
}
