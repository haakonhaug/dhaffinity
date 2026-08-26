package io.github.haakon.dhaffinity.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DhPoolsTest {

	@Test
	void poolNameExtraction() {
		assertEquals("World Gen", DhPools.poolName("DH-World Gen Thread[12]"));
		assertEquals("Task Picker Re-queue Schedule", DhPools.poolName("DH-Task Picker Re-queue Schedule Thread[0]"));
		assertEquals("custom", DhPools.poolName("custom"));
		assertEquals("", DhPools.poolName(null));
	}

	@Test
	void configuredThreadCountIsUnavailableWithoutDh() {
		assertEquals(-1, DhPools.configuredThreadCount());
	}
}
