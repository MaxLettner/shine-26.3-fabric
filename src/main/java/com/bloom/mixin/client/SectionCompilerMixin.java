package com.bloom.mixin.client;

import com.bloom.client.selection.BloomSelectionState;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@Inject(method = "compile", at = @At("HEAD"))
	private void bloom$beginCompile(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		BloomSelectionState.get().compilingSection = true;
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void bloom$endCompile(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		BloomSelectionState.get().compilingSection = false;
	}
}
