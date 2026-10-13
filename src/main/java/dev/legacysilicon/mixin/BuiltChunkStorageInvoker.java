package dev.legacysilicon.mixin;

import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BuiltChunkStorage.class)
public interface BuiltChunkStorageInvoker {
	@Invoker("getRenderedChunk")
	BuiltChunk legacysilicon$getRenderedChunk(BlockPos pos);
}
