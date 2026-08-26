package io.github.haakon.dhaffinity.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Native thread IDs of the live Distant Horizons worker threads, keyed by OS thread ID. */
public final class ThreadRegistry {

	/** A registered DH worker. */
	public record DhThread(long tid, String name, String pool, long registeredAtNanos) {}

	private final ConcurrentHashMap<Long, DhThread> threads = new ConcurrentHashMap<>();
	private final AtomicLong totalRegistered = new AtomicLong();

	public void register(long tid, String name, String pool) {
		threads.put(tid, new DhThread(tid, name, pool, System.nanoTime()));
		totalRegistered.incrementAndGet();
	}

	public void unregister(long tid) {
		threads.remove(tid);
	}

	/** Remove only if the entry is still the given one (a reused id may already belong to a new worker). */
	public void unregisterIfSame(long tid, DhThread expected) {
		threads.remove(tid, expected);
	}

	public boolean isDh(long tid) {
		return threads.containsKey(tid);
	}

	/** The registered worker for this OS thread ID, or {@code null}. */
	public DhThread get(long tid) {
		return threads.get(tid);
	}

	public int size() {
		return threads.size();
	}

	public long totalRegistered() {
		return totalRegistered.get();
	}

	public List<DhThread> snapshot() {
		return new ArrayList<>(threads.values());
	}
}
