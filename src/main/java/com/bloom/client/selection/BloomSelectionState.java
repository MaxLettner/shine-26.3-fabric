package com.bloom.client.selection;

/**
 * Per-thread meshing state, held in one mutable object so each lookup is a single ThreadLocal access
 * without boxing.
 */
public final class BloomSelectionState {
	private static final ThreadLocal<BloomSelectionState> CURRENT = ThreadLocal.withInitial(BloomSelectionState::new);

	/** True while this thread runs vanilla's SectionCompiler.compile (vanilla/Indigo meshing). */
	public boolean compilingSection;
	/** Encoding of the block or fluid Sodium is currently meshing on this thread. */
	public int sodiumEncoding;

	private BloomSelectionState() {
	}

	public static BloomSelectionState get() {
		return CURRENT.get();
	}
}
