package com.bloom.client.render;

import com.bloom.BloomMod;
import com.bloom.client.compat.IrisCompat;
import com.bloom.client.config.BloomConfig;
import com.bloom.mixin.client.accessor.PostChainAccessor;
import com.bloom.mixin.client.accessor.PostPassAccessor;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Runs the bloom post chain between opaque and translucent geometry:
 * extract (half res) -> downsample pyramid -> composite (full res) -> blit back onto the main target.
 */
public final class BloomPostProcessor {
	private static final Identifier EXTRACT_SHADER_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "post/bloom_extract");
	private static final Identifier DOWNSAMPLE_SHADER_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "post/bloom_downsample");
	private static final Identifier COMPOSITE_SHADER_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "post/bloom_composite");
	private static final Identifier BLIT_SHADER_ID = Identifier.withDefaultNamespace("post/blit");
	private static final Identifier SCREEN_QUAD_SHADER_ID = Identifier.withDefaultNamespace("core/screenquad");
	private static final Identifier RUNTIME_CHAIN_ID = Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, "runtime_bloom");
	private static final Identifier MAIN_TARGET_ID = PostChain.MAIN_TARGET_ID;
	private static final Set<Identifier> EXTERNAL_TARGETS = Set.of(MAIN_TARGET_ID, BloomSourceRenderer.SOURCE_TARGET_ID);
	private static final int MAX_ACTIVE_LEVELS = BloomConfig.MAX_BLUR_PASSES + 3;
	private static final float UNIFORM_EPSILON = 1.0E-5F;
	private static final float NEAR_PLANE = Camera.PROJECTION_Z_NEAR;
	private static final float DISTANCE_FADE_RANGE = 2.0F;
	private static final float SOURCE_STRENGTH_SCALE = 5.0F;
	private static final float FIRST_EXTRA_LEVEL_RADIUS = 100.0F;
	private static final float SECOND_EXTRA_LEVEL_RADIUS = 200.0F;
	private static final float RADIUS_RESPONSE_EXPONENT = 1.5F;
	private static final double WEIGHT_DISTRIBUTION_DENOMINATOR = 1.15D;
	private static final float[] BASE_LEVEL_FACTORS = new float[] { 1.0F, 0.8F, 0.6F, 0.4F, 0.2F, 0.1F };
	private static final float[] LEVEL_SCALES = new float[] { 0.5F, 0.25F, 0.125F, 0.0625F, 0.03125F, 0.015625F };
	private static final Identifier[] LEVEL_TARGET_IDS = new Identifier[] {
		runtimeTarget("half_a"),
		runtimeTarget("quarter_a"),
		runtimeTarget("eighth_a"),
		runtimeTarget("sixteenth_a"),
		runtimeTarget("thirtysecond_a"),
		runtimeTarget("sixtyfourth_a")
	};
	private static final String[] LEVEL_SAMPLER_NAMES = new String[] { "Half", "Quarter", "Eighth", "Sixteenth", "Thirtysecond", "Sixtyfourth" };
	private static final Identifier COMPOSITE_TARGET_ID = runtimeTarget("composite");

	private static final Projection PROJECTION = new Projection();
	private static @Nullable ProjectionMatrixBuffer projectionBuffer;
	private static @Nullable CrossFrameResourcePool resourcePool;

	private static boolean warnedChainLoadFailure;
	private static long failedChainKey = Long.MIN_VALUE;
	private static boolean warnedUniformWriteFailure;
	private static boolean irisCompatibilityDisabled;
	private static @Nullable String irisCompatibilityMessage;
	private static boolean uniformsDirty = true;
	private static @Nullable PostChain runtimeChain;
	private static int runtimeChainWidth = -1;
	private static int runtimeChainHeight = -1;
	private static int runtimeChainLevels = -1;
	private static final float[] lastExtractUniforms = new float[7];
	private static final float[] lastCompositeLevelWeights = new float[MAX_ACTIVE_LEVELS];
	private static float lastCompositeStrength;
	private static float lastCompositeMaxDistance;
	private static float lastCompositeFarPlane;

	static {
		PROJECTION.setupOrtho(0.1F, 1000.0F, 1.0F, 1.0F, false);
	}

	private BloomPostProcessor() {
	}

	public static boolean toggleFromKeybind() {
		BloomConfig.Data config = BloomConfig.get();
		config.enabled = !config.enabled;
		BloomConfig.save();
		return config.enabled;
	}

	public static void onConfigSaved() {
		warnedChainLoadFailure = false;
		warnedUniformWriteFailure = false;
		failedChainKey = Long.MIN_VALUE;
		uniformsDirty = true;
		closeRuntimeChain();
		BloomSourceRenderer.reset();
	}

	/**
	 * Decided before the opaque render pass is split, so nothing is touched when bloom is off.
	 */
	public static boolean shouldRender() {
		BloomConfig.Data config = BloomConfig.get();
		if (!config.enabled || config.strength <= 1.0E-4 || Minecraft.getInstance().level == null) {
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
		RenderTarget sourceTarget = BloomSourceRenderer.renderSource(chunkSectionsToRender, mainTarget, atlasSampler, atlas, lightmap);
		if (sourceTarget == null) {
			return;
		}

		BloomConfig.Data config = BloomConfig.get();
		int baseLevels = getBaseLevels(config.blurPassCount);
		int activeLevels = getActiveLevels(config.blurPassCount, config.radius);
		PostChain chain = ensureRuntimeChain(mainTarget.width, mainTarget.height, activeLevels);
		if (chain == null) {
			return;
		}

		applyConfigUniforms(chain, config, activeLevels, baseLevels, farPlane);
		processChain(chain, mainTarget, sourceTarget);
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
			closeRuntimeChain();
			BloomSourceRenderer.reset();
		}
		return true;
	}

	private static @Nullable PostChain ensureRuntimeChain(int mainWidth, int mainHeight, int activeLevels) {
		if (runtimeChain != null && runtimeChainWidth == mainWidth && runtimeChainHeight == mainHeight && runtimeChainLevels == activeLevels) {
			return runtimeChain;
		}

		long chainKey = ((long) mainWidth << 32) | ((long) mainHeight << 8) | activeLevels;
		if (chainKey == failedChainKey) {
			// Don't recompile a broken chain every frame; retry on resize, level change or config save.
			return null;
		}

		closeRuntimeChain();
		if (projectionBuffer == null) {
			projectionBuffer = new ProjectionMatrixBuffer("shine_runtime_post");
		}
		try {
			runtimeChain = PostChain.load(
				buildRuntimeConfig(mainWidth, mainHeight, activeLevels),
				Minecraft.getInstance().getTextureManager(),
				EXTERNAL_TARGETS,
				RUNTIME_CHAIN_ID.withSuffix("_" + activeLevels),
				PROJECTION,
				projectionBuffer
			);
			runtimeChainWidth = mainWidth;
			runtimeChainHeight = mainHeight;
			runtimeChainLevels = activeLevels;
			uniformsDirty = true;
			warnedChainLoadFailure = false;
			failedChainKey = Long.MIN_VALUE;
			return runtimeChain;
		} catch (ShaderManager.CompilationException e) {
			failedChainKey = chainKey;
			if (!warnedChainLoadFailure) {
				BloomMod.LOGGER.warn("Shine runtime bloom chain could not be built.", e);
				warnedChainLoadFailure = true;
			}
			return null;
		}
	}

	private static PostChainConfig buildRuntimeConfig(int mainWidth, int mainHeight, int activeLevels) {
		Map<Identifier, PostChainConfig.InternalTarget> targets = new LinkedHashMap<>();
		for (int level = 0; level < activeLevels; level++) {
			targets.put(
				LEVEL_TARGET_IDS[level],
				internalTarget(Optional.of(getScaledDimension(mainWidth, LEVEL_SCALES[level])), Optional.of(getScaledDimension(mainHeight, LEVEL_SCALES[level])))
			);
		}
		// Full-size intermediate: the composite samples the main target, so it cannot render into it directly.
		targets.put(COMPOSITE_TARGET_ID, internalTarget(Optional.empty(), Optional.empty()));

		List<PostChainConfig.Pass> passes = new ArrayList<>();
		passes.add(
			postPass(
				EXTRACT_SHADER_ID,
				List.of(
					targetInput("Depth", MAIN_TARGET_ID, true, false),
					targetInput("Source", BloomSourceRenderer.SOURCE_TARGET_ID, false, true)
				),
				LEVEL_TARGET_IDS[0],
				extractUniformDefaults()
			)
		);
		for (int level = 1; level < activeLevels; level++) {
			passes.add(postPass(DOWNSAMPLE_SHADER_ID, List.of(targetInput("In", LEVEL_TARGET_IDS[level - 1], false, true)), LEVEL_TARGET_IDS[level], Map.of()));
		}

		List<PostChainConfig.Input> compositeInputs = new ArrayList<>();
		compositeInputs.add(targetInput("Main", MAIN_TARGET_ID, false, false));
		for (int level = 0; level < LEVEL_SAMPLER_NAMES.length; level++) {
			compositeInputs.add(targetInput(LEVEL_SAMPLER_NAMES[level], LEVEL_TARGET_IDS[Math.min(level, activeLevels - 1)], false, true));
		}
		compositeInputs.add(targetInput("Depth", MAIN_TARGET_ID, true, false));
		passes.add(postPass(COMPOSITE_SHADER_ID, compositeInputs, COMPOSITE_TARGET_ID, compositeUniformDefaults()));
		passes.add(postPass(BLIT_SHADER_ID, List.of(targetInput("In", COMPOSITE_TARGET_ID, false, false)), MAIN_TARGET_ID, blitUniformDefaults()));

		return new PostChainConfig(targets, passes);
	}

	private static void processChain(PostChain chain, RenderTarget mainTarget, RenderTarget sourceTarget) {
		if (resourcePool == null) {
			resourcePool = new CrossFrameResourcePool(3);
		}

		FrameGraphBuilder frameGraphBuilder = new FrameGraphBuilder();
		BloomTargetBundle targetBundle = new BloomTargetBundle(
			frameGraphBuilder.importExternal("main", mainTarget),
			frameGraphBuilder.importExternal("shine_source", sourceTarget)
		);
		chain.addToFrame(frameGraphBuilder, mainTarget.width, mainTarget.height, targetBundle);
		frameGraphBuilder.execute(resourcePool);
		resourcePool.endFrame();
	}

	private static void applyConfigUniforms(PostChain chain, BloomConfig.Data config, int activeLevels, int baseLevels, float farPlane) {
		List<PostPass> passes = ((PostChainAccessor) chain).bloom$getPasses();
		if (passes.size() < 3) {
			return;
		}

		float threshold = (float) config.threshold;
		float highlightClamp = (float) config.highlightClamp;
		float softKnee = (float) config.softKnee;
		float maxDistance = (float) config.bloomDistance;
		float[] extract = new float[] { threshold, highlightClamp, softKnee, maxDistance, NEAR_PLANE, farPlane, SOURCE_STRENGTH_SCALE };
		if (uniformsDirty || arrayChanged(extract, lastExtractUniforms, extract.length)) {
			writeUniform(passes.getFirst(), "BloomExtractConfig", 8, builder -> {
				for (float value : extract) {
					builder.putFloat(value);
				}
				builder.putFloat(DISTANCE_FADE_RANGE);
			});
			System.arraycopy(extract, 0, lastExtractUniforms, 0, extract.length);
		}

		PostPass compositePass = passes.get(passes.size() - 2);
		float strength = (float) config.strength;
		if (uniformsDirty || changed(lastCompositeStrength, strength)) {
			writeUniform(compositePass, "BloomCompositeConfig", 1, builder -> builder.putFloat(strength));
			lastCompositeStrength = strength;
		}

		float[] levelWeights = computeLevelWeights(config.radius, activeLevels, baseLevels);
		if (uniformsDirty || arrayChanged(levelWeights, lastCompositeLevelWeights, MAX_ACTIVE_LEVELS)) {
			writeUniform(compositePass, "BloomCompositeWeights", 8, builder -> {
				for (int i = 0; i < 8; i++) {
					builder.putFloat(i < MAX_ACTIVE_LEVELS ? levelWeights[i] : 0.0F);
				}
			});
			System.arraycopy(levelWeights, 0, lastCompositeLevelWeights, 0, MAX_ACTIVE_LEVELS);
		}

		if (uniformsDirty || changed(lastCompositeMaxDistance, maxDistance) || changed(lastCompositeFarPlane, farPlane)) {
			writeUniform(compositePass, "BloomCompositeDistanceConfig", 4, builder -> {
				builder.putFloat(maxDistance);
				builder.putFloat(NEAR_PLANE);
				builder.putFloat(farPlane);
				builder.putFloat(DISTANCE_FADE_RANGE);
			});
			lastCompositeMaxDistance = maxDistance;
			lastCompositeFarPlane = farPlane;
		}

		uniformsDirty = false;
	}

	private static float[] computeLevelWeights(double radius, int activeLevels, int baseLevels) {
		float[] weights = new float[MAX_ACTIVE_LEVELS];
		float radiusNorm = clamp((float) (radius / BloomConfig.MAX_RADIUS), 0.0F, 1.0F);
		float radiusResponse = (float) Math.pow(radiusNorm, RADIUS_RESPONSE_EXPONENT);
		float targetIndex = radiusResponse * Math.max(activeLevels - 1, 0);
		for (int i = 0; i < activeLevels; i++) {
			float distance = i - targetIndex;
			weights[i] = (float) (BASE_LEVEL_FACTORS[i] * Math.exp(-(distance * distance) / WEIGHT_DISTRIBUTION_DENOMINATOR));
		}

		if (activeLevels > baseLevels) {
			weights[baseLevels] *= clamp((float) ((radius - FIRST_EXTRA_LEVEL_RADIUS) / (SECOND_EXTRA_LEVEL_RADIUS - FIRST_EXTRA_LEVEL_RADIUS)), 0.0F, 1.0F);
		}
		if (activeLevels > baseLevels + 1) {
			weights[baseLevels + 1] *= clamp((float) ((radius - SECOND_EXTRA_LEVEL_RADIUS) / (BloomConfig.MAX_RADIUS - SECOND_EXTRA_LEVEL_RADIUS)), 0.0F, 1.0F);
		}

		float total = 0.0F;
		for (float weight : weights) {
			total += weight;
		}
		if (total > UNIFORM_EPSILON) {
			for (int i = 0; i < activeLevels; i++) {
				weights[i] /= total;
			}
		}
		return weights;
	}

	/**
	 * Post pass uniform buffers are immutable GPU buffers; swap in a freshly created one holding the new values.
	 */
	private static void writeUniform(PostPass pass, String uniformName, int floatCount, UniformWriter writer) {
		Map<String, GpuBuffer> customUniforms = ((PostPassAccessor) pass).bloom$getCustomUniforms();
		GpuBuffer previous = customUniforms.get(uniformName);
		if (previous == null) {
			return;
		}

		int size = ((floatCount * Float.BYTES) + 15) & ~15;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder builder = Std140Builder.onStack(stack, size);
			writer.write(builder);
			GpuBuffer replacement = RenderSystem.getDevice()
				.createBuffer(() -> "Shine uniform " + uniformName, GpuBuffer.USAGE_UNIFORM, builder.get());
			customUniforms.put(uniformName, replacement);
			previous.close();
		} catch (RuntimeException e) {
			if (!warnedUniformWriteFailure) {
				BloomMod.LOGGER.warn("Shine uniform update failed; continuing with shader defaults.", e);
				warnedUniformWriteFailure = true;
			}
		}
	}

	private static boolean changed(float previous, float current) {
		return Math.abs(previous - current) > UNIFORM_EPSILON;
	}

	private static boolean arrayChanged(float[] current, float[] previous, int length) {
		for (int i = 0; i < length; i++) {
			if (changed(previous[i], current[i])) {
				return true;
			}
		}
		return false;
	}

	private static void closeRuntimeChain() {
		if (runtimeChain != null) {
			runtimeChain.close();
			runtimeChain = null;
		}
		if (resourcePool != null) {
			resourcePool.close();
			resourcePool = null;
		}
		runtimeChainWidth = -1;
		runtimeChainHeight = -1;
		runtimeChainLevels = -1;
	}

	private static int getBaseLevels(int blurPassCount) {
		int clamped = Math.max(1, Math.min(BloomConfig.MAX_BLUR_PASSES, blurPassCount));
		return Math.min(MAX_ACTIVE_LEVELS - 1, clamped + 1);
	}

	private static int getActiveLevels(int blurPassCount, double radius) {
		int activeLevels = getBaseLevels(blurPassCount);
		if (radius > FIRST_EXTRA_LEVEL_RADIUS) {
			activeLevels++;
		}
		if (radius > SECOND_EXTRA_LEVEL_RADIUS) {
			activeLevels++;
		}
		return Math.min(MAX_ACTIVE_LEVELS, activeLevels);
	}

	private static int getScaledDimension(int mainSize, float scale) {
		return Math.max(1, Math.round(mainSize * scale));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static Identifier runtimeTarget(String path) {
		return Identifier.fromNamespaceAndPath(BloomMod.MOD_ID, path);
	}

	private static PostChainConfig.InternalTarget internalTarget(Optional<Integer> width, Optional<Integer> height) {
		return new PostChainConfig.InternalTarget(width, height, false, 0);
	}

	private static PostChainConfig.Pass postPass(
		Identifier fragmentShaderId,
		List<PostChainConfig.Input> inputs,
		Identifier outputTarget,
		Map<String, List<UniformValue>> uniforms
	) {
		return new PostChainConfig.Pass(SCREEN_QUAD_SHADER_ID, fragmentShaderId, inputs, outputTarget, uniforms);
	}

	private static PostChainConfig.TargetInput targetInput(String samplerName, Identifier targetId, boolean depthBuffer, boolean bilinear) {
		return new PostChainConfig.TargetInput(samplerName, targetId, depthBuffer, bilinear);
	}

	private static List<UniformValue> floats(float... values) {
		List<UniformValue> uniforms = new ArrayList<>(values.length);
		for (float value : values) {
			uniforms.add(new UniformValue.FloatUniform(value));
		}
		return uniforms;
	}

	private static Map<String, List<UniformValue>> extractUniformDefaults() {
		return Map.of("BloomExtractConfig", floats(0.15F, 0.3F, 0.2F, 75.0F, NEAR_PLANE, 1024.0F, SOURCE_STRENGTH_SCALE, DISTANCE_FADE_RANGE));
	}

	private static Map<String, List<UniformValue>> compositeUniformDefaults() {
		return Map.of(
			"BloomCompositeConfig", floats(3.0F),
			"BloomCompositeWeights", floats(1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F),
			"BloomCompositeDistanceConfig", floats(75.0F, NEAR_PLANE, 1024.0F, DISTANCE_FADE_RANGE)
		);
	}

	private static Map<String, List<UniformValue>> blitUniformDefaults() {
		// vec4 ColorModulate
		return Map.of("BlitConfig", floats(1.0F, 1.0F, 1.0F, 1.0F));
	}

	@FunctionalInterface
	private interface UniformWriter {
		void write(Std140Builder builder);
	}

	private static final class BloomTargetBundle implements PostChain.TargetBundle {
		private ResourceHandle<RenderTarget> main;
		private ResourceHandle<RenderTarget> source;

		private BloomTargetBundle(ResourceHandle<RenderTarget> main, ResourceHandle<RenderTarget> source) {
			this.main = main;
			this.source = source;
		}

		@Override
		public void replace(Identifier identifier, ResourceHandle<RenderTarget> resourceHandle) {
			if (identifier.equals(MAIN_TARGET_ID)) {
				this.main = resourceHandle;
			} else if (identifier.equals(BloomSourceRenderer.SOURCE_TARGET_ID)) {
				this.source = resourceHandle;
			} else {
				throw new IllegalArgumentException("No target with id " + identifier);
			}
		}

		@Override
		public @Nullable ResourceHandle<RenderTarget> get(Identifier identifier) {
			if (identifier.equals(MAIN_TARGET_ID)) {
				return this.main;
			}
			return identifier.equals(BloomSourceRenderer.SOURCE_TARGET_ID) ? this.source : null;
		}
	}
}
