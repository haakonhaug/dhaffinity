package io.github.haakon.dhaffinity;

import io.github.haakon.dhaffinity.core.DhAffinity;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs before Minecraft's main class is loaded, i.e. before the game spawns its thread pools, so
 * the sweeper is already in place when they appear.
 */
public final class DhAffinityPreLaunch implements PreLaunchEntrypoint {

	@Override
	public void onPreLaunch() {
		try {
			FabricLoader loader = FabricLoader.getInstance();
			loader.getModContainer("distanthorizons").ifPresent(dh ->
					DhAffinity.LOG.info("DH Affinity: found Distant Horizons {}.", dh.getMetadata().getVersion().getFriendlyString()));
			DhAffinity.initialize(loader.getConfigDir());
		} catch (Throwable t) {
			DhAffinity.LOG.error("DH Affinity failed to initialise; the mod is inactive.", t);
		}
	}
}
