package com.bloom.mixin.client;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the bloom source pass replay vanilla's per-layer terrain draws (both the separate-draw and
 * the multidraw-indirect paths) with Shine's own pipelines.
 */
@Mixin(ChunkSectionsToRender.class)
public interface ChunkSectionsToRenderAccessor {
	@Accessor("maxIndicesRequired")
	int bloom$getMaxIndicesRequired();

	@Accessor("terrainTransformUBO")
	GpuBufferSlice bloom$getTerrainTransformUBO();

	@Invoker("render")
	void bloom$render(
		ChunkSectionLayer layer,
		RenderPass renderPass,
		@Nullable GpuBuffer defaultIndexBuffer,
		@Nullable IndexType defaultIndexType,
		@Nullable RenderPipeline renderPipelineOverride,
		@Nullable RenderPipeline renderPipelineOverrideMultidraw
	);
}
