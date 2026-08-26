package io.github.haakon.dhaffinity.core;

import java.util.concurrent.ThreadFactory;

/**
 * Delegates thread creation to Distant Horizons' own factory (so names, priorities and daemon
 * flags stay exactly as DH set them) but hands each new thread a wrapped Runnable that registers
 * and pins itself first. Installed on DH's pools right after they are created, before any
 * thread exists.
 */
public final class WrappingThreadFactory implements ThreadFactory {

	private final ThreadFactory delegate;

	private WrappingThreadFactory(ThreadFactory delegate) {
		this.delegate = delegate;
	}

	/** Wrap {@code factory} unless it is already wrapped (pools are re-created on DH config changes). */
	public static ThreadFactory wrap(ThreadFactory factory) {
		if (factory == null || factory instanceof WrappingThreadFactory) {
			return factory;
		}
		return new WrappingThreadFactory(factory);
	}

	public ThreadFactory delegate() {
		return delegate;
	}

	@Override
	public Thread newThread(Runnable runnable) {
		return delegate.newThread(DhAffinity.get().wrapDhWorker(runnable));
	}
}
