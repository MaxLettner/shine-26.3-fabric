package com.bloom.client.compat.sodium;

import com.bloom.BloomMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * State for replaying Sodium's opaque terrain into the bloom source target.
 *
 * <p>While active, Sodium's chunk renderer is handed a bloom variant of its own pipeline: same vertex
 * layout, defines and bindings, but Shine's shaders (which decode the source strength from Sodium's
 * material bits), a depth-equal test without writes, and a plain color target.
 */
public final class SodiumBloomReplay {
	private static final Identifier VERTEX_SHADER_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "sodium/block_layer_bloom");
	private static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "sodium/block_layer_bloom");
	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new IdentityHashMap<>();

	private static boolean active;
	private static boolean failed;

	private SodiumBloomReplay() {
	}

	public static void begin() {
		active = true;
	}

	/**
	 * @return true if every pipeline swap during the replay succeeded
	 */
	public static boolean end() {
		active = false;
		return !failed;
	}

	public static boolean isActive() {
		return active;
	}

	/** Once a variant fails to compile, the replay is skipped until {@link #clearVariants()} (config save). */
	public static boolean hasFailed() {
		return failed;
	}

	/**
	 * Returns the bloom variant of an opaque Sodium pipeline. If it cannot be compiled the original is
	 * returned (Sodium would throw on a missing pipeline) and the replay is marked as failed so its
	 * output is discarded and later replays are skipped.
	 */
	public static RenderPipeline variantOf(RenderPipeline sodiumPipeline) {
		if (failed) {
			return sodiumPipeline;
		}
		RenderPipeline variant = VARIANTS.computeIfAbsent(sodiumPipeline, SodiumBloomReplay::buildVariant);
		if (RenderSystem.getCompiledPipelineNullable(variant) == null) {
			failed = true;
			BloomMod.LOGGER.warn("Shine: Sodium bloom pipeline {} failed to compile; bloom is disabled with Sodium until the config is saved again.", variant.getLocation());
			return sodiumPipeline;
		}
		return variant;
	}

	private static RenderPipeline buildVariant(RenderPipeline source) {
		Map<ShaderType, Identifier> shaders = new EnumMap<>(ShaderType.class);
		shaders.put(ShaderType.VERTEX, VERTEX_SHADER_ID);
		shaders.put(ShaderType.FRAGMENT, FRAGMENT_SHADER_ID);
		VertexFormat[] vertexFormats = source.getVertexFormatBindings().toArray(new VertexFormat[16]);
		RenderPipeline.Snippet snippet = new RenderPipeline.Snippet(
			shaders,
			Optional.of(source.getShaderDefines()),
			Optional.of(source.getBindGroupLayouts()),
			new ColorTargetState[8],
			0,
			Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)),
			Optional.of(source.getPolygonMode()),
			Optional.of(source.isCull()),
			vertexFormats,
			Optional.of(source.getPrimitiveTopology()),
			source.pushConstantSize()
		);
		Identifier location = source.getLocation();
		return RenderPipeline.builder(snippet)
			.withLocation(Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "sodium/" + location.getNamespace() + "/" + location.getPath() + "_bloom"))
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build();
	}

	public static void clearFailure() {
		failed = false;
	}

	public static void clearVariants() {
		VARIANTS.clear();
		failed = false;
	}
}
