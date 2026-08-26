package io.github.haakon.dhaffinity.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Helpers about Distant Horizons' thread pools. No compile-time dependency on DH classes. */
public final class DhPools {

	private DhPools() {}

	/** "DH-World Gen Thread[3]" → "World Gen"; names without the DH pattern are returned trimmed. */
	public static String poolName(String threadName) {
		String n = threadName == null ? "" : threadName;
		if (n.startsWith("DH-")) {
			n = n.substring(3);
		}
		int bracket = n.indexOf('[');
		if (bracket > 0) {
			n = n.substring(0, bracket);
		}
		if (n.endsWith(" Thread")) {
			n = n.substring(0, n.length() - " Thread".length());
		}
		return n.trim();
	}

	/**
	 * DH's configured "Number of Threads" (Advanced → Multi Threading), read reflectively so this
	 * class loads without DH. Returns -1 if unavailable.
	 */
	public static int configuredThreadCount() {
		try {
			Class<?> cls = Class.forName("com.seibel.distanthorizons.core.config.Config$Common$MultiThreading");
			Field field = cls.getField("numberOfThreads");
			Object entry = field.get(null);
			Method get = entry.getClass().getMethod("get");
			Object value = get.invoke(entry);
			return value instanceof Number number ? number.intValue() : -1;
		} catch (Throwable t) {
			return -1;
		}
	}
}
