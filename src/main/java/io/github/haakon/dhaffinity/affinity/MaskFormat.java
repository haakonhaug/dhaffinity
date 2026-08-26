package io.github.haakon.dhaffinity.affinity;

/** Parsing and formatting of CPU affinity masks. */
public final class MaskFormat {

	private MaskFormat() {}

	/**
	 * Parse a mask from either hex ({@code 0xFFFF0000}; leading zeros are ignored, up to 16
	 * significant digits) or a CPU list ({@code 16-31} or {@code 0,2,4-7}).
	 *
	 * @throws IllegalArgumentException if the text is not a mask or names a CPU beyond 63
	 */
	public static long parse(String text) {
		if (text == null) {
			throw new IllegalArgumentException("mask is null");
		}
		String t = text.trim().replace("_", "");
		if (t.isEmpty()) {
			throw new IllegalArgumentException("mask is empty");
		}
		if (t.equalsIgnoreCase("none")) {
			return 0; // what toCpuList(0) prints
		}
		if (t.regionMatches(true, 0, "0x", 0, 2)) {
			String hex = t.substring(2);
			if (hex.isEmpty()) {
				throw new IllegalArgumentException("Cannot parse CPU mask '" + text + "' (expected up to 16 hex digits after 0x)");
			}
			// Leading zeros carry no information: 0x00000000000000000000FFFF is a valid 0xFFFF.
			int firstSignificant = 0;
			while (firstSignificant < hex.length() - 1 && hex.charAt(firstSignificant) == '0') {
				firstSignificant++;
			}
			hex = hex.substring(firstSignificant);
			if (hex.length() > 16) {
				throw new IllegalArgumentException("Cannot parse CPU mask '" + text + "' (expected up to 16 significant hex digits after 0x)");
			}
			try {
				return Long.parseUnsignedLong(hex, 16);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Cannot parse CPU mask '" + text + "' as hex", e);
			}
		}
		if (t.matches("[0-9,\\-\\s]+")) {
			long mask = 0;
			for (String part : t.split(",")) {
				part = part.trim();
				if (part.isEmpty()) {
					continue;
				}
				int dash = part.indexOf('-');
				if (dash < 0) {
					mask |= bit(parseCpu(part, text));
				} else {
					int from = parseCpu(part.substring(0, dash).trim(), text);
					int to = parseCpu(part.substring(dash + 1).trim(), text);
					if (to < from) {
						throw new IllegalArgumentException("Cannot parse CPU mask '" + text + "': range " + part + " is reversed");
					}
					for (int cpu = from; cpu <= to; cpu++) {
						mask |= bit(cpu);
					}
				}
			}
			if (mask == 0) {
				throw new IllegalArgumentException("Cannot parse CPU mask '" + text + "': no CPUs listed");
			}
			return mask;
		}
		throw new IllegalArgumentException("Cannot parse CPU mask '" + text + "' (use hex like 0xFFFF0000 or a CPU list like 16-31)");
	}

	private static int parseCpu(String s, String whole) {
		int cpu;
		try {
			cpu = Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Cannot parse CPU mask '" + whole + "': '" + s + "' is not a CPU number", e);
		}
		if (cpu < 0 || cpu > 63) {
			throw new IllegalArgumentException("Cannot parse CPU mask '" + whole + "': CPU " + cpu + " is outside 0-63");
		}
		return cpu;
	}

	private static long bit(int cpu) {
		return 1L << cpu;
	}

	/** {@code 0x} followed by 16 upper-case hex digits. */
	public static String toHex(long mask) {
		String hex = Long.toHexString(mask).toUpperCase();
		return "0x" + "0".repeat(16 - hex.length()) + hex;
	}

	/** Compact CPU list such as {@code 0-15} or {@code 0,2,4-7}; {@code none} for an empty mask. */
	public static String toCpuList(long mask) {
		if (mask == 0) {
			return "none";
		}
		StringBuilder sb = new StringBuilder();
		int cpu = 0;
		while (cpu < 64) {
			if ((mask & bit(cpu)) == 0) {
				cpu++;
				continue;
			}
			int start = cpu;
			while (cpu + 1 < 64 && (mask & bit(cpu + 1)) != 0) {
				cpu++;
			}
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(start);
			if (cpu > start) {
				sb.append('-').append(cpu);
			}
			cpu++;
		}
		return sb.toString();
	}

	/** Mask with the lowest {@code count} bits set (clamped to 64). */
	public static long lowBits(int count) {
		if (count <= 0) {
			return 0;
		}
		if (count >= 64) {
			return -1L;
		}
		return (1L << count) - 1;
	}
}
