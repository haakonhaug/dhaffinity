package io.github.haakon.dhaffinity.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrappingThreadFactoryTest {

	@Test
	void wrapsOnceAndKeepsDelegateNaming() {
		ThreadFactory dhLike = r -> new Thread(r, "DH-World Gen Thread[0]");
		ThreadFactory wrapped = WrappingThreadFactory.wrap(dhLike);
		assertInstanceOf(WrappingThreadFactory.class, wrapped);
		assertSame(wrapped, WrappingThreadFactory.wrap(wrapped), "no double wrapping");
		assertNull(WrappingThreadFactory.wrap(null));
		assertSame(dhLike, ((WrappingThreadFactory) wrapped).delegate());
		Thread t = wrapped.newThread(() -> {});
		assertEquals("DH-World Gen Thread[0]", t.getName());
	}

	@Test
	void factorySwapAfterPoolCreationWrapsEveryWorker() throws Exception {
		// Mirrors the mixin: the pool exists, has no threads yet, and gets its factory swapped.
		ThreadFactory dhLike = r -> new Thread(r, "DH-IO Thread[0]");
		ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), dhLike);
		pool.setThreadFactory(WrappingThreadFactory.wrap(pool.getThreadFactory()));

		AtomicReference<String> runnableClass = new AtomicReference<>();
		pool.submit(() -> runnableClass.set(Thread.currentThread().getName())).get(5, TimeUnit.SECONDS);
		pool.shutdown();
		assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
		assertEquals("DH-IO Thread[0]", runnableClass.get());
		// The inert default DhAffinity instance (Noop backend) returns the original Runnable, so the
		// worker ran unwrapped here; the wrapping decision itself is covered by DhWorkerRunnableTest.
	}
}
