package com.bloom.mixin.client.sodium;

import com.bloom.client.selection.BloomSelectionState;
import com.bloom.client.selection.BloomSourceEncoding;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Packs the current block/fluid source strength into the free bits of Sodium's per-vertex material byte. */
@Mixin(ChunkMeshBufferBuilder.class)
public abstract class SodiumChunkMeshBufferBuilderMixin {
	@ModifyVariable(
		method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V",
		at = @At("HEAD"),
		argsOnly = true
	)
	private int bloom$encodeSourceStrength(int materialBits) {
		return BloomSourceEncoding.applyToMaterial(materialBits, BloomSelectionState.get().sodiumEncoding);
	}
}
