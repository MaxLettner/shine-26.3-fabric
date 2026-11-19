package com.bloom.client.selection;

import com.bloom.client.config.BloomConfig;

/**
 * Packs a bloom source strength into the otherwise unused low nibbles of the block-light and
 * sky-light lightmap coordinates. Decoded by {@code shine:core/terrain_bloom_source.vsh}.
 */
public final class BloomSourceEncoding {
	private static final int LEGACY_BITS_PER_AXIS = 3;
	private static final int LEGACY_AXIS_MASK = (1 << LEGACY_BITS_PER_AXIS) - 1;
	private static final int LEGACY_MAX = (1 << (LEGACY_BITS_PER_AXIS * 2)) - 1;
	private static final int LOW_NIBBLE_MASK = 0xF;
	private static final int SKY_LOW_NIBBLE_SHIFT = 16;
	private static final int EXTENDED_MODE_MAX = 3;
	private static final int EXTENDED_STEPS_PER_MODE = LEGACY_MAX + 1;
	private static final int EXTENDED_TOTAL_STEPS = EXTENDED_MODE_MAX * EXTENDED_STEPS_PER_MODE;

	private BloomSourceEncoding() {
	}

	public static int encodePackedLight(int packedLight, double sourceStrength) {
		double clamped = Math.max(BloomConfig.MIN_SOURCE_STRENGTH, Math.min(BloomConfig.MAX_SOURCE_STRENGTH, sourceStrength));
		int low6;
		int mode;

		if (clamped <= 100.0) {
			// Legacy range [0, 100]: 6 bits, mode 0.
			low6 = (int) Math.round((clamped / 100.0) * LEGACY_MAX);
			mode = 0;
		} else {
			// Extended range (100, 500]: 192 extra steps using two mode bits + 6 legacy bits.
			double t = (clamped - 100.0) / (BloomConfig.MAX_SOURCE_STRENGTH - 100.0);
			int code = 1 + (int) Math.ceil(t * (EXTENDED_TOTAL_STEPS - 1));
			mode = ((code - 1) / EXTENDED_STEPS_PER_MODE) + 1;
			low6 = (code - 1) % EXTENDED_STEPS_PER_MODE;
		}

		int blockNibble = (low6 & LEGACY_AXIS_MASK) | ((mode & 1) << 3);
		int skyNibble = ((low6 >>> LEGACY_BITS_PER_AXIS) & LEGACY_AXIS_MASK) | (((mode >>> 1) & 1) << 3);
		int cleared = packedLight & ~LOW_NIBBLE_MASK & ~(LOW_NIBBLE_MASK << SKY_LOW_NIBBLE_SHIFT);
		return cleared | blockNibble | (skyNibble << SKY_LOW_NIBBLE_SHIFT);
	}
}
