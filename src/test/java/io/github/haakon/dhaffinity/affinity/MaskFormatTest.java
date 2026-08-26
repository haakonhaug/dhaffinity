package io.github.haakon.dhaffinity.affinity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaskFormatTest {

	@Test
	void parsesHex() {
		assertEquals(0xFFFF_0000L, MaskFormat.parse("0xFFFF0000"));
		assertEquals(0xFFFF_0000L, MaskFormat.parse(" 0xffff_0000 "));
		assertEquals(0x0000_FFFFL, MaskFormat.parse("0x0000FFFF"));
		assertEquals(-1L, MaskFormat.parse("0xFFFFFFFFFFFFFFFF"));
		assertEquals(0L, MaskFormat.parse("0x0"));
	}

	@Test
	void parsesHexWithLeadingZerosBeyondSixteenDigits() {
		assertEquals(0xFFFFL, MaskFormat.parse("0x00000000000000000000FFFF"));
		assertEquals(0xFFFFL, MaskFormat.parse("0x0000_0000_0000_0000_0000_FFFF"));
		assertEquals(0L, MaskFormat.parse("0x000000000000000000000000"));
		assertEquals(-1L, MaskFormat.parse("0x0FFFFFFFFFFFFFFFF"));
		// Seventeen significant digits are still too many.
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse("0x010000000000000000"));
	}

	@Test
	void parsesCpuLists() {
		assertEquals(0xFFFF_0000L, MaskFormat.parse("16-31"));
		assertEquals(0x0000_FFFFL, MaskFormat.parse("0-15"));
		assertEquals(0b1011_0101L, MaskFormat.parse("0,2,4-5,7"));
		assertEquals(1L << 63, MaskFormat.parse("63"));
	}

	@Test
	void rejectsGarbage() {
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse(""));
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse("0x"));
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse("0xZZ"));
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse("0x1FFFFFFFFFFFFFFFF"));
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse("64"));
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse("5-2"));
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse("cores"));
		assertThrows(IllegalArgumentException.class, () -> MaskFormat.parse(null));
	}

	@Test
	void formats() {
		assertEquals("0x00000000FFFF0000", MaskFormat.toHex(0xFFFF_0000L));
		assertEquals("16-31", MaskFormat.toCpuList(0xFFFF_0000L));
		assertEquals("0,2,4-5,7", MaskFormat.toCpuList(0b1011_0101L));
		assertEquals("none", MaskFormat.toCpuList(0));
		assertEquals("0-63", MaskFormat.toCpuList(-1L));
		assertEquals(0xFFFF_FFFFL, MaskFormat.lowBits(32));
		assertEquals(-1L, MaskFormat.lowBits(64));
		assertEquals(0L, MaskFormat.lowBits(0));
	}
}
