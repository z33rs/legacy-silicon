package dev.legacysilicon.mixin;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dev.legacysilicon.region.ChunkRegions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ChunkAssemblyHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkBuilder.class)
public abstract class ChunkBuilderMixin {
	/**
	 * Vanilla's upload step, render-thread branch: send the finished mesh into the chunk's region buffer
	 * instead of its private VBO. Off-thread calls fall through to vanilla, which queues a task that
	 * calls this method again on the render thread.
	 */
	@Inject(method = "method_12419", at = @At("HEAD"), cancellable = true)
	private void legacysilicon$uploadToRegion(RenderLayer layer, BufferBuilder builder, BuiltChunk chunk, ChunkAssemblyHelper helper,
			double distance, CallbackInfoReturnable<ListenableFuture<Object>> cir) {
		if (!ChunkRegions.active || !MinecraftClient.getInstance().isOnThread()) {
			return;
		}
		if (ChunkRegions.dualUpload) {
			chunk.method_10165(layer.ordinal()).data(builder.getByteBuffer());
		}
		try {
			ChunkRegions.upload(chunk, layer, builder.getByteBuffer());
		} catch (RuntimeException e) {
			// Never let our code fail an upload future: vanilla turns that into a "Rendering chunk" crash.
			// Skip this one upload; uploadFailed schedules a rebuild of the chunk.
			ChunkRegions.uploadFailed(chunk, layer, e);
		}
		builder.reset();
		builder.offset(0.0, 0.0, 0.0);
		cir.setReturnValue(Futures.<Object>immediateFuture(null));
	}
}
