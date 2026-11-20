package com.bloom.mixin.client.sodium;

import com.bloom.client.selection.BloomSelection;
import com.bloom.client.selection.BloomSelectionState;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockRenderer.class)
public abstract class SodiumBlockRendererMixin {
	@Inject(method = "renderModel", at = @At("HEAD"))
	private void bloom$setBlockStrength(BlockStateModel model, BlockState blockState, BlockPos pos, BlockPos modelOffset, CallbackInfo ci) {
		BloomSelectionState.setSodiumSourceStrength(BloomSelection.getBlockSourceStrength(blockState));
	}

	@Inject(method = "renderModel", at = @At("RETURN"))
	private void bloom$clearBlockStrength(BlockStateModel model, BlockState blockState, BlockPos pos, BlockPos modelOffset, CallbackInfo ci) {
		BloomSelectionState.setSodiumSourceStrength(0.0);
	}
}
