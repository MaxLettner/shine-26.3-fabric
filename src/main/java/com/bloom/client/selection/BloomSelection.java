package com.bloom.client.selection;

import com.bloom.client.config.BloomConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Resolves the bloom source encoding for block and fluid states. Meshing asks for this per quad on
 * worker threads, so results are cached per state (states are identity-hashed singletons) and the
 * caches are swapped out whenever the config changes.
 */
public final class BloomSelection {
	private static final Identifier LAVA = Identifier.parse("minecraft:lava");
	private static final Identifier FLOWING_LAVA = Identifier.parse("minecraft:flowing_lava");

	private static volatile Map<BlockState, Integer> blockEncodings = new ConcurrentHashMap<>();
	private static volatile Map<FluidState, Integer> fluidEncodings = new ConcurrentHashMap<>();

	private BloomSelection() {
	}

	public static void invalidate() {
		blockEncodings = new ConcurrentHashMap<>();
		fluidEncodings = new ConcurrentHashMap<>();
	}

	public static int getBlockEncoding(BlockState blockState) {
		Map<BlockState, Integer> cache = blockEncodings;
		Integer encoding = cache.get(blockState);
		if (encoding == null) {
			encoding = BloomSourceEncoding.encode(getBlockSourceStrength(blockState));
			cache.put(blockState, encoding);
		}
		return encoding;
	}

	public static int getFluidEncoding(FluidState fluidState) {
		Map<FluidState, Integer> cache = fluidEncodings;
		Integer encoding = cache.get(fluidState);
		if (encoding == null) {
			encoding = BloomSourceEncoding.encode(getFluidSourceStrength(fluidState));
			cache.put(fluidState, encoding);
		}
		return encoding;
	}

	private static double getBlockSourceStrength(BlockState blockState) {
		BloomConfig.Data config = BloomConfig.get();
		Double override = config.blockStrengthOverrides.get(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString());
		if (override != null) {
			return override;
		}
		return blockState.getLightEmission() > 0 ? config.defaultLightSourceStrength : config.defaultNonLightStrength;
	}

	private static double getFluidSourceStrength(FluidState fluidState) {
		if (fluidState.isEmpty()) {
			return 0.0;
		}

		BloomConfig.Data config = BloomConfig.get();
		Identifier fluidId = BuiltInRegistries.FLUID.getKey(fluidState.getType());
		Double override = config.blockStrengthOverrides.get(fluidId.toString());
		if (override != null) {
			return override;
		}

		Identifier legacyBlockId = BuiltInRegistries.BLOCK.getKey(fluidState.createLegacyBlock().getBlock());
		Double legacyOverride = config.blockStrengthOverrides.get(legacyBlockId.toString());
		if (legacyOverride != null) {
			return legacyOverride;
		}

		boolean lava = fluidId.equals(LAVA) || fluidId.equals(FLOWING_LAVA);
		return lava ? config.defaultLightSourceStrength : config.defaultNonLightStrength;
	}
}
