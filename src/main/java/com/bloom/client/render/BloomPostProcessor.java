package com.bloom.client.render;

import com.bloom.BloomMod;
import com.bloom.client.compat.IrisCompat;
import com.bloom.client.config.BloomConfig;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Runs bloom between opaque and translucent geometry, as plain render passes:
 * extract (half res) -> downsample pyramid -> additive composite straight onto the main target.
 *
 * <p>Targets and uniform buffers persist across frames; uniforms are only rebuilt when the config,
 * the screen size or the far plane change.
 */
public final class BloomPostProcessor {
	private static final Identifier SCREEN_QUAD_SHADER_ID = Identifier.withDefaultNamespace("core/screenquad");
	private static final int MAX_LEVELS = BloomConfig.MAX_BLUR_PASSES + 3;
	private static final float NEAR_PLANE = Camera.PROJECTION_Z_NEAR;
	private static final float DISTANCE_FADE_RANGE = 2.0F;
	private static final float SOURCE_STRENGTH_SCALE = 5.0F;
	private static final float FIRST_EXTRA_LEVEL_RADIUS = 100.0F;
	private static final float SECOND_EXTRA_LEVEL_RADIUS = 200.0F;
	private static final float RADIUS_RESPONSE_EXPONENT = 1.5F;
	private static final double WEIGHT_DISTRIBUTION_DENOMINATOR = 1.15D;
	private static final float[] BASE_LEVEL_FACTORS = new float[] { 1.0F, 0.8F, 0.6F, 0.4F, 0.2F, 0.1F };

	private static final RenderPipeline EXTRACT_PIPELINE = postPipeline(
		"extract",
		BindGroupLayout.builder()
			.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("SourceSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("BloomExtractConfig", UniformType.UNIFORM_BUFFER)
			.build(),
		ColorTargetState.DEFAULT,
		0
	);
	private static final RenderPipeline DOWNSAMPLE_PIPELINE = postPipeline(
		"downsample",
		BindGroupLayout.builder()
			.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
			.build(),
		ColorTargetState.DEFAULT,
		0
	);
	/** Indexed by level count - 1. */
	private static final RenderPipeline[] COMPOSITE_PIPELINES = new RenderPipeline[MAX_LEVELS];

	static {
		// Additive onto the main target, leaving its alpha untouched.
		ColorTargetState additive = new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR);
		for (int levels = 1; levels <= MAX_LEVELS; levels++) {
			BindGroupLayout.Builder layout = BindGroupLayout.builder()
				.withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
				.withUniform("BloomCompositeConfig", UniformType.UNIFORM_BUFFER);
			for (int level = 0; level < levels; level++) {
				layout.withUniform("Level" + level + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
			}
			COMPOSITE_PIPELINES[levels - 1] = postPipeline("composite_" + levels, layout.build(), additive, levels);
		}
	}

	private static final TextureTarget[] levelTargets = new TextureTarget[MAX_LEVELS];
	private static final @Nullable GpuBuffer[] downsampleInfo = new GpuBuffer[MAX_LEVELS];
	private static @Nullable GpuBuffer extractConfig;
	private static @Nullable GpuBuffer compositeConfig;

	private static boolean uniformsDirty = true;
	private static int targetWidth = -1;
	private static int targetHeight = -1;
	private static float lastFarPlane = Float.NaN;
	private static boolean pipelinesFailed;
	private static boolean irisCompatibilityDisabled;
	private static @Nullable String irisCompatibilityMessage;

	private BloomPostProcessor() {
	}

	public static boolean toggleFromKeybind() {
		BloomConfig.Data config = BloomConfig.get();
		config.enabled = !config.enabled;
		BloomConfig.save();
		return config.enabled;
	}

	/** Called when shaders are reloaded: compile failures may be fixed now, so try again. */
	public static void onPipelinesReloaded() {
		pipelinesFailed = false;
		BloomSourceRenderer.clearPipelineFailures();
	}

	public static void onConfigSaved() {
		uniformsDirty = true;
		pipelinesFailed = false;
		BloomSourceRenderer.reset();
	}

	/**
	 * Decided before the opaque render pass is split, so nothing is touched when bloom is off.
	 */
	public static boolean shouldRender() {
		BloomConfig.Data config = BloomConfig.get();
		if (!config.enabled || config.strength <= 1.0E-4 || pipelinesFailed || Minecraft.getInstance().level == null) {
			return false;
		}
		return !shouldSkipForIrisCompatibility();
	}

	/**
	 * Called with no render pass open, after opaque terrain and solid features, before translucents.
	 */
	public static void render(
		ChunkSectionsToRender chunkSectionsToRender,
		RenderTarget mainTarget,
		GpuSampler atlasSampler,
		GpuTextureView atlas,
		GpuTextureView lightmap,
		float farPlane
	) {
		BloomConfig.Data config = BloomConfig.get();
		int baseLevels = getBaseLevels(config.blurPassCount);
		int levels = getActiveLevels(baseLevels, config.radius);
		CompiledRenderPipeline extract = RenderSystem.getCompiledPipelineNullable(EXTRACT_PIPELINE);
		CompiledRenderPipeline downsample = RenderSystem.getCompiledPipelineNullable(DOWNSAMPLE_PIPELINE);
		CompiledRenderPipeline composite = RenderSystem.getCompiledPipelineNullable(COMPOSITE_PIPELINES[levels - 1]);
		if (extract == null || downsample == null || composite == null) {
			// The pipeline cache does not remember failures; don't recompile every frame.
			pipelinesFailed = true;
			BloomMod.LOGGER.warn("Shine bloom post-processing pipelines failed to compile; bloom is disabled until the config is saved again.");
			return;
		}

		GpuTextureView mainDepth = mainTarget.getDepthTextureView();
		GpuTextureView mainColor = mainTarget.getColorTextureView();
		if (mainDepth == null || mainColor == null) {
			return;
		}

		RenderTarget sourceTarget = BloomSourceRenderer.renderSource(chunkSectionsToRender, mainTarget, atlasSampler, atlas, lightmap);
		if (sourceTarget == null) {
			return;
		}

		ensureTargets(mainTarget.width, mainTarget.height, levels);
		if (uniformsDirty || farPlane != lastFarPlane) {
			writeUniforms(config, baseLevels, farPlane);
			lastFarPlane = farPlane;
			uniformsDirty = false;
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

		try (RenderPass pass = encoder.createRenderPass(() -> "Shine bloom extract", levelTargets[0].getColorTextureView(), Optional.empty())) {
			pass.setPipeline(extract);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DepthSampler", mainDepth, nearest);
			pass.setUniform("SourceSampler", sourceTarget.getColorTextureView(), linear);
			pass.setUniform("BloomExtractConfig", extractConfig);
			pass.draw(3, 1, 0, 0);
		}

		for (int level = 1; level < levels; level++) {
			int index = level;
			try (RenderPass pass = encoder.createRenderPass(() -> "Shine bloom downsample " + index, levelTargets[level].getColorTextureView(), Optional.empty())) {
				pass.setPipeline(downsample);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("InSampler", levelTargets[level - 1].getColorTextureView(), linear);
				pass.setUniform("SamplerInfo", downsampleInfo[level]);
				pass.draw(3, 1, 0, 0);
			}
		}

		try (RenderPass pass = encoder.createRenderPass(() -> "Shine bloom composite", mainColor, Optional.empty())) {
			pass.setPipeline(composite);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DepthSampler", mainDepth, nearest);
			pass.setUniform("BloomCompositeConfig", compositeConfig);
			for (int level = 0; level < levels; level++) {
				pass.setUniform("Level" + level + "Sampler", levelTargets[level].getColorTextureView(), linear);
			}
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void ensureTargets(int mainWidth, int mainHeight, int levels) {
		boolean resized = mainWidth != targetWidth || mainHeight != targetHeight;
		if (resized) {
			targetWidth = mainWidth;
			targetHeight = mainHeight;
			uniformsDirty = true;
		}

		for (int level = 0; level < levels; level++) {
			int width = levelSize(mainWidth, level);
			int height = levelSize(mainHeight, level);
			TextureTarget target = levelTargets[level];
			if (target == null) {
				levelTargets[level] = new TextureTarget("Shine bloom level " + level, width, height, GpuFormat.RGBA8_UNORM, null);
			} else if (target.width != width || target.height != height) {
				target.resize(width, height);
			}
		}
	}

	private static void writeUniforms(BloomConfig.Data config, int baseLevels, float farPlane) {
		int levels = getActiveLevels(baseLevels, config.radius);
		float maxDistance = (float) config.bloomDistance;

		extractConfig = replaceBuffer(extractConfig, "extract config", 8, builder -> builder
			.putFloat((float) config.threshold)
			.putFloat((float) config.highlightClamp)
			.putFloat((float) config.softKnee)
			.putFloat(maxDistance)
			.putFloat(NEAR_PLANE)
			.putFloat(farPlane)
			.putFloat(SOURCE_STRENGTH_SCALE)
			.putFloat(DISTANCE_FADE_RANGE));

		for (int level = 1; level < levels; level++) {
			int outWidth = levelSize(targetWidth, level);
			int outHeight = levelSize(targetHeight, level);
			int inWidth = levelSize(targetWidth, level - 1);
			int inHeight = levelSize(targetHeight, level - 1);
			downsampleInfo[level] = replaceBuffer(downsampleInfo[level], "downsample info " + level, 4, builder -> builder
				.putVec2(outWidth, outHeight)
				.putVec2(inWidth, inHeight));
		}

		float[] weights = computeLevelWeights(config.radius, levels, baseLevels);
		float[] halfTexelX = new float[MAX_LEVELS];
		for (int level = 0; level < levels; level++) {
			halfTexelX[level] = 0.5F / levelSize(targetWidth, level);
		}
		compositeConfig = replaceBuffer(compositeConfig, "composite config", 21, builder -> builder
			.putVec4(weights[0], weights[1], weights[2], weights[3])
			.putVec4(weights[4], weights[5], 0.0F, 0.0F)
			.putVec4(halfTexelX[0], halfTexelX[1], halfTexelX[2], halfTexelX[3])
			.putVec4(halfTexelX[4], halfTexelX[5], 0.0F, 0.0F)
			.putFloat((float) config.strength)
			.putFloat(maxDistance)
			.putFloat(NEAR_PLANE)
			.putFloat(farPlane)
			.putFloat(DISTANCE_FADE_RANGE));
	}

	/**
	 * Uniform buffers are immutable GPU buffers created with their contents; closing the old one is
	 * deferred by the backend until the GPU is done with it.
	 */
	private static GpuBuffer replaceBuffer(@Nullable GpuBuffer previous, String name, int floatCount, Consumer<Std140Builder> writer) {
		int size = ((floatCount * Float.BYTES) + 15) & ~15;
		GpuBuffer buffer;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder builder = Std140Builder.onStack(stack, size);
			writer.accept(builder);
			buffer = RenderSystem.getDevice().createBuffer(() -> "Shine bloom " + name, GpuBuffer.USAGE_UNIFORM, builder.get());
		}
		if (previous != null) {
			previous.close();
		}
		return buffer;
	}

	private static float[] computeLevelWeights(double radius, int levels, int baseLevels) {
		float[] weights = new float[MAX_LEVELS];
		float radiusNorm = clamp((float) (radius / BloomConfig.MAX_RADIUS), 0.0F, 1.0F);
		float targetIndex = (float) Math.pow(radiusNorm, RADIUS_RESPONSE_EXPONENT) * Math.max(levels - 1, 0);
		for (int i = 0; i < levels; i++) {
			float distance = i - targetIndex;
			weights[i] = (float) (BASE_LEVEL_FACTORS[i] * Math.exp(-(distance * distance) / WEIGHT_DISTRIBUTION_DENOMINATOR));
		}

		if (levels > baseLevels) {
			weights[baseLevels] *= clamp((float) ((radius - FIRST_EXTRA_LEVEL_RADIUS) / (SECOND_EXTRA_LEVEL_RADIUS - FIRST_EXTRA_LEVEL_RADIUS)), 0.0F, 1.0F);
		}
		if (levels > baseLevels + 1) {
			weights[baseLevels + 1] *= clamp((float) ((radius - SECOND_EXTRA_LEVEL_RADIUS) / (BloomConfig.MAX_RADIUS - SECOND_EXTRA_LEVEL_RADIUS)), 0.0F, 1.0F);
		}

		float total = 0.0F;
		for (float weight : weights) {
			total += weight;
		}
		if (total > 1.0E-5F) {
			for (int i = 0; i < levels; i++) {
				weights[i] /= total;
			}
		}
		return weights;
	}

	private static boolean shouldSkipForIrisCompatibility() {
		boolean disableForIris = IrisCompat.shouldDisableBloom();
		if (!disableForIris) {
			if (irisCompatibilityDisabled) {
				BloomMod.LOGGER.info("Shine bloom re-enabled: Iris compatibility no longer blocks bloom.");
				irisCompatibilityDisabled = false;
				irisCompatibilityMessage = null;
			}
			return false;
		}

		String message = IrisCompat.disableMessage();
		if (!irisCompatibilityDisabled || !Objects.equals(message, irisCompatibilityMessage)) {
			BloomMod.LOGGER.info(message);
			irisCompatibilityDisabled = true;
			irisCompatibilityMessage = message;
			BloomSourceRenderer.reset();
		}
		return true;
	}

	private static int getBaseLevels(int blurPassCount) {
		int clamped = Math.max(1, Math.min(BloomConfig.MAX_BLUR_PASSES, blurPassCount));
		return Math.min(MAX_LEVELS - 1, clamped + 1);
	}

	private static int getActiveLevels(int baseLevels, double radius) {
		int levels = baseLevels;
		if (radius > FIRST_EXTRA_LEVEL_RADIUS) {
			levels++;
		}
		if (radius > SECOND_EXTRA_LEVEL_RADIUS) {
			levels++;
		}
		return Math.min(MAX_LEVELS, levels);
	}

	/** Level 0 is half resolution, each further level halves again. */
	private static int levelSize(int mainSize, int level) {
		return Math.max(1, Math.round(mainSize * (0.5F / (1 << level))));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static RenderPipeline postPipeline(String name, BindGroupLayout layout, ColorTargetState colorTarget, int levels) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "pipeline/bloom_" + name))
			.withVertexShader(SCREEN_QUAD_SHADER_ID)
			.withFragmentShader(Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "post/bloom_" + (levels > 0 ? "composite" : name)))
			.withBindGroupLayout(layout)
			.withColorTargetState(colorTarget);
		if (levels > 0) {
			builder.withShaderDefine("BLOOM_LEVELS", levels);
		}
		return builder.build();
	}
}
