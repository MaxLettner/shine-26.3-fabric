package com.bloom.mixin.client;

import com.bloom.client.selection.BloomSelection;
import com.bloom.client.selection.BloomSelectionState;
import com.bloom.client.selection.BloomSourceEncoding;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A FluidRenderer is created per section compile, so the strength of the fluid being tesselated
 * can live on the instance. It is set at HEAD on every call (including Fabric's re-entrant
 * delegation for custom fluid handlers), so no pop is needed.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
	@Unique
	private double bloom$fluidStrength;

	@Inject(method = "tesselate", at = @At("HEAD"))
	private void bloom$captureFluidStrength(
		BlockAndTintGetter level,
		BlockPos pos,
		FluidRenderer.Output output,
		BlockState blockState,
		FluidState fluidState,
		CallbackInfo ci
	) {
		this.bloom$fluidStrength = BloomSelection.getFluidSourceStrength(fluidState);
	}

	@ModifyReturnValue(method = "getLightCoords", at = @At("RETURN"))
	private int bloom$encodeSourceStrength(int lightCoords) {
		if (!BloomSelectionState.isCompilingSection()) {
			return lightCoords;
		}
		return BloomSourceEncoding.encodePackedLight(lightCoords, this.bloom$fluidStrength);
	}
}
