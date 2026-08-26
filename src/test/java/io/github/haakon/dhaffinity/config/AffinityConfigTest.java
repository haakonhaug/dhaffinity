package io.github.haakon.dhaffinity.config;

import io.github.haakon.dhaffinity.affinity.CpuTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffinityConfigTest {

	private static final Logger LOG = LoggerFactory.getLogger("test");
	private static final long ALL32 = 0xFFFF_FFFFL;
	private static final CpuTopology X3D = CpuTopology.of(List.of(
			new CpuTopology.CacheGroup(0xFFFFL, 96L << 20), new CpuTopology.CacheGroup(0xFFFF_0000L, 32L << 20)));

	@Test
	void defaultsSplitByL3AndPutTheGameOnTheBiggerCache() {
		AffinityConfig.Json json = AffinityConfig.defaultsFor(ALL32, X3D);
		assertEquals("0-15", json.gameMask);
		assertEquals("16-31", json.dhMask);

		// Reversed order/size: the bigger cache still wins the game.
		CpuTopology reversed = CpuTopology.of(List.of(new CpuTopology.CacheGroup(0xFFFFL, 32L << 20), new CpuTopology.CacheGroup(0xFFFF_0000L, 96L << 20)));
		AffinityConfig.Json r = AffinityConfig.defaultsFor(ALL32, reversed);
		assertEquals("16-31", r.gameMask);
		assertEquals("0-15", r.dhMask);

		// Three groups: the others are merged into DH.
		CpuTopology three = CpuTopology.of(List.of(new CpuTopology.CacheGroup(0xFFL, 96L << 20), new CpuTopology.CacheGroup(0xFF00L, 32L << 20), new CpuTopology.CacheGroup(0xFF_0000L, 32L << 20)));
		assertEquals("8-23", AffinityConfig.defaultsFor(0xFF_FFFFL, three).dhMask);
	}

	@Test
	void defaultsWithoutTopologyDoNotSplit() {
		AffinityConfig.Json json = AffinityConfig.defaultsFor(0xFFFFL, CpuTopology.of(List.of()));
		assertEquals("0-15", json.gameMask);
		assertEquals("0-15", json.dhMask);
		AffinityConfig cfg = AffinityConfig.resolve(json, 0xFFFFL, LOG);
		assertEquals(cfg.gameMask, cfg.dhMask);
		assertTrue(cfg.warnings.isEmpty(), cfg.warnings.toString());
	}

	@Test
	void createsDefaultFileWhenMissing(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("dhaffinity.json");
		AffinityConfig.Json json = AffinityConfig.readOrCreate(file, LOG, () -> AffinityConfig.defaultsFor(ALL32, X3D));
		assertTrue(Files.exists(file));
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"dhMask\": \"16-31\""), text);
		assertTrue(text.contains("\"gameMask\": \"0-15\""), text);
		assertTrue(text.contains("\"dhPoolMasks\": {}"), text);
		AffinityConfig cfg = AffinityConfig.resolve(json, ALL32, LOG);
		assertTrue(cfg.enabled);
		assertEquals(0xFFFF_0000L, cfg.dhMask);
		assertEquals(0x0000_FFFFL, cfg.gameMask);
		assertEquals(cfg.gameMask, cfg.mainThreadMask);
		assertEquals(250, cfg.startupSweepMs);
		assertEquals(30000, cfg.startupWindowMs);
		assertEquals(2000, cfg.steadySweepMs);
		assertTrue(cfg.capVanillaWorkerThreads);
		assertTrue(cfg.warnings.isEmpty(), cfg.warnings.toString());
	}

	@Test
	void readsUserValuesAndKeepsDefaultsForMissingKeys(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("dhaffinity.json");
		Files.writeString(file, "{ \"dhMask\": \"8-15\", \"gameMask\": \"0x00FF\", \"mainThreadMask\": \"0\", \"dhPoolMasks\": { \"World Gen\": \"12-15\", \"IO\": \"\" }, \"logPins\": true }");
		AffinityConfig cfg = AffinityConfig.resolve(AffinityConfig.readOrCreate(file, LOG, AffinityConfig.Json::new), 0xFFFFL, LOG);
		assertEquals(0xFF00L, cfg.dhMask);
		assertEquals(0x00FFL, cfg.gameMask);
		assertEquals(1L, cfg.mainThreadMask);
		assertEquals(0xF000L, cfg.maskForDhPool("World Gen"));
		assertEquals(0xFF00L, cfg.maskForDhPool("IO"), "empty override follows dhMask");
		assertEquals(0xFF00L, cfg.maskForDhPool("Render Loader"));
		assertEquals(1, cfg.dhPoolMasks.size());
		assertTrue(cfg.logPins);
		assertTrue(cfg.manageNonDhThreads);
		assertEquals(2000, cfg.steadySweepMs);
		assertTrue(cfg.warnings.isEmpty(), cfg.warnings.toString());
	}

	@Test
	void emptyMasksMeanAllCpus() {
		AffinityConfig cfg = AffinityConfig.resolve(new AffinityConfig.Json(), 0xFFL, LOG);
		assertEquals(0xFFL, cfg.gameMask);
		assertEquals(0xFFL, cfg.dhMask);
		assertEquals(0xFFL, cfg.mainThreadMask);
		assertTrue(cfg.warnings.isEmpty());
	}

	@Test
	void overlapIsAllowedWithoutWarning() {
		AffinityConfig.Json json = new AffinityConfig.Json();
		json.gameMask = "0-15";
		json.dhMask = "8-31";
		AffinityConfig cfg = AffinityConfig.resolve(json, ALL32, LOG);
		assertTrue(cfg.warnings.isEmpty(), cfg.warnings.toString());
		assertEquals(0xFFFF_FF00L, cfg.dhMask);
	}

	@Test
	void malformedFileIsLeftAloneAndDefaultsUsed(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("dhaffinity.json");
		Files.writeString(file, "{ this is not json");
		AffinityConfig.Json json = AffinityConfig.readOrCreate(file, LOG, () -> AffinityConfig.defaultsFor(ALL32, X3D));
		assertEquals("{ this is not json", Files.readString(file));
		assertEquals("16-31", json.dhMask);
	}

	@Test
	void nullPoolMapInFileIsTolerated(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("dhaffinity.json");
		Files.writeString(file, "{ \"dhPoolMasks\": null }");
		AffinityConfig.Json json = AffinityConfig.readOrCreate(file, LOG, AffinityConfig.Json::new);
		assertTrue(json.dhPoolMasks.isEmpty());
	}

	@Test
	void masksEntirelyOutsideTheMachineFallBackToAllCpus() {
		AffinityConfig.Json json = AffinityConfig.defaultsFor(ALL32, X3D);
		AffinityConfig cfg = AffinityConfig.resolve(json, 0xFFFFL, LOG); // same file moved to a 16-CPU machine
		assertEquals(0xFFFFL, cfg.dhMask, "dhMask entirely outside the machine becomes all CPUs, never 'no CPU'");
		assertEquals(0xFFFFL, cfg.gameMask);
		assertTrue(cfg.dhPinningActive());
		assertEquals(2, cfg.warnings.size(), cfg.warnings.toString());
		assertTrue(cfg.warnings.get(1).contains("using all CPUs"), cfg.warnings.get(1));

		json.dhPoolMasks.put("World Gen", "0-3");
		AffinityConfig withPool = AffinityConfig.resolve(json, 0xFFFFL, LOG);
		assertEquals(0xFL, withPool.maskForDhPool("World Gen"));
	}

	@Test
	void invalidValuesFallBackWithWarnings() {
		AffinityConfig.Json json = new AffinityConfig.Json();
		json.gameMask = "0-15";
		json.dhMask = "sixteen to thirty-one";
		json.dhPoolMasks.put("IO", "nope");
		json.steadySweepMs = 1;
		json.startupWindowMs = -5;
		AffinityConfig cfg = AffinityConfig.resolve(json, ALL32, LOG);
		assertEquals(ALL32, cfg.dhMask, "unparsable dhMask falls back to all CPUs");
		assertEquals(ALL32, cfg.maskForDhPool("IO"), "unparsable pool falls back to dhMask");
		assertEquals(AffinityConfig.MIN_SWEEP_MS, cfg.steadySweepMs);
		assertEquals(0, cfg.startupWindowMs);
		assertEquals(4, cfg.warnings.size(), cfg.warnings.toString());
	}

	@Test
	void writeRoundTrips(@TempDir Path dir) throws Exception {
		AffinityConfig.Json json = AffinityConfig.defaultsFor(ALL32, X3D);
		json.dhPoolMasks.put("World Gen", "16-23");
		json.mainThreadMask = "0-1";
		Path file = dir.resolve("dhaffinity.json");
		AffinityConfig.write(file, json);
		AffinityConfig.Json back = AffinityConfig.readOrCreate(file, LOG, AffinityConfig.Json::new);
		assertEquals("16-23", back.dhPoolMasks.get("World Gen"));
		assertEquals("0-1", back.mainThreadMask);
		AffinityConfig.Json copy = back.copy();
		copy.dhPoolMasks.put("IO", "24-31");
		assertFalse(back.dhPoolMasks.containsKey("IO"), "copy is deep for the pool map");
	}

	@Test
	void gpuUploadAndBudgetKeys() {
		AffinityConfig.Json json = new AffinityConfig.Json();
		assertTrue(AffinityConfig.resolve(json, ALL32, LOG).offThreadGpuUpload, "default on");
		assertEquals(0, AffinityConfig.resolve(json, ALL32, LOG).renderThreadTaskBudgetMs);
		json.offThreadGpuUpload = false;
		json.renderThreadTaskBudgetMs = 3;
		AffinityConfig cfg = AffinityConfig.resolve(json, ALL32, LOG);
		assertFalse(cfg.offThreadGpuUpload);
		assertEquals(3, cfg.renderThreadTaskBudgetMs);
		json.renderThreadTaskBudgetMs = 999;
		AffinityConfig clamped = AffinityConfig.resolve(json, ALL32, LOG);
		assertEquals(0, clamped.renderThreadTaskBudgetMs);
		assertEquals(1, clamped.warnings.size(), clamped.warnings.toString());
		AffinityConfig.Json copy = json.copy();
		assertFalse(copy.offThreadGpuUpload);
		assertEquals(999, copy.renderThreadTaskBudgetMs);
	}

	@Test
	void disabledInstanceIsInert() {
		AffinityConfig cfg = AffinityConfig.disabled();
		assertFalse(cfg.enabled);
		assertEquals(0, cfg.dhMask);
		assertFalse(cfg.dhPinningActive());
	}
}
