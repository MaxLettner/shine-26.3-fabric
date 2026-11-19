package com.bloom.mixin.client;

import com.bloom.client.render.BloomPostProcessor;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs bloom after opaque terrain + solid features and before translucent geometry.
 *
 * <p>Improved transparency (OIT): the opaque pass is already closed when {@code executeOit} starts.
 *
 * <p>Classic transparency: translucents are drawn in the same pass as opaque geometry. The pass is
 * closed at the start of {@code executeClassicTransparency} (closing is idempotent, so vanilla's own
 * close later is a no-op), bloom runs, and a fresh pass on the same targets replaces the method's
 * {@code renderPass} argument for the translucent draws.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private GameRenderer gameRenderer;

	@Shadow
	@Final
	private TextureManager textureManager;

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	@Final
	private LevelTargetBundle targets;

	@Shadow
	private @Nullable GpuSampler chunkLayerSampler;

	@Unique
	private @Nullable RenderPass bloom$translucentPass;

	@ModifyVariable(method = "executeClassicTransparency", at = @At("HEAD"), argsOnly = true)
	private RenderPass bloom$renderBloomBeforeClassicTranslucents(
		RenderPass renderPass,
		@Local(argsOnly = true) ChunkSectionsToRender chunkSectionsToRender
	) {
		if (!this.bloom$canRender()) {
			return renderPass;
		}

		RenderTarget mainTarget = this.targets.main.get();
		renderPass.close();
		this.bloom$render(chunkSectionsToRender, mainTarget);

		RenderPass translucentPass = RenderSystem.getDevice()
			.createCommandEncoder()
			.createRenderPass(
				() -> "Main (after Shine bloom)",
				mainTarget.getColorTextureView(),
				Optional.empty(),
				mainTarget.getDepthTextureView(),
				OptionalDouble.empty()
			);
		RenderSystem.bindDefaultUniforms(translucentPass);
		this.bloom$translucentPass = translucentPass;
		return translucentPass;
	}

	@Inject(method = "executeClassicTransparency", at = @At("RETURN"))
	private void bloom$closeTranslucentPass(CallbackInfo ci) {
		if (this.bloom$translucentPass != null) {
			this.bloom$translucentPass.close();
			this.bloom$translucentPass = null;
		}
	}

	@Inject(method = "executeOit", at = @At("HEAD"))
	private void bloom$renderBloomBeforeOit(
		ChunkSectionsToRender chunkSectionsToRender,
		FeatureRenderDispatcher.PreparedFrame featureFrame,
		CallbackInfo ci
	) {
		if (this.bloom$canRender()) {
			this.bloom$render(chunkSectionsToRender, this.targets.main.get());
		}
	}

	@Unique
	private boolean bloom$canRender() {
		return this.chunkLayerSampler != null && !this.levelRenderState.renderWireframeTerrain && BloomPostProcessor.shouldRender();
	}

	@Unique
	private void bloom$render(ChunkSectionsToRender chunkSectionsToRender, RenderTarget mainTarget) {
		BloomPostProcessor.render(
			chunkSectionsToRender,
			mainTarget,
			this.chunkLayerSampler,
			this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
			this.gameRenderer.lightmap(),
			this.levelRenderState.cameraRenderState.depthFar
		);
	}
}
