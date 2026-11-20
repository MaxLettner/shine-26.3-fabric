package com.bloom.client.selection;

import com.bloom.client.config.BloomConfig;

/**
 * Bloom source strength encodings. {@link #encode} is computed once per block/fluid state (see
 * {@link BloomSelection}) and packs both mesh encodings into one int:
 * <ul>
 *   <li>bits 0-3 and 16-19: the low nibbles of the block-light and sky-light lightmap coordinates
 *       (vanilla/Indigo meshes, decoded by {@code shine:core/terrain_bloom_source.vsh});</li>
 *   <li>bits 24-28: a 5-bit source code for Sodium's per-vertex material byte, bits 3-7
 *       (decoded by {@code shine:sodium/block_layer_bloom.vsh}).</li>
 * </ul>
 * Applying an encoding to a vertex is then a single mask-and-or.
 */
public final class BloomSourceEncoding {
	private static final int LIGHT_LOW_NIBBLES_MASK = 0xF | (0xF << 16);
	private static final int MATERIAL_CODE_SHIFT = 24;
	private static final int MATERIAL_CODE_MASK = 0x1F;
	private static final int MATERIAL_SOURCE_SHIFT = 3;
	private static final int MATERIAL_SOURCE_BITS_MASK = MATERIAL_CODE_MASK << MATERIAL_SOURCE_SHIFT;

	// Lightmap encoding: 6 "legacy" bits for [0, 100] plus 2 mode bits for 192 extra steps up to 500.
	private static final int LEGACY_MAX = 63;
	private static final int EXTENDED_STEPS_PER_MODE = LEGACY_MAX + 1;
	private static final int EXTENDED_TOTAL_STEPS = 3 * EXTENDED_STEPS_PER_MODE;

	// Material encoding: codes 0-20 cover [0, 100] in steps of 5, codes 21-31 cover (100, 500].
	private static final int MATERIAL_LEGACY_CODE_MAX = 20;
	private static final double MATERIAL_LEGACY_STEP = 5.0;
	private static final double MATERIAL_EXTENDED_STEP = (BloomConfig.MAX_SOURCE_STRENGTH - 100.0) / (MATERIAL_CODE_MASK - MATERIAL_LEGACY_CODE_MAX);

	private BloomSourceEncoding() {
	}

	public static int encode(double sourceStrength) {
		double clamped = Math.max(BloomConfig.MIN_SOURCE_STRENGTH, Math.min(BloomConfig.MAX_SOURCE_STRENGTH, sourceStrength));
		return lightBits(clamped) | (materialCode(clamped) << MATERIAL_CODE_SHIFT);
	}

	public static int applyToLight(int packedLight, int encoding) {
		return (packedLight & ~LIGHT_LOW_NIBBLES_MASK) | (encoding & LIGHT_LOW_NIBBLES_MASK);
	}

	public static int applyToMaterial(int materialBits, int encoding) {
		return (materialBits & ~MATERIAL_SOURCE_BITS_MASK) | (((encoding >>> MATERIAL_CODE_SHIFT) & MATERIAL_CODE_MASK) << MATERIAL_SOURCE_SHIFT);
	}

	private static int lightBits(double clamped) {
		int low6;
		int mode;
		if (clamped <= 100.0) {
			low6 = (int) Math.round((clamped / 100.0) * LEGACY_MAX);
			mode = 0;
		} else {
			double t = (clamped - 100.0) / (BloomConfig.MAX_SOURCE_STRENGTH - 100.0);
			int code = 1 + (int) Math.ceil(t * (EXTENDED_TOTAL_STEPS - 1));
			mode = ((code - 1) / EXTENDED_STEPS_PER_MODE) + 1;
			low6 = (code - 1) % EXTENDED_STEPS_PER_MODE;
		}

		int blockNibble = (low6 & 0x7) | ((mode & 1) << 3);
		int skyNibble = ((low6 >>> 3) & 0x7) | (((mode >>> 1) & 1) << 3);
		return blockNibble | (skyNibble << 16);
	}

	private static int materialCode(double clamped) {
		if (clamped <= 100.0) {
			return Math.clamp(Math.round(clamped / MATERIAL_LEGACY_STEP), 0, MATERIAL_LEGACY_CODE_MAX);
		}
		return Math.clamp(MATERIAL_LEGACY_CODE_MAX + Math.round((clamped - 100.0) / MATERIAL_EXTENDED_STEP), MATERIAL_LEGACY_CODE_MAX, MATERIAL_CODE_MASK);
	}
}
