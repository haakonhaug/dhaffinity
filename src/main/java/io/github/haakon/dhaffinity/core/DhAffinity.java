package io.github.haakon.dhaffinity.core;

import io.github.haakon.dhaffinity.affinity.AffinityBackend;
import io.github.haakon.dhaffinity.affinity.Backends;
import io.github.haakon.dhaffinity.affinity.CpuTopology;
import io.github.haakon.dhaffinity.affinity.MaskFormat;
import io.github.haakon.dhaffinity.affinity.NoopAffinityBackend;
import io.github.haakon.dhaffinity.config.AffinityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Mod core: owns the backend, the config, the registry and the sweeper. Has no Minecraft
 * dependencies so it can be exercised by plain unit tests.
 */
public final class DhAffinity {

	public static final String MOD_ID = "dhaffinity";
	public static final Logger LOG = LoggerFactory.getLogger("DHAffinity");
	/** JVM property Minecraft reads (once, lazily) to size its background worker pool. */
	public static final String MAX_BG_THREADS_PROPERTY = "max.bg.threads";

	/** What happened to the Distant Horizons mixin. */
	public enum HookState {
		/** The mixin has not been applied (yet). */
		NOT_APPLIED,
		/** A DH class we hook was not found on the classpath. */
		TARGET_CLASS_MISSING,
		/** The class exists but the hooked method does not; DH changed its internals. */
		TARGET_METHOD_MISSING,
		/** The pool-creation hook is in place; workers get wrapped as DH creates its pools. */
		APPLIED
	}

	private static volatile DhAffinity instance = new DhAffinity(new NoopAffinityBackend("not initialised"), AffinityConfig.disabled(), null, CpuTopology.of(List.of()));
	/** Static: the mixin plugin reports before {@link #initialize(Path)} replaces the instance. */
	private static volatile HookState hookState = HookState.NOT_APPLIED;

	private final AffinityBackend backend;
	private final CpuTopology topology;
	private final Path configFile;
	private final ThreadRegistry registry = new ThreadRegistry();
	private final AtomicLong wrappedWorkers = new AtomicLong();
	private final AtomicLong workersWithoutTid = new AtomicLong();
	private final AtomicLong selfPinFailures = new AtomicLong();
	private final AtomicLong wrapperFailures = new AtomicLong();
	private final List<Supplier<List<String>>> statusProviders = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile AffinityConfig config;
	private volatile AffinitySweeper sweeper;
	private volatile long mainThreadTid;
	private volatile boolean localWorld = true;
	private volatile boolean started;
	private volatile String vanillaWorkerCap = "";
	private final Object logLock = new Object();
	private int selfPinFailureLogs;
	private int wrapperFailureLogs;

	private DhAffinity(AffinityBackend backend, AffinityConfig config, Path configFile, CpuTopology topology) {
		this.backend = backend;
		this.config = config;
		this.configFile = configFile;
		this.topology = topology;
	}

	/** The live instance (inert until {@link #initialize(Path)} has run). */
	public static DhAffinity get() {
		return instance;
	}

	/**
	 * Called once from the preLaunch entrypoint, on the main thread (which later becomes the
	 * render thread — its native ID is captured here).
	 */
	public static synchronized DhAffinity initialize(Path configDir) {
		if (instance.started) {
			return instance;
		}
		AffinityBackend backend = Backends.create(LOG);
		CpuTopology topology = CpuTopology.detect();
		Path file = configDir.resolve(AffinityConfig.FILE_NAME);
		AffinityConfig.Json json = AffinityConfig.readOrCreate(file, LOG, () -> AffinityConfig.defaultsFor(backend.allCpusMask(), topology));
		AffinityConfig cfg = AffinityConfig.resolve(json, backend.allCpusMask(), LOG);
		DhAffinity core = new DhAffinity(backend, cfg, file, topology);
		core.mainThreadTid = backend.currentThreadId();
		instance = core;
		core.start();
		return core;
	}

	/** Build an instance around a custom backend/config without touching the singleton (tests). */
	static DhAffinity createDetached(AffinityBackend backend, AffinityConfig config) {
		return new DhAffinity(backend, config, null, CpuTopology.of(List.of()));
	}

	private synchronized void start() {
		started = true;
		AffinityConfig cfg = config;
		LOG.info("DH Affinity: backend {} — {} logical CPUs ({}); {}.", backend.name(), backend.cpuCount(),
				MaskFormat.toCpuList(backend.allCpusMask()), topology.describe());
		if (!cfg.enabled) {
			LOG.info("DH Affinity: disabled in config; doing nothing.");
			return;
		}
		if (backend instanceof NoopAffinityBackend) {
			LOG.warn("DH Affinity: enabled, but there is no affinity support on this platform ({}); doing nothing.", backend.name());
			return;
		}
		LOG.info("DH Affinity: DH workers -> CPUs {}{}; main thread -> CPUs {}; {} -> CPUs {}.",
				MaskFormat.toCpuList(cfg.dhMask), describePoolOverrides(cfg), MaskFormat.toCpuList(cfg.mainThreadMask),
				cfg.manageNonDhThreads ? "everything else" : "other threads NOT managed; the sweeper thread itself",
				MaskFormat.toCpuList(cfg.gameMask));
		if (!cfg.dhPinningActive()) {
			LOG.warn("DH Affinity: no DH group selects a usable CPU on this machine, so DH threads will not be pinned. "
					+ "Open the DH Affinity menu (ModMenu → Configure, or /dhaffinity gui) to choose cores.");
		} else if (cfg.dhMask == cfg.gameMask && cfg.dhPoolMasks.isEmpty()) {
			LOG.info("DH Affinity: DH and game groups use the same CPUs ({}); nothing is separated until you choose different cores in the menu.",
					MaskFormat.toCpuList(cfg.dhMask));
		}
		capVanillaWorkers(cfg);
		widenProcessAffinity(cfg);
		AffinitySweeper s = new AffinitySweeper(this, LOG);
		sweeper = s;
		s.start();
	}

	private static String describePoolOverrides(AffinityConfig cfg) {
		if (cfg.dhPoolMasks.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(" (");
		cfg.dhPoolMasks.forEach((pool, mask) -> sb.append(pool).append(": ").append(MaskFormat.toCpuList(mask)).append(", "));
		sb.setLength(sb.length() - 2);
		return sb.append(')').toString();
	}

	/**
	 * Widening the process mask makes {@code Runtime.availableProcessors()} report every CPU, so
	 * Minecraft would size its "Worker-Main" pool for all of them while the sweeper confines the
	 * workers to the game CPUs. Keep the pool sized for the CPUs it actually gets, unless the
	 * user set the property themselves. Only effective before Minecraft's {@code Util} class
	 * initialises, i.e. at preLaunch.
	 */
	private void capVanillaWorkers(AffinityConfig cfg) {
		if (!cfg.capVanillaWorkerThreads || !cfg.manageNonDhThreads || cfg.gameMask == 0) {
			return;
		}
		String existing = System.getProperty(MAX_BG_THREADS_PROPERTY);
		if (existing != null) {
			vanillaWorkerCap = existing + " (set outside the mod)";
			return;
		}
		// Size the pool for the CPUs its threads will actually run on: the chunk-generation group when
		// it catches vanilla's workers, otherwise the game CPUs.
		boolean onChunkGen = cfg.chunkGenActive() && cfg.chunkGenMask != 0 && cfg.isChunkGenThread("Worker-Main-0");
		long workerCpus = onChunkGen ? cfg.chunkGenMask : cfg.gameMask;
		int cap = Math.max(1, Long.bitCount(workerCpus) - 1);
		System.setProperty(MAX_BG_THREADS_PROPERTY, Integer.toString(cap));
		vanillaWorkerCap = Integer.toString(cap);
		LOG.info("DH Affinity: set -D{}={} so Minecraft's background worker pool matches the {} CPUs it runs on ({}).",
				MAX_BG_THREADS_PROPERTY, cap, Long.bitCount(workerCpus), onChunkGen ? "chunk-generation group" : "game CPUs");
	}

	private void widenProcessAffinity(AffinityConfig cfg) {
		if (!cfg.manageProcessAffinity || !backend.supportsProcessAffinity()) {
			return;
		}
		long all = backend.allCpusMask();
		long process = backend.getProcessAffinity();
		if (process != 0 && process != all) {
			boolean ok = backend.setProcessAffinity(all);
			LOG.warn("DH Affinity: at launch the process was restricted to CPUs {} (mask {}); {}. "
							+ "If Process Lasso (or similar) has a rule for javaw, remove it — the mod takes over that job.",
					MaskFormat.toCpuList(process), MaskFormat.toHex(process),
					ok ? "widened to all CPUs" : "widening FAILED (OS error " + backend.lastError() + ")");
		}
	}

	/**
	 * Entry point used by the DH mixin for every thread DH's factory creates. Always wraps (when
	 * the platform can identify threads at all) so that enabling the mod later still finds the
	 * workers; the wrapper itself decides at start-up time whether to pin.
	 */
	public Runnable wrapDhWorker(Runnable original) {
		if (original == null || backend instanceof NoopAffinityBackend) {
			return original;
		}
		wrappedWorkers.incrementAndGet();
		return new DhWorkerRunnable(this, original);
	}

	void onWorkerStarted(long tid, String name, String pool, long desired, boolean pinned) {
		AffinityConfig cfg = config;
		if (pinned) {
			if (cfg.logPins) {
				LOG.info("DH Affinity: DH worker '{}' (thread {}, pool '{}') pinned itself to CPUs {}.", name, tid, pool,
						MaskFormat.toCpuList(desired));
			}
			return;
		}
		selfPinFailures.incrementAndGet();
		synchronized (logLock) {
			if (selfPinFailureLogs < 3) {
				selfPinFailureLogs++;
				LOG.warn("DH Affinity: DH worker '{}' (thread {}) could not pin itself to CPUs {} (OS error {}). "
								+ "On Windows this usually means the process affinity is restricted (Process Lasso?); the sweeper will retry.",
						name, tid, MaskFormat.toCpuList(desired), backend.lastError());
			}
		}
		AffinitySweeper s = sweeper;
		if (s != null) {
			s.poke();
		}
	}

	void onWorkerWithoutTid(String name) {
		workersWithoutTid.incrementAndGet();
		if (config.logPins) {
			LOG.info("DH Affinity: DH worker '{}' has no native thread id on this platform; not managed.", name);
		}
	}

	void onWrapperFailure(Throwable t) {
		wrapperFailures.incrementAndGet();
		synchronized (logLock) {
			if (wrapperFailureLogs < 3) {
				wrapperFailureLogs++;
				LOG.error("DH Affinity: error while preparing a DH worker thread; the worker runs unpinned.", t);
			}
		}
	}

	/** Re-read the config file and apply it on the next sweep. Returns a short human-readable result. */
	public synchronized String reload() {
		if (configFile == null) {
			return "No config file (detached instance).";
		}
		AffinityConfig.Json json = AffinityConfig.readOrCreate(configFile, LOG, () -> AffinityConfig.defaultsFor(backend.allCpusMask(), topology));
		return apply(json);
	}

	/** Persist {@code json} to the config file and apply it. Returns a short human-readable result. */
	public synchronized String save(AffinityConfig.Json json) {
		if (configFile == null) {
			return "No config file (detached instance).";
		}
		try {
			AffinityConfig.write(configFile, json);
		} catch (IOException e) {
			LOG.error("DH Affinity: cannot write {}", configFile, e);
			return "Could not write " + configFile.getFileName() + ": " + e.getMessage();
		}
		return apply(json);
	}

	private String apply(AffinityConfig.Json json) {
		AffinityConfig cfg = AffinityConfig.resolve(json, backend.allCpusMask(), LOG);
		config = cfg;
		if (cfg.enabled && sweeper == null && !(backend instanceof NoopAffinityBackend)) {
			widenProcessAffinity(cfg);
			AffinitySweeper s = new AffinitySweeper(this, LOG);
			sweeper = s;
			s.start();
		}
		AffinitySweeper s = sweeper;
		if (s != null) {
			s.configChanged();
		}
		LOG.info("DH Affinity: config applied (enabled={}, DH -> {}{}, main thread -> {}, game -> {}).", cfg.enabled,
				MaskFormat.toCpuList(cfg.dhMask), describePoolOverrides(cfg), MaskFormat.toCpuList(cfg.mainThreadMask),
				MaskFormat.toCpuList(cfg.gameMask));
		StringBuilder sb = new StringBuilder(cfg.enabled ? "Applied: " : "Applied (disabled; threads keep their current cores until re-enabled): ")
				.append("DH -> CPUs ").append(MaskFormat.toCpuList(cfg.dhMask)).append(describePoolOverrides(cfg))
				.append(", game -> CPUs ").append(MaskFormat.toCpuList(cfg.gameMask))
				.append(cfg.mainThreadMask != cfg.gameMask ? ", main thread -> CPUs " + MaskFormat.toCpuList(cfg.mainThreadMask) : "")
				.append('.');
		for (String warning : cfg.warnings) {
			sb.append("\nWarning: ").append(warning);
		}
		return sb.toString();
	}

	/** Plain-text status lines for the in-game command and logs. */
	public List<String> statusLines() {
		AffinityConfig cfg = config;
		List<String> lines = new ArrayList<>();
		lines.add("Backend: " + backend.name() + " | CPUs: " + backend.cpuCount() + " (" + MaskFormat.toCpuList(backend.allCpusMask())
				+ ") | " + topology.describe() + " | enabled: " + cfg.enabled);
		lines.add("DH workers -> CPUs " + MaskFormat.toCpuList(cfg.dhMask) + describePoolOverrides(cfg)
				+ " | main thread -> CPUs " + MaskFormat.toCpuList(cfg.mainThreadMask)
				+ " | others -> CPUs " + MaskFormat.toCpuList(cfg.gameMask)
				+ (cfg.manageNonDhThreads ? "" : " [non-DH threads not managed]"));
		if (backend.supportsProcessAffinity()) {
			long process = backend.getProcessAffinity();
			if (process == 0) {
				lines.add("Process affinity: unknown (OS error " + backend.lastError() + ")");
			} else {
				lines.add("Process affinity: " + MaskFormat.toHex(process) + " (CPUs " + MaskFormat.toCpuList(process) + ")"
						+ (process != backend.allCpusMask() ? " — RESTRICTED, DH pins will fail (Process Lasso rule?)" : " — ok"));
			}
		}
		if (!vanillaWorkerCap.isEmpty()) {
			lines.add("Minecraft worker threads (-D" + MAX_BG_THREADS_PROPERTY + "): " + vanillaWorkerCap);
		}
		int dhThreads = DhPools.configuredThreadCount();
		lines.add("DH hook: " + describeHook() + " | wrapped workers: " + wrappedWorkers.get()
				+ " | alive: " + registry.size()
				+ (dhThreads > 0 ? " | DH 'Number of Threads' setting: " + dhThreads : "")
				+ (workersWithoutTid.get() > 0 ? " | without thread id: " + workersWithoutTid.get() : "")
				+ (selfPinFailures.get() > 0 ? " | self-pin failures: " + selfPinFailures.get() : "")
				+ (wrapperFailures.get() > 0 ? " | wrapper errors: " + wrapperFailures.get() : ""));
		AffinitySweeper s = sweeper;
		if (s == null) {
			lines.add("Sweeper: not running" + (cfg.enabled ? "" : " (disabled)"));
		} else {
			AffinitySweeper.Stats st = s.stats();
			lines.add("Sweeper: " + (s.isAlive() ? "running" : "DEAD") + " | sweep #" + st.sweeps()
					+ " | threads " + st.lastThreads() + " (DH " + st.lastDhThreads() + ")"
					+ " | corrected " + st.lastCorrected() + " | unchanged " + st.lastUnchanged()
					+ " | skipped " + st.lastSkipped() + " | gone " + st.lastGone() + " | failed " + st.lastFailed()
					+ " | " + formatMicros(st.lastDurationMicros()) + " wall (max " + formatMicros(st.maxDurationMicros()) + ")"
					+ " | interval " + s.currentIntervalMs() + " ms"
					+ (s.leftAloneCount() > 0 ? " | left alone (kept moving back): " + s.leftAloneCount() : ""));
			lines.add("Totals: corrected " + st.totalCorrected() + " | failed " + st.totalFailed()
					+ " | process-mask resets " + st.processMaskResets());
		}
		if (cfg.jvmGroupActive() && cfg.manageNonDhThreads) {
			lines.add("JVM threads (GC, JIT) -> " + (cfg.jvmMask == 0 ? "unpinned (OS decides)" : "CPUs " + MaskFormat.toCpuList(cfg.jvmMask))
					+ " | matched " + (s == null ? "?" : Integer.toString(s.lastJvmThreads())) + " threads | jvmMask \"none\" leaves them unpinned");
		}
		if (cfg.chunkGenActive()) {
			StringBuilder sb = new StringBuilder("Chunk generation -> CPUs ").append(MaskFormat.toCpuList(cfg.chunkGenMask))
					.append(" | patterns ").append(cfg.chunkGenPatternTexts);
			if (!cfg.manageNonDhThreads) {
				sb.append(" | NOT applied (non-DH threads are not managed)");
			} else if (!localWorld) {
				sb.append(" | inactive: remote server (applied only with a local world)");
			} else if (s == null) {
				sb.append(" | matched: sweeper not running");
			} else {
				sb.append(" | matched ").append(s.stats().lastChunkGenThreads()).append(" threads");
				Map<String, Integer> names = s.lastChunkGenNames();
				if (!names.isEmpty()) {
					sb.append(" (");
					names.forEach((name, count) -> sb.append(name).append(" x").append(count).append(", "));
					sb.setLength(sb.length() - 2);
					sb.append(')');
				}
			}
			lines.add(sb.toString());
		}
		Map<String, Integer> byPool = new TreeMap<>();
		for (ThreadRegistry.DhThread t : registry.snapshot()) {
			byPool.merge(t.pool(), 1, Integer::sum);
		}
		if (!byPool.isEmpty()) {
			StringBuilder sb = new StringBuilder("DH threads alive:");
			byPool.forEach((pool, count) -> sb.append(' ').append(pool).append(" x").append(count)
					.append(" -> ").append(MaskFormat.toCpuList(cfg.maskForDhPool(pool))).append(','));
			sb.setLength(sb.length() - 1);
			lines.add(sb.toString());
		}
		if (cfg.enabled && !cfg.dhPinningActive()) {
			lines.add("Note: no DH group selects a usable CPU; DH threads are not pinned.");
		}
		for (Supplier<List<String>> provider : statusProviders) {
			try {
				lines.addAll(provider.get());
			} catch (Throwable t) {
				lines.add("(status provider failed: " + t + ")");
			}
		}
		for (String warning : cfg.warnings) {
			lines.add("Config warning: " + warning);
		}
		return lines;
	}

	/** Client-side subsystems (GPU upload, frame diagnostics) contribute their own status lines. */
	public void addStatusProvider(Supplier<List<String>> provider) {
		statusProviders.add(provider);
	}

	private String describeHook() {
		return switch (hookState) {
			case APPLIED -> wrappedWorkers.get() > 0 ? "active" : "hooked (pools wrap their workers on creation; none created yet)";
			case NOT_APPLIED -> "not applied yet (DH pool classes not loaded, or mixin skipped)";
			case TARGET_CLASS_MISSING -> "INACTIVE — a DH pool class was not found (incompatible DH version)";
			case TARGET_METHOD_MISSING -> "INACTIVE — a DH pool method was not found (incompatible DH version)";
		};
	}

	private static String formatMicros(long micros) {
		if (micros < 1000) {
			return micros + " us";
		}
		return String.format("%.2f ms", micros / 1000.0);
	}

	public AffinityBackend backend() {
		return backend;
	}

	public CpuTopology topology() {
		return topology;
	}

	public AffinityConfig config() {
		return config;
	}

	public ThreadRegistry registry() {
		return registry;
	}

	public AffinitySweeper sweeper() {
		return sweeper;
	}

	/** Whether the current world runs on the integrated server (singleplayer / LAN host). */
	public boolean localWorld() {
		return localWorld;
	}

	/**
	 * Set by the client on world join/leave. The chunk-generation thread group only applies with a local
	 * world: on a remote server those threads do no terrain generation, so moving them buys nothing.
	 */
	public void setLocalWorld(boolean local) {
		if (localWorld != local) {
			localWorld = local;
			AffinitySweeper s = sweeper;
			if (s != null) {
				// The chunk-generation threads legitimately move now; a config-style reset keeps that from
				// counting against them as "moving themselves back".
				s.configChanged();
			}
		}
	}

	/** Native ID of the main/render thread (captured at preLaunch), or 0 if unknown. */
	public long mainThreadTid() {
		return mainThreadTid;
	}

	public static HookState hookState() {
		return hookState;
	}

	public static void setHookState(HookState state) {
		hookState = state;
	}

	public long wrappedWorkers() {
		return wrappedWorkers.get();
	}

	public long wrapperFailures() {
		return wrapperFailures.get();
	}

	/** Tests only: swap the config in a detached instance. */
	void setConfigForTest(AffinityConfig cfg) {
		config = cfg;
	}

	/** Tests only. */
	void setMainThreadTidForTest(long tid) {
		mainThreadTid = tid;
	}

	/** Tests only: create (but do not start) a sweeper for a detached instance. */
	AffinitySweeper createSweeperForTest() {
		AffinitySweeper s = new AffinitySweeper(this, LOG);
		sweeper = s;
		return s;
	}
}
