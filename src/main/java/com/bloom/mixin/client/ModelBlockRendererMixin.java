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

/**
 * Vanilla meshing path, used when no Fabric renderer (Indigo) takes over block tesselation.
 * Emission is applied when the quad is written, which repacks light and would drop the encoded
 * low bits, so the encoding has to happen after emission inside the QuadInstance itself.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
	@Unique
	private static final ThreadLocal<EncodingQuadInstance> bloom$ENCODING_INSTANCE = ThreadLocal.withInitial(EncodingQuadInstance::new);

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
		Operation<Void> original,
		@Local(argsOnly = true) BlockState blockState
	) {
		if (!BloomSelectionState.isCompilingSection()) {
			original.call(output, x, y, z, quad, instance);
			return;
		}

		EncodingQuadInstance encoding = bloom$ENCODING_INSTANCE.get().copyFrom(instance, BloomSelection.getBlockSourceStrength(blockState));
		original.call(output, x, y, z, quad, encoding);
	}
}
