package io.github.haakon.dhaffinity.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.haakon.dhaffinity.core.TestConfigs.ALL;
import static io.github.haakon.dhaffinity.core.TestConfigs.DH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhWorkerRunnableTest {

	@Test
	void registersPinsAndUnregisters() throws Exception {
		FakeBackend os = new FakeBackend();
		os.currentTid = 42;
		os.addThread(42, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		AtomicBoolean wasRegisteredDuringRun = new AtomicBoolean();
		Runnable original = () -> wasRegisteredDuringRun.set(core.registry().isDh(42));
		Runnable wrapped = core.wrapDhWorker(original);
		assertNotSame(original, wrapped);
		assertInstanceOf(DhWorkerRunnable.class, wrapped);

		Thread t = new Thread(wrapped, "DH-World Gen Thread[0]");
		t.start();
		t.join();

		assertTrue(wasRegisteredDuringRun.get());
		assertFalse(core.registry().isDh(42), "unregistered after the worker finished");
		assertEquals(DH, os.threads.get(42L), "pinned itself to dhMask");
		assertEquals(1, core.wrappedWorkers());
	}

	@Test
	void registersThePoolNameAndUsesItsOverride() throws Exception {
		FakeBackend os = new FakeBackend();
		os.currentTid = 9;
		os.addThread(9, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.dhPoolMasks.put("IO", "8-11")));
		AtomicBoolean sawPool = new AtomicBoolean();
		Runnable wrapped = core.wrapDhWorker(() -> sawPool.set("IO".equals(core.registry().get(9).pool())));

		Thread io = new Thread(wrapped, "DH-IO Thread[0]");
		io.start();
		io.join();

		assertTrue(sawPool.get());
		assertEquals(0xF00L, os.threads.get(9L));
	}

	@Test
	void unregistersEvenWhenWorkerThrows() {
		FakeBackend os = new FakeBackend();
		os.currentTid = 7;
		os.addThread(7, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		Runnable wrapped = core.wrapDhWorker(() -> {
			throw new IllegalStateException("boom");
		});

		assertThrows(IllegalStateException.class, wrapped::run);
		assertFalse(core.registry().isDh(7));
	}

	@Test
	void disabledStillRegistersButDoesNotPin() throws Exception {
		FakeBackend os = new FakeBackend();
		os.currentTid = 5;
		os.addThread(5, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.enabled = false));
		AtomicBoolean registered = new AtomicBoolean();
		Runnable wrapped = core.wrapDhWorker(() -> registered.set(core.registry().isDh(5)));
		assertInstanceOf(DhWorkerRunnable.class, wrapped);

		Thread t = new Thread(wrapped, "DH-World Gen Thread[0]");
		t.start();
		t.join();

		assertTrue(registered.get(), "registered so a later enable can find it");
		assertEquals(ALL, os.threads.get(5L), "not pinned while disabled");
	}

	@Test
	void prologueFailureNeverPreventsTheWorkerFromRunning() {
		FakeBackend os = new FakeBackend();
		os.throwOnCurrentTid = true;
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		AtomicBoolean ran = new AtomicBoolean();
		Runnable wrapped = core.wrapDhWorker(() -> ran.set(true));

		wrapped.run();

		assertTrue(ran.get());
		assertEquals(1, core.wrapperFailures());
		assertEquals(0, core.registry().size());
	}

	@Test
	void nullAndUnsupportedReturnOriginal() {
		FakeBackend os = new FakeBackend();
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		assertSame(null, core.wrapDhWorker(null));
		DhAffinity noop = DhAffinity.createDetached(new io.github.haakon.dhaffinity.affinity.NoopAffinityBackend("test"), TestConfigs.split());
		Runnable original = () -> {};
		assertSame(original, noop.wrapDhWorker(original));
	}
}
