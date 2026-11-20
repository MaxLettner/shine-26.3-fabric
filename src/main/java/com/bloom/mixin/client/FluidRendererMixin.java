package com.bloom.mixin.client;

import com.bloom.client.selection.BloomSelection;
import com.bloom.client.selection.BloomSelectionState;
import com.bloom.client.selection.BloomSourceEncoding;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A FluidRenderer is created per section compile, so the encoding of the fluid being tesselated lives
 * on the instance. It is set at HEAD on every call (including Fabric's re-entrant delegation for custom
 * fluid handlers), so no reset is needed.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
	@Unique
	private boolean bloom$encode;
	@Unique
	private int bloom$encoding;

	@Inject(method = "tesselate", at = @At("HEAD"))
	private void bloom$resolveEncoding(CallbackInfo ci, @Local(argsOnly = true) FluidState fluidState) {
		this.bloom$encode = BloomSelectionState.get().compilingSection;
		if (this.bloom$encode) {
			this.bloom$encoding = BloomSelection.getFluidEncoding(fluidState);
		}
	}

	@ModifyReturnValue(method = "getLightCoords", at = @At("RETURN"))
	private int bloom$encodeSourceStrength(int lightCoords) {
		return this.bloom$encode ? BloomSourceEncoding.applyToLight(lightCoords, this.bloom$encoding) : lightCoords;
	}
}
