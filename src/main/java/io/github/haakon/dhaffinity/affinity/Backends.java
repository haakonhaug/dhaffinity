package io.github.haakon.dhaffinity.affinity;

import org.slf4j.Logger;

import java.util.Locale;

/** Chooses the {@link AffinityBackend} for the running OS. */
public final class Backends {

	private Backends() {}

	public static AffinityBackend create(Logger log) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			try {
				return new WindowsAffinityBackend();
			} catch (Throwable t) {
				log.error("DH Affinity: Windows backend failed to initialise (is JNA on the classpath?)", t);
				return new NoopAffinityBackend("Windows backend init failed: " + t);
			}
		}
		if (os.contains("linux")) {
			try {
				return new LinuxAffinityBackend();
			} catch (Throwable t) {
				log.error("DH Affinity: Linux backend failed to initialise (is JNA on the classpath?)", t);
				return new NoopAffinityBackend("Linux backend init failed: " + t);
			}
		}
		log.warn("DH Affinity: no affinity support for OS '{}'; the mod will stay inactive.", os);
		return new NoopAffinityBackend(os.isEmpty() ? "unknown OS" : os);
	}
}
