package io.github.haakon.dhaffinity.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.haakon.dhaffinity.affinity.CpuTopology;
import io.github.haakon.dhaffinity.affinity.MaskFormat;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Resolved, validated configuration. Immutable; a reload produces a new instance.
 *
 * <p>{@link Json} is the on-disk shape ({@code config/dhaffinity.json}). Thread groups:
 * <ul>
 *   <li>{@code gameMask} — every thread that is not a DH worker (Minecraft, JVM, drivers…).</li>
 *   <li>{@code mainThreadMask} — the main/render thread only; empty = same as {@code gameMask}.</li>
 *   <li>{@code dhMask} — Distant Horizons worker threads.</li>
 *   <li>{@code dhPoolMasks} — per-pool overrides ({@code "World Gen": "16-23"}); a pool that is
 *       absent or empty follows {@code dhMask}.</li>
 *   <li>{@code chunkGenMask} — Minecraft's own terrain-generation workers (threads whose name
 *       starts with one of {@code chunkGenThreadPatterns}); empty = same as {@code dhMask}.</li>
 * </ul>
 * Masks may overlap freely. An empty {@code gameMask}/{@code dhMask} means "all CPUs".
 */
public final class AffinityConfig {

	public static final String FILE_NAME = "dhaffinity.json";
	public static final int MIN_SWEEP_MS = 50;
	public static final int MAX_TASK_BUDGET_MS = 50;
	/** Registry pool name of the mod's own GPU upload thread (so it gets a menu row and a mask). */
	public static final String GPU_UPLOAD_POOL = "GPU Upload";
	/** DH pools that get their own row in the menu; every other pool follows {@code dhMask}. */
	public static final List<String> KNOWN_DH_POOLS = List.of(
			"World Gen", "LOD Builder", "Render Loader", "IO", "Update Propagator", "Network Compression", "GPU Upload");
	/**
	 * Thread-name prefixes (regular expressions, matched at the start of the name) of the game's
	 * terrain-generation workers: vanilla's background pool and C2ME's global executor.
	 */
	public static final List<String> DEFAULT_CHUNK_GEN_PATTERNS = List.of("Worker-Main-", "c2me-worker-");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** On-disk representation. Public fields so Gson can (de)serialise it; missing keys keep defaults. */
	public static final class Json {
		/** Master switch. */
		public boolean enabled = true;
		/** CPUs for everything that is not a DH worker. CPU list ({@code 0-15}) or hex ({@code 0x0000FFFF}); empty = all CPUs. */
		public String gameMask = "";
		/** CPUs for the main/render thread; empty = same as gameMask. */
		public String mainThreadMask = "";
		/** CPUs for Distant Horizons worker threads; empty = all CPUs. */
		public String dhMask = "";
		/** Per-pool overrides, e.g. {@code {"World Gen": "16-23"}}; absent/empty = dhMask. */
		public Map<String, String> dhPoolMasks = new LinkedHashMap<>();
		/**
		 * CPUs for Minecraft's own terrain-generation worker threads (see chunkGenThreadPatterns);
		 * empty = same as dhMask. Keeping these off the game CPUs prevents stutter while a new world
		 * generates.
		 */
		public String chunkGenMask = "";
		/**
		 * Regular expressions matched against the start of a thread's name; matching threads belong
		 * to the chunk-generation group. An empty list disables the group.
		 */
		public List<String> chunkGenThreadPatterns = new ArrayList<>(DEFAULT_CHUNK_GEN_PATTERNS);
		/** Pin every non-DH thread of the process (replaces a Process Lasso rule). */
		public boolean manageNonDhThreads = true;
		/** Keep the Windows process-wide affinity mask at "all CPUs" so thread pins can succeed. */
		public boolean manageProcessAffinity = true;
		/** Size Minecraft's background worker pool for the game CPUs (sets -Dmax.bg.threads if unset). */
		public boolean capVanillaWorkerThreads = true;
		/** Sweep interval during the first startupWindowMs after launch. */
		public int startupSweepMs = 250;
		/** Length of the fast-sweep window after launch (0 disables it). */
		public int startupWindowMs = 30000;
		/** Sweep interval afterwards. */
		public int steadySweepMs = 2000;
		/** Log every individual pin (verbose). */
		public boolean logPins = false;
		/**
		 * Run Distant Horizons' GPU buffer uploads on a dedicated thread with its own OpenGL context
		 * instead of the render thread (experimental; removes the upload hitch while exploring).
		 */
		public boolean offThreadGpuUpload = true;
		/**
		 * Milliseconds per frame the render thread may spend on DH's queued GL tasks when uploads are
		 * NOT off-loaded; 0 = DH's own default (half a frame at your FPS limit).
		 */
		public int renderThreadTaskBudgetMs = 0;
		/**
		 * Speed limit for handing finished LOD sections to the renderer: "auto" (adaptive), "off", or a
		 * number of sections per frame. Only applies while off-thread GPU upload is active.
		 */
		public String uploadPacing = "auto";

		public Json copy() {
			Json c = new Json();
			c.enabled = enabled;
			c.gameMask = gameMask;
			c.mainThreadMask = mainThreadMask;
			c.dhMask = dhMask;
			c.dhPoolMasks = dhPoolMasks == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dhPoolMasks);
			c.chunkGenMask = chunkGenMask;
			c.chunkGenThreadPatterns = chunkGenThreadPatterns == null ? new ArrayList<>() : new ArrayList<>(chunkGenThreadPatterns);
			c.manageNonDhThreads = manageNonDhThreads;
			c.manageProcessAffinity = manageProcessAffinity;
			c.capVanillaWorkerThreads = capVanillaWorkerThreads;
			c.startupSweepMs = startupSweepMs;
			c.startupWindowMs = startupWindowMs;
			c.steadySweepMs = steadySweepMs;
			c.logPins = logPins;
			c.offThreadGpuUpload = offThreadGpuUpload;
			c.renderThreadTaskBudgetMs = renderThreadTaskBudgetMs;
			c.uploadPacing = uploadPacing;
			return c;
		}
	}

	public final boolean enabled;
	public final long gameMask;
	public final long mainThreadMask;
	public final long dhMask;
	/** Only the explicit, non-empty overrides. */
	public final Map<String, Long> dhPoolMasks;
	/** Mask for the chunk-generation group (already defaulted to {@link #dhMask} when unset). */
	public final long chunkGenMask;
	/** Compiled, valid {@code chunkGenThreadPatterns}; empty = group disabled. */
	public final List<Pattern> chunkGenPatterns;
	/** The texts of {@link #chunkGenPatterns}, for display. */
	public final List<String> chunkGenPatternTexts;
	public final boolean manageNonDhThreads;
	public final boolean manageProcessAffinity;
	public final boolean capVanillaWorkerThreads;
	public final int startupSweepMs;
	public final int startupWindowMs;
	public final int steadySweepMs;
	public final boolean logPins;
	public final boolean offThreadGpuUpload;
	/** 0 = DH default. */
	public final int renderThreadTaskBudgetMs;
	/** "auto", "off", or a fixed sections-per-frame count as text (validated: see {@link #uploadPacingFixed}). */
	public final String uploadPacing;
	/** Fixed sections per frame when {@link #uploadPacing} is numeric; 0 otherwise. */
	public final int uploadPacingFixed;
	/** Problems found while resolving; already logged, kept for the status command. */
	public final List<String> warnings;
	/** The raw values this instance was resolved from. */
	public final Json source;

	private AffinityConfig(Json source, boolean enabled, long gameMask, long mainThreadMask, long dhMask,
			Map<String, Long> dhPoolMasks, long chunkGenMask, List<Pattern> chunkGenPatterns, List<String> chunkGenPatternTexts,
			boolean manageNonDhThreads, boolean manageProcessAffinity,
			boolean capVanillaWorkerThreads, int startupSweepMs, int startupWindowMs, int steadySweepMs,
			boolean logPins, boolean offThreadGpuUpload, int renderThreadTaskBudgetMs, String uploadPacing, int uploadPacingFixed,
			List<String> warnings) {
		this.source = source;
		this.enabled = enabled;
		this.gameMask = gameMask;
		this.mainThreadMask = mainThreadMask;
		this.dhMask = dhMask;
		this.dhPoolMasks = Collections.unmodifiableMap(new LinkedHashMap<>(dhPoolMasks));
		this.chunkGenMask = chunkGenMask;
		this.chunkGenPatterns = List.copyOf(chunkGenPatterns);
		this.chunkGenPatternTexts = List.copyOf(chunkGenPatternTexts);
		this.manageNonDhThreads = manageNonDhThreads;
		this.manageProcessAffinity = manageProcessAffinity;
		this.capVanillaWorkerThreads = capVanillaWorkerThreads;
		this.startupSweepMs = startupSweepMs;
		this.startupWindowMs = startupWindowMs;
		this.steadySweepMs = steadySweepMs;
		this.logPins = logPins;
		this.offThreadGpuUpload = offThreadGpuUpload;
		this.renderThreadTaskBudgetMs = renderThreadTaskBudgetMs;
		this.uploadPacing = uploadPacing;
		this.uploadPacingFixed = uploadPacingFixed;
		this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
	}

	/** An inert configuration (used before initialisation). */
	public static AffinityConfig disabled() {
		Json json = new Json();
		json.enabled = false;
		return new AffinityConfig(json, false, 0, 0, 0, Map.of(), 0, List.of(), List.of(), false, false, false, 250, 30000, 2000, false, false, 0, "off", 0, List.of());
	}

	/** Whether the chunk-generation group exists at all (at least one valid pattern). */
	public boolean chunkGenActive() {
		return !chunkGenPatterns.isEmpty();
	}

	/** Whether a thread with this OS-level name belongs to the chunk-generation group (prefix match). */
	public boolean isChunkGenThread(String threadName) {
		if (threadName == null) {
			return false;
		}
		for (Pattern p : chunkGenPatterns) {
			if (p.matcher(threadName).lookingAt()) {
				return true;
			}
		}
		return false;
	}

	/** Mask for a DH pool: its override if present, otherwise {@code dhMask}. */
	public long maskForDhPool(String pool) {
		Long override = dhPoolMasks.get(pool);
		return override != null ? override : dhMask;
	}

	/** Whether any DH thread can actually be pinned with this configuration (false only when disabled or masks are 0). */
	public boolean dhPinningActive() {
		if (!enabled) {
			return false;
		}
		if (dhMask != 0) {
			return true;
		}
		for (long m : dhPoolMasks.values()) {
			if (m != 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Defaults for a machine: with two or more L3 groups the one with the largest cache (the
	 * V-cache CCD on X3D parts) gets the game and the rest get DH; otherwise both groups use all
	 * CPUs (no split) and the user picks one in the menu.
	 */
	public static Json defaultsFor(long allCpusMask, CpuTopology topology) {
		Json json = new Json();
		List<CpuTopology.CacheGroup> groups = new ArrayList<>();
		for (CpuTopology.CacheGroup g : topology.l3Groups()) {
			long m = g.mask() & allCpusMask;
			if (m != 0 && groups.stream().noneMatch(x -> x.mask() == m)) {
				groups.add(new CpuTopology.CacheGroup(m, g.sizeBytes()));
			}
		}
		if (groups.size() >= 2) {
			CpuTopology.CacheGroup game = groups.get(0);
			for (CpuTopology.CacheGroup g : groups) {
				if (g.sizeBytes() > game.sizeBytes()) {
					game = g;
				}
			}
			long dh = 0;
			for (CpuTopology.CacheGroup g : groups) {
				if (g != game) {
					dh |= g.mask();
				}
			}
			json.gameMask = MaskFormat.toCpuList(game.mask());
			json.dhMask = MaskFormat.toCpuList(dh);
		} else {
			json.gameMask = MaskFormat.toCpuList(allCpusMask);
			json.dhMask = MaskFormat.toCpuList(allCpusMask);
		}
		return json;
	}

	/**
	 * Read the file, or create it from {@code defaults} if it does not exist. A malformed file is
	 * left untouched (so the user can fix it) and the defaults are used.
	 */
	public static Json readOrCreate(Path file, Logger log, Supplier<Json> defaults) {
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Json json = GSON.fromJson(reader, Json.class);
				if (json == null) {
					log.warn("DH Affinity: {} is empty; using defaults.", file);
					return defaults.get();
				}
				if (json.dhPoolMasks == null) {
					json.dhPoolMasks = new LinkedHashMap<>();
				}
				return json;
			} catch (IOException | JsonParseException e) {
				log.error("DH Affinity: cannot read {} ({}); using defaults. The file was left unchanged.", file, e.toString());
				return defaults.get();
			}
		}
		Json json = defaults.get();
		try {
			write(file, json);
			log.info("DH Affinity: wrote default config to {}", file);
		} catch (IOException e) {
			log.error("DH Affinity: cannot write default config to {} ({}); using defaults.", file, e.toString());
		}
		return json;
	}

	/**
	 * Serialise {@code json} to {@code file} (pretty-printed), creating parent directories. Written
	 * to a sibling temp file first and then moved, so a failure never leaves a truncated config.
	 */
	public static void write(Path file, Json json) throws IOException {
		Path dir = file.toAbsolutePath().getParent();
		if (dir != null) {
			Files.createDirectories(dir);
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			GSON.toJson(json, writer);
		}
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Validate raw values against the machine; problems are logged and recorded in {@link #warnings}. */
	public static AffinityConfig resolve(Json json, long allCpusMask, Logger log) {
		List<String> warnings = new ArrayList<>();

		long gameMask = resolveMask("gameMask", json.gameMask, allCpusMask, allCpusMask, warnings);
		long dhMask = resolveMask("dhMask", json.dhMask, allCpusMask, allCpusMask, warnings);
		long mainThreadMask = isBlank(json.mainThreadMask) ? gameMask
				: resolveMask("mainThreadMask", json.mainThreadMask, allCpusMask, gameMask, warnings);

		Map<String, Long> poolMasks = new LinkedHashMap<>();
		if (json.dhPoolMasks != null) {
			for (Map.Entry<String, String> e : json.dhPoolMasks.entrySet()) {
				if (e.getKey() == null || isBlank(e.getKey()) || isBlank(e.getValue())) {
					continue;
				}
				poolMasks.put(e.getKey().trim(), resolveMask("dhPoolMasks." + e.getKey(), e.getValue(), allCpusMask, dhMask, warnings));
			}
		}

		long chunkGenMask = resolveMask("chunkGenMask", json.chunkGenMask, allCpusMask, dhMask, warnings);
		List<Pattern> chunkGenPatterns = new ArrayList<>();
		List<String> chunkGenPatternTexts = new ArrayList<>();
		if (json.chunkGenThreadPatterns != null) {
			for (String text : json.chunkGenThreadPatterns) {
				if (text == null || isBlank(text)) {
					continue;
				}
				try {
					chunkGenPatterns.add(Pattern.compile(text.trim()));
					chunkGenPatternTexts.add(text.trim());
				} catch (PatternSyntaxException e) {
					warnings.add("chunkGenThreadPatterns: '" + text + "' is not a valid regular expression (" + e.getDescription() + "); ignoring it.");
				}
			}
		}

		int startupSweepMs = clampInterval("startupSweepMs", json.startupSweepMs, warnings);
		int steadySweepMs = clampInterval("steadySweepMs", json.steadySweepMs, warnings);
		int startupWindowMs = json.startupWindowMs;
		if (startupWindowMs < 0) {
			warnings.add("startupWindowMs " + startupWindowMs + " is negative; using 0.");
			startupWindowMs = 0;
		}

		int budget = json.renderThreadTaskBudgetMs;
		if (budget < 0 || budget > MAX_TASK_BUDGET_MS) {
			warnings.add("renderThreadTaskBudgetMs " + budget + " is outside 0-" + MAX_TASK_BUDGET_MS + "; using 0 (DH default).");
			budget = 0;
		}

		String pacing = json.uploadPacing == null ? "auto" : json.uploadPacing.trim().toLowerCase(java.util.Locale.ROOT);
		int pacingFixed = 0;
		if (!pacing.equals("auto") && !pacing.equals("off")) {
			try {
				pacingFixed = Integer.parseInt(pacing);
				if (pacingFixed < 1 || pacingFixed > 64) {
					warnings.add("uploadPacing " + pacing + " is outside 1-64 sections per frame; using auto.");
					pacing = "auto";
					pacingFixed = 0;
				}
			} catch (NumberFormatException e) {
				warnings.add("uploadPacing '" + json.uploadPacing + "' is not auto, off or a number; using auto.");
				pacing = "auto";
			}
		}

		for (String warning : warnings) {
			log.warn("DH Affinity config: {}", warning);
		}
		return new AffinityConfig(json, json.enabled, gameMask, mainThreadMask, dhMask, poolMasks, chunkGenMask, chunkGenPatterns,
				chunkGenPatternTexts, json.manageNonDhThreads,
				json.manageProcessAffinity, json.capVanillaWorkerThreads, startupSweepMs, startupWindowMs, steadySweepMs,
				json.logPins, json.offThreadGpuUpload, budget, pacing, pacingFixed, warnings);
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	/**
	 * @param emptyMeans the mask to use when the text is empty or unparsable
	 */
	private static long resolveMask(String key, String text, long allCpusMask, long emptyMeans, List<String> warnings) {
		if (isBlank(text)) {
			return emptyMeans;
		}
		long mask;
		try {
			mask = MaskFormat.parse(text);
		} catch (IllegalArgumentException e) {
			warnings.add(key + ": " + e.getMessage() + "; using " + MaskFormat.toCpuList(emptyMeans) + ".");
			return emptyMeans;
		}
		long outside = mask & ~allCpusMask;
		if (outside != 0) {
			warnings.add(key + " " + MaskFormat.toCpuList(mask) + " names CPUs " + MaskFormat.toCpuList(outside)
					+ " that this machine does not have (it has CPUs " + MaskFormat.toCpuList(allCpusMask) + "); ignoring those bits.");
			mask &= allCpusMask;
		}
		if (mask == 0) {
			// A thread must be allowed somewhere; on Linux "unpinned" would just mean "inherits the
			// creator's mask", so use every CPU and say so.
			warnings.add(key + " selects no usable CPU on this machine; using all CPUs (" + MaskFormat.toCpuList(allCpusMask) + ") for that group.");
			return allCpusMask;
		}
		return mask;
	}

	private static int clampInterval(String key, int value, List<String> warnings) {
		if (value < MIN_SWEEP_MS) {
			warnings.add(key + " " + value + " is below the minimum of " + MIN_SWEEP_MS + " ms; using " + MIN_SWEEP_MS + ".");
			return MIN_SWEEP_MS;
		}
		return value;
	}
}
