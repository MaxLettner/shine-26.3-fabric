package com.bloom.client;

import com.bloom.BloomMod;
import com.bloom.client.config.BloomConfig;
import com.bloom.client.render.BloomPostProcessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class BloomClient implements ClientModInitializer {
	private static final KeyMapping TOGGLE_BLOOM_KEY = KeyMappingHelper.registerKeyMapping(
		new KeyMapping(
			"key.shine.toggle",
			InputConstants.Type.KEYBOARD,
			InputConstants.KEY_B,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "main"))
		)
	);

	@Override
	public void onInitializeClient() {
		BloomConfig.load();

		// Rendering hooks live in LevelRendererMixin; the post chain runs between opaque and translucent geometry.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_BLOOM_KEY.consumeClick()) {
				boolean enabled = BloomPostProcessor.toggleFromKeybind();
				BloomMod.LOGGER.info("Shine post-processing {}", enabled ? "enabled" : "disabled");
			}
		});
	}
}
