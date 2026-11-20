package com.bloom.mixin.client;

import com.bloom.client.render.BloomPostProcessor;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shader reloads install a new pipeline cache. Clear Shine's remembered compile failures then, so a
 * resource pack reload that fixes a shader re-enables bloom without a config save.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
	@Inject(method = "setCurrentPipelineCache", at = @At("RETURN"))
	private static void bloom$onPipelineCacheChanged(PipelineCache pipelineCache, CallbackInfoReturnable<PipelineCache> cir) {
		BloomPostProcessor.onPipelinesReloaded();
	}
}
