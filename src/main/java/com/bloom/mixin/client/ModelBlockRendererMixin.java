package com.bloom.mixin.client;

import com.bloom.client.selection.BloomSelection;
import com.bloom.client.selection.BloomSelectionState;
import com.bloom.client.selection.EncodingQuadInstance;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla meshing path, used when no Fabric renderer (Indigo) takes over block tesselation.
 * Emission is applied when the quad is written, which repacks light and would drop the encoded
 * low bits, so the encoding has to happen after emission inside the QuadInstance itself.
 *
 * <p>SectionCompiler creates a ModelBlockRenderer per compile (it is not thread-safe anyway), so the
 * per-block state lives on the instance instead of in ThreadLocals.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
	@Unique
	private final EncodingQuadInstance bloom$encodingInstance = new EncodingQuadInstance();
	@Unique
	private boolean bloom$encode;
	@Unique
	private int bloom$encoding;

	@Inject(method = "tesselateBlock", at = @At("HEAD"))
	private void bloom$resolveEncoding(CallbackInfo ci, @Local(argsOnly = true) BlockState blockState) {
		this.bloom$encode = BloomSelectionState.get().compilingSection;
		if (this.bloom$encode) {
			this.bloom$encoding = BloomSelection.getBlockEncoding(blockState);
		}
	}

	@WrapOperation(
		method = "putQuadWithTint",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"
		)
	)
	private void bloom$encodeSourceStrength(
		BlockQuadOutput output,
		float x,
		float y,
		float z,
		BakedQuad quad,
		QuadInstance instance,
		Operation<Void> original
	) {
		original.call(output, x, y, z, quad, this.bloom$encode ? this.bloom$encodingInstance.copyFrom(instance, this.bloom$encoding) : instance);
	}
}
