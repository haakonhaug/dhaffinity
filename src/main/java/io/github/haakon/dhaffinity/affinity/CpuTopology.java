package io.github.haakon.dhaffinity.affinity;

import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which logical CPUs share a last-level (L3) cache. On chiplet CPUs such as the Ryzen 9950X3D each
 * CCD has its own L3, so these groups are exactly the "CCD" presets a user wants to pick from.
 * Detection failures degrade to an empty list; callers fall back to lower/upper halves.
 */
public final class CpuTopology {

	/** An L3 cache and the logical CPUs that share it. */
	public record CacheGroup(long mask, long sizeBytes) {}

	private static final CpuTopology EMPTY = new CpuTopology(List.of());

	private final List<CacheGroup> l3Groups;

	private CpuTopology(List<CacheGroup> l3Groups) {
		this.l3Groups = Collections.unmodifiableList(new ArrayList<>(l3Groups));
	}

	/** Detect for the running OS; never throws. */
	public static CpuTopology detect() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("win")) {
				return new CpuTopology(Windows.l3Groups());
			}
			if (os.contains("linux")) {
				return new CpuTopology(linuxL3Groups());
			}
		} catch (Throwable t) {
			// Topology is a convenience only; fall through to EMPTY.
		}
		return EMPTY;
	}

	public static CpuTopology of(List<CacheGroup> groups) {
		return new CpuTopology(groups);
	}

	/** L3 groups sorted by their lowest CPU; empty if unknown. */
	public List<CacheGroup> l3Groups() {
		return l3Groups;
	}

	/**
	 * Masks worth offering as one-click presets: the L3 groups when there are at least two of
	 * them, otherwise the lower and upper half of {@code allCpusMask} (when it has 2+ CPUs).
	 */
	public List<Long> presets(long allCpusMask) {
		List<Long> out = new ArrayList<>();
		if (l3Groups.size() >= 2) {
			for (CacheGroup g : l3Groups) {
				long m = g.mask() & allCpusMask;
				if (m != 0 && !out.contains(m)) {
					out.add(m);
				}
			}
			if (out.size() >= 2) {
				return out;
			}
			out.clear();
		}
		int count = Long.bitCount(allCpusMask);
		if (count >= 2) {
			int half = count / 2;
			long lower = 0;
			long upper = 0;
			int seen = 0;
			for (int cpu = 0; cpu < 64; cpu++) {
				if ((allCpusMask & (1L << cpu)) == 0) {
					continue;
				}
				if (seen++ < half) {
					lower |= 1L << cpu;
				} else {
					upper |= 1L << cpu;
				}
			}
			out.add(lower);
			out.add(upper);
		}
		return out;
	}

	/** Human-readable summary such as {@code L3 groups: 0-15 (96 MB), 16-31 (32 MB)}. */
	public String describe() {
		if (l3Groups.isEmpty()) {
			return "L3 cache layout: unknown";
		}
		StringBuilder sb = new StringBuilder("L3 groups: ");
		for (int i = 0; i < l3Groups.size(); i++) {
			CacheGroup g = l3Groups.get(i);
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(MaskFormat.toCpuList(g.mask()));
			if (g.sizeBytes() > 0) {
				sb.append(" (").append(g.sizeBytes() / (1024 * 1024)).append(" MB)");
			}
		}
		return sb.toString();
	}

	private static List<CacheGroup> sorted(Map<Long, Long> sizeByMask) {
		List<CacheGroup> groups = new ArrayList<>();
		sizeByMask.forEach((mask, size) -> groups.add(new CacheGroup(mask, size)));
		groups.sort(Comparator.comparingInt(g -> Long.numberOfTrailingZeros(g.mask())));
		return groups;
	}

	/** Isolated so the JNA platform classes are only touched on Windows. */
	private static final class Windows {
		static List<CacheGroup> l3Groups() {
			Map<Long, Long> sizeByMask = new LinkedHashMap<>();
			WinNT.SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX[] infos =
					Kernel32Util.getLogicalProcessorInformationEx(WinNT.LOGICAL_PROCESSOR_RELATIONSHIP.RelationCache);
			for (WinNT.SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX info : infos) {
				if (!(info instanceof WinNT.CACHE_RELATIONSHIP cache) || cache.level != 3 || cache.groupMask == null) {
					continue;
				}
				if (cache.groupMask.group != 0 || cache.groupMask.mask == null) {
					continue; // only processor group 0 is addressable with a 64-bit mask
				}
				long mask = cache.groupMask.mask.longValue();
				if (mask != 0) {
					sizeByMask.merge(mask, Integer.toUnsignedLong(cache.cacheSize), Math::max);
				}
			}
			return sorted(sizeByMask);
		}
	}

	private static List<CacheGroup> linuxL3Groups() throws IOException {
		Map<Long, Long> sizeByMask = new LinkedHashMap<>();
		Path cpus = Paths.get("/sys/devices/system/cpu");
		try (DirectoryStream<Path> cpuDirs = Files.newDirectoryStream(cpus, "cpu[0-9]*")) {
			for (Path cpuDir : cpuDirs) {
				Path cache = cpuDir.resolve("cache");
				if (!Files.isDirectory(cache)) {
					continue;
				}
				try (DirectoryStream<Path> indexes = Files.newDirectoryStream(cache, "index*")) {
					for (Path index : indexes) {
						if (!"3".equals(read(index.resolve("level")))) {
							continue;
						}
						long mask = parseCpuList(read(index.resolve("shared_cpu_list")));
						if (mask == 0) {
							continue;
						}
						long size = parseSize(read(index.resolve("size")));
						sizeByMask.merge(mask, size, Math::max);
					}
				}
			}
		}
		return sorted(sizeByMask);
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.US_ASCII).trim();
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	/** {@code 0-15,32} style lists; CPUs above 63 are ignored. */
	static long parseCpuList(String list) {
		long mask = 0;
		for (String part : list.split(",")) {
			part = part.trim();
			if (part.isEmpty()) {
				continue;
			}
			try {
				int dash = part.indexOf('-');
				int from = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
				int to = Integer.parseInt(dash < 0 ? part : part.substring(dash + 1));
				for (int cpu = Math.max(0, from); cpu <= to && cpu < 64; cpu++) {
					mask |= 1L << cpu;
				}
			} catch (NumberFormatException ignored) {
				// skip malformed segment
			}
		}
		return mask;
	}

	/** {@code 98304K} / {@code 32M} / plain bytes. */
	static long parseSize(String text) {
		if (text.isEmpty()) {
			return 0;
		}
		char unit = Character.toUpperCase(text.charAt(text.length() - 1));
		String digits = Character.isDigit(unit) ? text : text.substring(0, text.length() - 1);
		try {
			long value = Long.parseLong(digits.trim());
			return switch (unit) {
				case 'K' -> value * 1024L;
				case 'M' -> value * 1024L * 1024L;
				case 'G' -> value * 1024L * 1024L * 1024L;
				default -> value;
			};
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
