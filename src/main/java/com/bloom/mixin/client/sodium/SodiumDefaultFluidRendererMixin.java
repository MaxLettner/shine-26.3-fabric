package com.bloom.mixin.client.sodium;

import com.bloom.client.selection.BloomSelection;
import com.bloom.client.selection.BloomSelectionState;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultFluidRenderer.class)
public abstract class SodiumDefaultFluidRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void bloom$setFluidStrength(CallbackInfo ci, @Local(argsOnly = true) FluidState fluidState) {
		BloomSelectionState.get().sodiumEncoding = BloomSelection.getFluidEncoding(fluidState);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void bloom$clearFluidStrength(CallbackInfo ci) {
		BloomSelectionState.get().sodiumEncoding = 0;
	}
}
