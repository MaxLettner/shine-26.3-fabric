package com.bloom.client.selection;

import com.mojang.blaze3d.vertex.QuadInstance;

/**
 * Copy of a {@link QuadInstance} whose emitted light coordinates carry an encoded bloom source strength.
 * Encoding happens after emission is applied, since emission repacks light and clears the low bits.
 */
public final class EncodingQuadInstance extends QuadInstance {
	private int encoding;

	public EncodingQuadInstance copyFrom(QuadInstance source, int encoding) {
		for (int vertex = 0; vertex < 4; vertex++) {
			this.setColor(vertex, source.getColor(vertex));
			this.setLightCoords(vertex, source.getLightCoords(vertex));
		}
		this.setOverlayCoords(source.overlayCoords());
		this.encoding = encoding;
		return this;
	}

	@Override
	public int getLightCoordsWithEmission(int vertex, int lightEmission) {
		return BloomSourceEncoding.applyToLight(super.getLightCoordsWithEmission(vertex, lightEmission), this.encoding);
	}
}
