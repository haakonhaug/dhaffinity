package io.github.haakon.dhaffinity.core;

import io.github.haakon.dhaffinity.config.AffinityConfig;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

final class TestConfigs {

	static final long ALL = 0xFFFF_FFFFL;
	static final long GAME = 0x0000_FFFFL;
	static final long DH = 0xFFFF_0000L;

	private TestConfigs() {}

	/** 9950X3D-style split: game 0-15, DH 16-31. */
	static AffinityConfig split() {
		return custom(j -> {});
	}

	static AffinityConfig custom(Consumer<AffinityConfig.Json> edit) {
		AffinityConfig.Json json = new AffinityConfig.Json();
		json.gameMask = "0-15";
		json.dhMask = "16-31";
		edit.accept(json);
		return AffinityConfig.resolve(json, ALL, LoggerFactory.getLogger("test"));
	}
}
