package com.bloom.mixin.client.sodium;

import com.bloom.client.compat.sodium.SodiumBloomReplay;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** During Shine's bloom replay, swap Sodium's opaque terrain pipeline for its bloom variant. */
@Mixin(ShaderChunkRenderer.class)
public abstract class SodiumShaderChunkRendererMixin {
	@Shadow
	protected RenderPipeline activeProgram;

	@Inject(method = "begin", at = @At("TAIL"))
	private void bloom$useBloomPipeline(TerrainRenderPass pass, @Nullable OitStage stage, CallbackInfo ci) {
		if (SodiumBloomReplay.isActive() && stage == null && !pass.isTranslucent() && this.activeProgram != null) {
			this.activeProgram = SodiumBloomReplay.variantOf(this.activeProgram);
		}
	}
}
