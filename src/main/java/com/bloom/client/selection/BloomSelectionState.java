package com.bloom.client.selection;

/**
 * Marks the current thread as compiling a chunk section, so light encoding only touches
 * terrain meshes and never block models rendered elsewhere (falling blocks, items, ...).
 */
public final class BloomSelectionState {
	private static final ThreadLocal<Boolean> COMPILING_SECTION = ThreadLocal.withInitial(() -> false);
	private static final ThreadLocal<Double> SODIUM_SOURCE_STRENGTH = ThreadLocal.withInitial(() -> 0.0);

	private BloomSelectionState() {
	}

	public static void beginSectionCompile() {
		COMPILING_SECTION.set(true);
	}

	public static void endSectionCompile() {
		COMPILING_SECTION.set(false);
	}

	public static boolean isCompilingSection() {
		return COMPILING_SECTION.get();
	}

	/** Sodium meshing: strength of the block or fluid currently being rendered on this thread. */
	public static void setSodiumSourceStrength(double strength) {
		SODIUM_SOURCE_STRENGTH.set(strength);
	}

	public static double getSodiumSourceStrength() {
		return SODIUM_SOURCE_STRENGTH.get();
	}
}
