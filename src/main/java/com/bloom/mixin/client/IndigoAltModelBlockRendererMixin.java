package com.bloom.mixin.client;

import com.bloom.client.selection.BloomSelection;
import com.bloom.client.selection.BloomSelectionState;
import com.bloom.client.selection.BloomSourceEncoding;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With Fabric API installed, chunk meshing goes through Indigo's AltModelBlockRenderer instead of
 * vanilla's ModelBlockRenderer. Encode after Indigo has finished shading (light + AO + emission).
 * The encoding is resolved once per block state, not per quad.
 */
@Mixin(value = AltModelBlockRendererImpl.class, remap = false)
public abstract class IndigoAltModelBlockRendererMixin {
	@Shadow
	private BlockState blockState;

	@Unique
	private BlockState bloom$encodedState;
	@Unique
	private int bloom$encoding;

	@Inject(method = "shadeQuad", at = @At("TAIL"), require = 0)
	private void bloom$encodeSourceStrength(MutableQuadViewImpl quad, boolean ao, boolean emissive, boolean vanillaShade, CallbackInfo ci) {
		BlockState state = this.blockState;
		if (state == null || !BloomSelectionState.get().compilingSection) {
			return;
		}

		if (state != this.bloom$encodedState) {
			this.bloom$encoding = BloomSelection.getBlockEncoding(state);
			this.bloom$encodedState = state;
		}
		int encoding = this.bloom$encoding;
		for (int i = 0; i < 4; i++) {
			quad.lightmap(i, BloomSourceEncoding.applyToLight(quad.lightmap(i), encoding));
		}
	}
}
