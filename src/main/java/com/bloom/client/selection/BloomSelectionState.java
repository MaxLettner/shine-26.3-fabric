package com.bloom.client.selection;

/**
 * Marks the current thread as compiling a chunk section, so light encoding only touches
 * terrain meshes and never block models rendered elsewhere (falling blocks, items, ...).
 */
public final class BloomSelectionState {
	private static final ThreadLocal<Boolean> COMPILING_SECTION = ThreadLocal.withInitial(() -> false);

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
}
