package io.github.haakon.dhaffinity.core;

import io.github.haakon.dhaffinity.config.AffinityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhAffinityTest {

	@Test
	void hookStateRecordedBeforeInitializeSurvivesTheInstanceSwap(@TempDir Path dir) throws Exception {
		// The mixin plugin reports before preLaunch; initialize() must not lose it.
		Files.writeString(dir.resolve(AffinityConfig.FILE_NAME), "{ \"enabled\": false }");
		DhAffinity.setHookState(DhAffinity.HookState.TARGET_CLASS_MISSING);
		DhAffinity before = DhAffinity.get();

		DhAffinity after = DhAffinity.initialize(dir);

		assertNotEquals(before, after);
		assertEquals(DhAffinity.HookState.TARGET_CLASS_MISSING, DhAffinity.hookState());
		List<String> status = after.statusLines();
		assertTrue(status.stream().anyMatch(l -> l.contains("INACTIVE — a DH pool class was not found")), status.toString());
		assertEquals(after, DhAffinity.initialize(dir), "second initialize is a no-op");
		DhAffinity.setHookState(DhAffinity.HookState.NOT_APPLIED);
	}

	@Test
	void statusReportsUnknownProcessMaskInsteadOfOk() {
		FakeBackend os = new FakeBackend();
		os.processMask = 0;
		DhAffinity core = DhAffinity.createDetached(os, TestConfigs.split());
		assertTrue(core.statusLines().stream().anyMatch(l -> l.startsWith("Process affinity: unknown")), core.statusLines().toString());
	}
}
