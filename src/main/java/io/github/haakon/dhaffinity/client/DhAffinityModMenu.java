package io.github.haakon.dhaffinity.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.haakon.dhaffinity.client.gui.AffinityConfigScreen;

/**
 * ModMenu integration ("Configure" button). ModMenu is optional: this class is only loaded when
 * ModMenu itself queries the {@code modmenu} entrypoint, so its absence at runtime is harmless.
 */
public final class DhAffinityModMenu implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AffinityConfigScreen::new;
	}
}
