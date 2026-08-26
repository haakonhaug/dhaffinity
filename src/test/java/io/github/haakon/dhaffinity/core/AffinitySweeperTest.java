package io.github.haakon.dhaffinity.core;

import org.junit.jupiter.api.Test;

import static io.github.haakon.dhaffinity.core.TestConfigs.ALL;
import static io.github.haakon.dhaffinity.core.TestConfigs.DH;
import static io.github.haakon.dhaffinity.core.TestConfigs.GAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffinitySweeperTest {

	@Test
	void pinsNonDhThreadsToGameAndDhThreadsToDh() {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		os.addThread(11, ALL);
		os.addThread(20, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
		AffinitySweeper sweeper = core.createSweeperForTest();

		sweeper.sweepOnce();

		assertEquals(GAME, os.threads.get(10L));
		assertEquals(GAME, os.threads.get(11L));
		assertEquals(DH, os.threads.get(20L));
		AffinitySweeper.Stats st = sweeper.stats();
		assertEquals(1, st.sweeps());
		assertEquals(3, st.lastThreads());
		assertEquals(1, st.lastDhThreads());
		assertEquals(3, st.lastCorrected());
		assertEquals(0, st.lastFailed());
	}

	@Test
	void chunkGenerationWorkersGetTheirOwnMask() {
		FakeBackend os = new FakeBackend();
		os.addThread(30, ALL, "Worker-Main-3");
		os.addThread(31, ALL, "c2me-worker-17");
		os.addThread(32, ALL, "Chunk Render Task Executor #1");
		os.addThread(33, ALL, "IO-Worker-2");
		os.addThread(34, ALL, null); // name unknown -> plain game thread
		os.addThread(35, ALL, "Worker-Main-1"); // Linux-style truncated names still match by prefix
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		AffinitySweeper sweeper = core.createSweeperForTest();

		sweeper.sweepOnce();

		// Default: same CPUs as Distant Horizons.
		assertEquals(DH, os.threads.get(30L));
		assertEquals(DH, os.threads.get(31L));
		assertEquals(DH, os.threads.get(35L));
		assertEquals(GAME, os.threads.get(32L));
		assertEquals(GAME, os.threads.get(33L));
		assertEquals(GAME, os.threads.get(34L));
		assertEquals(3, sweeper.stats().lastChunkGenThreads());
		assertEquals(java.util.Map.of("Worker-Main", 2, "c2me-worker", 1), sweeper.lastChunkGenNames());
	}

	@Test
	void chunkGenerationMaskCanBeItsOwnAndCanBeDisabled() {
		FakeBackend os = new FakeBackend();
		os.addThread(30, ALL, "Worker-Main-3");
		os.addThread(31, ALL, "c2me-worker-1");
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> {
			j.chunkGenMask = "24-31";
			j.chunkGenThreadPatterns = java.util.List.of("Worker-Main-");
		}));
		core.createSweeperForTest().sweepOnce();
		assertEquals(0xFF00_0000L, os.threads.get(30L));
		assertEquals(GAME, os.threads.get(31L)); // pattern removed -> ordinary game thread

		FakeBackend os2 = new FakeBackend();
		os2.addThread(30, ALL, "Worker-Main-3");
		DhAffinity core2 = DhAffinity.createDetached(os2, TestConfigs.custom(j -> j.chunkGenThreadPatterns = java.util.List.of()));
		AffinitySweeper sweeper2 = core2.createSweeperForTest();
		sweeper2.sweepOnce();
		assertEquals(GAME, os2.threads.get(30L));
		assertEquals(0, sweeper2.stats().lastChunkGenThreads());
	}

	@Test
	void chunkGenerationIsNotAppliedWhenNonDhThreadsAreUnmanaged() {
		FakeBackend os = new FakeBackend();
		os.addThread(30, ALL, "Worker-Main-3");
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.manageNonDhThreads = false));
		core.createSweeperForTest().sweepOnce();
		assertEquals(ALL, os.threads.get(30L));
	}

	@Test
	void perPoolOverridesAndMainThreadMask() {
		FakeBackend os = new FakeBackend();
		os.addThread(1, ALL);   // main thread
		os.addThread(10, ALL);  // some game thread
		os.addThread(20, ALL);  // DH World Gen
		os.addThread(21, ALL);  // DH IO
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> {
			j.mainThreadMask = "0-3";
			j.dhPoolMasks.put("IO", "8-11");
		}));
		core.setMainThreadTidForTest(1);
		core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
		core.registry().register(21, "DH-IO Thread[0]", "IO");
		AffinitySweeper sweeper = core.createSweeperForTest();

		sweeper.sweepOnce();

		assertEquals(0xFL, os.threads.get(1L));
		assertEquals(GAME, os.threads.get(10L));
		assertEquals(DH, os.threads.get(20L));
		assertEquals(0xF00L, os.threads.get(21L));
	}

	@Test
	void reloadAppliesToRunningDhWorkersOnNextSweep() {
		FakeBackend os = new FakeBackend();
		os.addThread(20, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.enabled = false));
		core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
		AffinitySweeper sweeper = core.createSweeperForTest();
		sweeper.sweepOnce();
		assertEquals(ALL, os.threads.get(20L), "disabled: nothing touched");

		core.setConfigForTest(TestConfigs.split());
		sweeper.sweepOnce();
		assertEquals(DH, os.threads.get(20L), "enable via reload pins the already-running worker to DH, not to game");

		core.setConfigForTest(TestConfigs.custom(j -> j.dhMask = "24-31"));
		sweeper.sweepOnce();
		assertEquals(0xFF00_0000L, os.threads.get(20L));
	}

	@Test
	void steadyStateWritesNothing() {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		os.addThread(20, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		core.registry().register(20, "DH-IO Thread[0]", "IO");
		AffinitySweeper sweeper = core.createSweeperForTest();
		sweeper.sweepOnce();
		int writesAfterFirst = os.writes;

		sweeper.sweepOnce();
		sweeper.sweepOnce();

		assertEquals(writesAfterFirst, os.writes);
		assertEquals(0, sweeper.stats().lastCorrected());
		assertEquals(2, sweeper.stats().lastUnchanged());
	}

	@Test
	void reusedThreadIdAfterDhThreadExitsBecomesGameThread() {
		FakeBackend os = new FakeBackend();
		os.addThread(20, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		core.registry().register(20, "DH-LOD Builder Thread[0]", "LOD Builder");
		AffinitySweeper sweeper = core.createSweeperForTest();
		sweeper.sweepOnce();
		assertEquals(DH, os.threads.get(20L));

		// DH thread exits (unregisters); the OS hands the same id to an unrelated thread.
		core.registry().unregister(20);
		os.threads.remove(20L);
		os.addThread(20, ALL);
		sweeper.sweepOnce();

		assertEquals(GAME, os.threads.get(20L));
	}

	@Test
	void selfHealsAfterProcessMaskReset() {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		os.addThread(20, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
		AffinitySweeper sweeper = core.createSweeperForTest();
		sweeper.sweepOnce();

		// Process Lasso applies "javaw -> CCD0": process mask shrinks and every thread is reset.
		os.setProcessAffinity(GAME);
		assertEquals(GAME, os.threads.get(20L));

		sweeper.sweepOnce();

		assertEquals(ALL, os.processMask);
		assertEquals(GAME, os.threads.get(10L));
		assertEquals(DH, os.threads.get(20L));
		assertEquals(1, sweeper.stats().processMaskResets());
	}

	@Test
	void failedThreadIsRetriedWithBackoffSoAReusedIdIsNotStuck() {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		os.addThread(11, ALL);
		os.failing.add(11L);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		AffinitySweeper sweeper = core.createSweeperForTest();

		sweeper.sweepOnce();
		assertEquals(1, sweeper.stats().lastFailed());
		sweeper.sweepOnce();
		assertEquals(0, sweeper.stats().lastFailed(), "not retried immediately");
		assertEquals(1, sweeper.stats().lastSkipped());

		// The failing thread exits and a fresh thread reuses id 11 within the same interval.
		os.threads.remove(11L);
		os.failing.clear();
		os.addThread(11, ALL);
		for (int i = 0; i < 8; i++) {
			sweeper.sweepOnce();
		}
		assertEquals(GAME, os.threads.get(11L), "retried after the backoff and pinned");
		assertEquals(0, sweeper.stats().lastSkipped());
	}

	@Test
	void registryOnlyModeDoesNotEnumerateAndPinsOnlyDhThreads() {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		os.addThread(20, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.manageNonDhThreads = false));
		core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
		AffinitySweeper sweeper = core.createSweeperForTest();

		sweeper.sweepOnce();

		assertEquals(0, os.enumerations);
		assertEquals(ALL, os.threads.get(10L));
		assertEquals(DH, os.threads.get(20L));
		assertEquals(1, sweeper.stats().lastThreads());

		// A registered worker that vanished is dropped from the registry in this mode.
		os.threads.remove(20L);
		sweeper.sweepOnce();
		assertFalse(core.registry().isDh(20));
	}

	@Test
	void registryOnlyModeUsesOneSnapshotAndNeverReclassifiesAnExitedWorker() {
		FakeBackend os = new FakeBackend();
		os.addThread(20, ALL);
		os.addThread(21, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.manageNonDhThreads = false));
		core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
		core.registry().register(21, "DH-IO Thread[0]", "IO");
		AffinitySweeper sweeper = core.createSweeperForTest();
		// Worker 21 exits (unregisters) while the sweep is handling worker 20: it must still be treated
		// as the DH worker the snapshot saw, never as a "game" thread.
		os.beforeReconcile = () -> core.registry().unregister(21);

		sweeper.sweepOnce();

		assertEquals(DH, os.threads.get(20L));
		assertEquals(DH, os.threads.get(21L), "snapshot classification, not gameMask");
		assertEquals(0, os.enumerations);
	}

	@Test
	void sweeperPinsItselfEvenWhenNotManagingOtherThreads() throws Exception {
		FakeBackend os = new FakeBackend();
		os.currentTid = 77;
		os.addThread(77, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> {
			j.manageNonDhThreads = false;
			j.startupSweepMs = 50;
		}));
		AffinitySweeper sweeper = core.createSweeperForTest();
		sweeper.start();
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (sweeper.stats().sweeps() < 1 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		sweeper.stop();
		assertEquals(GAME, os.threads.get(77L));
	}

	@Test
	void lateRegistrationDuringSweepIsCorrectedInTheSameSweep() {
		FakeBackend os = new FakeBackend();
		os.addThread(20, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		AffinitySweeper sweeper = core.createSweeperForTest();
		// The worker registers after the sweeper decided it was a game thread but before the write lands.
		os.beforeReconcile = () -> {
			if (!core.registry().isDh(20)) {
				core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
			}
		};

		sweeper.sweepOnce();

		assertEquals(DH, os.threads.get(20L));
	}

	@Test
	void disabledConfigDoesNothing() {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.enabled = false));
		AffinitySweeper sweeper = core.createSweeperForTest();

		sweeper.sweepOnce();

		assertEquals(ALL, os.threads.get(10L));
		assertEquals(0, sweeper.stats().sweeps());
	}

	@Test
	void intervalSwitchesFromStartupToSteady() {
		FakeBackend os = new FakeBackend();
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> {
			j.startupSweepMs = 100;
			j.startupWindowMs = 0;
			j.steadySweepMs = 5000;
		}));
		AffinitySweeper sweeper = core.createSweeperForTest();
		assertEquals(5000, sweeper.currentIntervalMs());

		core.setConfigForTest(TestConfigs.custom(j -> {
			j.startupSweepMs = 100;
			j.startupWindowMs = 60_000;
			j.steadySweepMs = 5000;
		}));
		assertEquals(100, sweeper.currentIntervalMs());
	}

	@Test
	void maxDurationExcludesTheColdFirstSweep() {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		AffinitySweeper sweeper = core.createSweeperForTest();
		os.beforeReconcile = () -> {
			try {
				Thread.sleep(5);
			} catch (InterruptedException ignored) {
			}
		};
		sweeper.sweepOnce();
		assertTrue(sweeper.stats().lastDurationMicros() >= 5_000);
		assertEquals(0, sweeper.stats().maxDurationMicros(), "cold first sweep is excluded from the max");
		os.threads.put(10L, ALL); // force another write (and the 5 ms delay) in sweep #2
		sweeper.sweepOnce();
		assertTrue(sweeper.stats().maxDurationMicros() >= 5_000, "second sweep is recorded");
		assertEquals(sweeper.stats().lastDurationMicros(), sweeper.stats().maxDurationMicros());
	}

	@Test
	void configChangeResetsFailureBackoff() {
		FakeBackend os = new FakeBackend();
		os.addThread(11, ALL);
		os.failing.add(11L);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		AffinitySweeper sweeper = core.createSweeperForTest();
		sweeper.sweepOnce();
		assertEquals(1, sweeper.stats().lastFailed());
		sweeper.sweepOnce();
		assertEquals(1, sweeper.stats().lastSkipped(), "in backoff");

		// User saves a new config: the thread must be offered the new mask on the very next sweep.
		os.failing.clear();
		core.setConfigForTest(TestConfigs.custom(j -> j.gameMask = "0-7"));
		sweeper.configChanged();
		sweeper.sweepOnce();
		assertEquals(0xFFL, os.threads.get(11L));
		assertEquals(0, sweeper.stats().lastSkipped());
	}

	@Test
	void emptyEnumerationStillManagesDhWorkers() {
		FakeBackend os = new FakeBackend();
		os.addThread(20, ALL);
		os.emptyEnumeration = true;
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		core.registry().register(20, "DH-World Gen Thread[0]", "World Gen");
		AffinitySweeper sweeper = core.createSweeperForTest();

		sweeper.sweepOnce();

		assertEquals(DH, os.threads.get(20L));
	}

	@Test
	void backgroundThreadRunsAndStops() throws Exception {
		FakeBackend os = new FakeBackend();
		os.addThread(10, ALL);
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.custom(j -> j.startupSweepMs = 50));
		AffinitySweeper sweeper = core.createSweeperForTest();
		sweeper.start();
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (sweeper.stats().sweeps() < 2 && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertTrue(sweeper.stats().sweeps() >= 2, "sweeper should run repeatedly");
		assertTrue(sweeper.isAlive());
		sweeper.stop();
		deadline = System.nanoTime() + 2_000_000_000L;
		while (sweeper.isAlive() && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertFalse(sweeper.isAlive(), "sweeper should stop promptly");
	}
}
