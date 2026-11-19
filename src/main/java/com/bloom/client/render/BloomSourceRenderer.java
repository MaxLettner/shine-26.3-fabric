package com.bloom.client.render;

import com.bloom.BloomMod;
import com.bloom.mixin.client.ChunkSectionsToRenderAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Captures the bloom source: opaque terrain is drawn a second time into a separate color target,
 * depth-tested (equal or closer, no writes) against the finished main depth buffer so that
 * anything already in front of it (terrain, entities, block entities) occludes the source.
 */
public final class BloomSourceRenderer {
	public static final Identifier SOURCE_TARGET_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "source");
	private static final Identifier VERTEX_SHADER_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "core/terrain_bloom_source");
	private static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "core/terrain_bloom_source");
	private static final float CUTOUT_ALPHA_THRESHOLD = 0.5F;
	private static final Vector4f CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

	private static final RenderPipeline SOLID = buildPipeline("solid", RenderPipelines.TERRAIN_SNIPPET, false);
	private static final RenderPipeline SOLID_MULTIDRAW = buildPipeline("solid_multidraw", RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET, false);
	private static final RenderPipeline CUTOUT = buildPipeline("cutout", RenderPipelines.TERRAIN_SNIPPET, true);
	private static final RenderPipeline CUTOUT_MULTIDRAW = buildPipeline("cutout_multidraw", RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET, true);

	private static @Nullable TextureTarget sourceTarget;
	private static boolean warnedPipelineFailure;

	private BloomSourceRenderer() {
	}

	public static void reset() {
		if (sourceTarget != null) {
			sourceTarget.destroyBuffers();
			sourceTarget = null;
		}
		warnedPipelineFailure = false;
	}

	/**
	 * Must be called with no render pass open.
	 *
	 * @return the filled source target, or null if the source could not be rendered this frame
	 */
	public static @Nullable RenderTarget renderSource(
		ChunkSectionsToRender chunkSectionsToRender,
		RenderTarget mainTarget,
		GpuSampler atlasSampler,
		GpuTextureView atlas,
		GpuTextureView lightmap
	) {
		GpuTextureView depthView = mainTarget.getDepthTextureView();
		if (depthView == null || mainTarget.width <= 0 || mainTarget.height <= 0 || !pipelinesAvailable()) {
			return null;
		}

		TextureTarget target = ensureTarget(mainTarget.width, mainTarget.height);
		ChunkSectionsToRenderAccessor chunks = (ChunkSectionsToRenderAccessor) chunkSectionsToRender;
		RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		boolean needsIndices = chunks.bloom$getMaxIndicesRequired() != 0;
		GpuBuffer defaultIndexBuffer = needsIndices ? autoIndices.getBuffer() : null;
		IndexType defaultIndexType = needsIndices ? autoIndices.type() : null;

		try (
			RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "Shine bloom source", target.getColorTextureView(), Optional.of(CLEAR_COLOR), depthView, OptionalDouble.empty())
		) {
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("TerrainUniform", chunks.bloom$getTerrainTransformUBO());
			renderPass.setUniform("Sampler0", atlas, atlasSampler);
			renderPass.setUniform("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			chunks.bloom$render(ChunkSectionLayer.SOLID, renderPass, defaultIndexBuffer, defaultIndexType, SOLID, SOLID_MULTIDRAW);
			chunks.bloom$render(ChunkSectionLayer.CUTOUT, renderPass, defaultIndexBuffer, defaultIndexType, CUTOUT, CUTOUT_MULTIDRAW);
		}

		return target;
	}

	private static TextureTarget ensureTarget(int width, int height) {
		if (sourceTarget == null) {
			sourceTarget = new TextureTarget("Shine bloom source", width, height, GpuFormat.RGBA8_UNORM, null);
		} else if (sourceTarget.width != width || sourceTarget.height != height) {
			sourceTarget.resize(width, height);
		}
		return sourceTarget;
	}

	private static boolean pipelinesAvailable() {
		boolean available = RenderSystem.getCompiledPipelineNullable(SOLID) != null
			&& RenderSystem.getCompiledPipelineNullable(SOLID_MULTIDRAW) != null
			&& RenderSystem.getCompiledPipelineNullable(CUTOUT) != null
			&& RenderSystem.getCompiledPipelineNullable(CUTOUT_MULTIDRAW) != null;
		if (!available && !warnedPipelineFailure) {
			BloomMod.LOGGER.warn("Shine bloom source pipelines failed to compile; bloom is unavailable.");
			warnedPipelineFailure = true;
		}
		return available;
	}

	private static RenderPipeline buildPipeline(String name, RenderPipeline.Snippet terrainSnippet, boolean cutout) {
		RenderPipeline.Builder builder = RenderPipeline.builder(terrainSnippet)
			.withLocation(Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "pipeline/bloom_terrain_" + name))
			.withVertexShader(VERTEX_SHADER_ID)
			.withFragmentShader(FRAGMENT_SHADER_ID)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.withColorTargetState(ColorTargetState.DEFAULT);
		if (cutout) {
			builder.withShaderDefine("ALPHA_CUTOUT", CUTOUT_ALPHA_THRESHOLD);
		}
		return builder.build();
	}
}
