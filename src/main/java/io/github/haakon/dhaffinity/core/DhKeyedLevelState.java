package io.github.haakon.dhaffinity.core;

/**
 * Mirror of Distant Horizons' "keyed client levels enabled" flag (set by a server that assigns
 * level keys; never in singleplayer). Kept by the keyed-level mixin so other hot-path caches can
 * read it with one volatile load instead of touching DH's locked structures.
 */
public final class DhKeyedLevelState {

	public static volatile boolean keyedLevelsEnabled;

	private DhKeyedLevelState() {}
}
