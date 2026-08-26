package io.github.haakon.dhaffinity.affinity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CpuTopologyTest {

	@Test
	void parsesSysfsLists() {
		assertEquals(0xFFFFL, CpuTopology.parseCpuList("0-15"));
		assertEquals(0xFFFF_0000L, CpuTopology.parseCpuList("16-31"));
		assertEquals(0b1011L, CpuTopology.parseCpuList("0-1,3"));
		assertEquals(0L, CpuTopology.parseCpuList(""));
		assertEquals(0L, CpuTopology.parseCpuList("garbage"));
		assertEquals(1L << 63, CpuTopology.parseCpuList("63-70"));
	}

	@Test
	void parsesSysfsSizes() {
		assertEquals(98304L * 1024, CpuTopology.parseSize("98304K"));
		assertEquals(32L * 1024 * 1024, CpuTopology.parseSize("32M"));
		assertEquals(1024L, CpuTopology.parseSize("1024"));
		assertEquals(0L, CpuTopology.parseSize(""));
		assertEquals(0L, CpuTopology.parseSize("big"));
	}

	@Test
	void presetsUseL3GroupsWhenThereAreAtLeastTwo() {
		CpuTopology t = CpuTopology.of(List.of(new CpuTopology.CacheGroup(0xFFFFL, 96L << 20), new CpuTopology.CacheGroup(0xFFFF_0000L, 32L << 20)));
		assertEquals(List.of(0xFFFFL, 0xFFFF_0000L), t.presets(0xFFFF_FFFFL));
		assertEquals("L3 groups: 0-15 (96 MB), 16-31 (32 MB)", t.describe());
	}

	@Test
	void presetsFallBackToHalvesWithOneOrNoGroup() {
		CpuTopology single = CpuTopology.of(List.of(new CpuTopology.CacheGroup(0xFFFF_FFFFL, 0)));
		assertEquals(List.of(0xFFFFL, 0xFFFF_0000L), single.presets(0xFFFF_FFFFL));
		assertEquals(List.of(0xFFFFL, 0xFFFF_0000L), CpuTopology.of(List.of()).presets(0xFFFF_FFFFL));
		assertEquals(List.of(0b0011L, 0b1100L), CpuTopology.of(List.of()).presets(0b1111L));
		assertTrue(CpuTopology.of(List.of()).presets(0b1L).isEmpty());
		assertEquals("L3 cache layout: unknown", CpuTopology.of(List.of()).describe());
	}

	@Test
	void presetsIgnoreCpusTheMachineDoesNotHave() {
		CpuTopology t = CpuTopology.of(List.of(new CpuTopology.CacheGroup(0xFFL, 0), new CpuTopology.CacheGroup(0xFF00L, 0)));
		// A restricted machine with only CPUs 0-11: second group shrinks, both stay distinct.
		assertEquals(List.of(0xFFL, 0xF00L), t.presets(0xFFFL));
	}

	@Test
	void detectsSomethingOnLinux() {
		assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("linux"));
		CpuTopology t = CpuTopology.detect();
		// sysfs may hide cache info in containers; only assert consistency when present.
		for (CpuTopology.CacheGroup g : t.l3Groups()) {
			assertFalse(g.mask() == 0);
		}
	}
}
