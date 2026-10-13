package dev.legacysilicon.mixin;

import dev.legacysilicon.TerrainBfs;
import dev.legacysilicon.region.ChunkRegions;
import dev.legacysilicon.region.Slice;
import dev.legacysilicon.region.SliceHolder;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ChunkAssemblyHelper;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BuiltChunk.class)
public abstract class BuiltChunkMixin implements SliceHolder {
	@Shadow private BlockPos.Mutable position;
	@Unique private final Slice[] legacysilicon$slices = new Slice[4];
	/** Bumped whenever this chunk is marked dirty or moved; keys the camera-chunk open-faces cache. */
	@Unique private int legacysilicon$changes;

	@Override
	public int legacysilicon$changes() {
		return this.legacysilicon$changes;
	}

	@Inject(method = "method_10162", at = @At("HEAD"))
	private void legacysilicon$onDirty(boolean important, CallbackInfo ci) {
		this.legacysilicon$changes++;
	}

	@Override
	public Slice[] legacysilicon$getSlices() {
		return this.legacysilicon$slices;
	}

	/** Chunk renderers are recycled as the camera moves; a new position means a different region. */
	@Inject(method = "method_12427", at = @At("HEAD"))
	private void legacysilicon$onMove(int x, int y, int z, CallbackInfo ci) {
		this.legacysilicon$changes++;
		if (ChunkRegions.active && (x != this.position.getX() || y != this.position.getY() || z != this.position.getZ())) {
			ChunkRegions.releaseAll((BuiltChunk) (Object) this);
		}
	}

	/**
	 * Autotest verification only: vanilla publishes some compiled chunks from worker threads, which can change
	 * the world between our shadow search and vanilla's in the same frame. While verifying, publications are
	 * queued and applied (in order) at the start of the next visibility update instead.
	 */
	@Inject(method = "method_10159", at = @At("HEAD"), cancellable = true)
	private void legacysilicon$holdPublication(ChunkAssemblyHelper helper, CallbackInfo ci) {
		if (TerrainBfs.verify && !TerrainBfs.draining) {
			TerrainBfs.held.add(new Object[] {this, helper});
			ci.cancel();
		}
	}

	@Inject(method = "delete", at = @At("HEAD"))
	private void legacysilicon$onDelete(CallbackInfo ci) {
		if (ChunkRegions.active) {
			ChunkRegions.releaseAll((BuiltChunk) (Object) this);
		}
	}
}
