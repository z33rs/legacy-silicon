package dev.legacysilicon.mixin;

import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.legacysilicon.Config;
import dev.legacysilicon.TerrainBfs;
import dev.legacysilicon.region.SliceHolder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Shadow @Final private MinecraftClient client;
	@Shadow private ClientWorld world;
	@Shadow private List<?> visibleChunks;
	@Shadow private BuiltChunkStorage chunks;
	@Shadow private AbstractChunkRenderManager chunkRenderManager;
	@Shadow private int renderDistance;

	@Unique private boolean legacysilicon$bfsDone;
	@Unique private List<?> legacysilicon$shadow;

	// Camera-chunk open faces cache: valid while the same chunk renderer, its change count and the camera block match.
	@Unique private BuiltChunk legacysilicon$facesChunk;
	@Unique private int legacysilicon$facesChanges;
	@Unique private long legacysilicon$facesPos;
	@Unique private ClientWorld legacysilicon$facesWorld;
	@Unique private Set<Direction> legacysilicon$faces;

	// Entity loop: per-frame direct-mapped cache of world chunk columns.
	@Unique private final Chunk[] legacysilicon$colChunks = new Chunk[4096];
	@Unique private final long[] legacysilicon$colKeys = new long[4096];
	@Unique private final int[] legacysilicon$colStamp = new int[4096];
	@Unique private int legacysilicon$entityFrame;

	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void legacysilicon$beginTerrain(Entity entity, double tickDelta, CameraView view, int frame, boolean spectator, CallbackInfo ci) {
		this.legacysilicon$bfsDone = false;
		if (!TerrainBfs.held.isEmpty()) {
			TerrainBfs.releaseHeld();
		}
	}

	/**
	 * The first loop check of vanilla's flood fill. Fast mode: drain the queue with TerrainBfs and report
	 * empty so vanilla's loop never runs. Verify mode: run TerrainBfs in shadow, let vanilla run, compare.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	@WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Ljava/util/Queue;isEmpty()Z"))
	private boolean legacysilicon$floodFill(Queue queue, Operation<Boolean> original, @Local(argsOnly = true) Entity entity,
			@Local(argsOnly = true) CameraView view, @Local(argsOnly = true) int frame, @Local(argsOnly = true) boolean spectator) {
		if (!Config.fastTerrainSetup) {
			return original.call(queue);
		}
		if (!this.legacysilicon$bfsDone) {
			this.legacysilicon$bfsDone = true;
			AbstractChunkRenderManagerAccessor cam = (AbstractChunkRenderManagerAccessor) this.chunkRenderManager;
			double g = cam.legacysilicon$getViewX();
			double h = cam.legacysilicon$getViewY();
			double i = cam.legacysilicon$getViewZ();
			int camX = MathHelper.floor(g / 16.0) * 16;
			int camZ = MathHelper.floor(i / 16.0) * 16;
			// Vanilla's bl2: culling flag, switched off when a spectator's eye is inside a full block.
			boolean cull = this.client.chunkCullingEnabled;
			if (cull && spectator) {
				BlockPos eye = new BlockPos(g, h + entity.getEyeHeight(), i);
				if (((BuiltChunkStorageInvoker) this.chunks).legacysilicon$getRenderedChunk(eye) != null
					&& this.world.getBlockState(eye).isFullBoundsCubeForCulling()) {
					cull = false;
				}
			}
			BuiltChunkStorageAccessor s = (BuiltChunkStorageAccessor) this.chunks;
			if (TerrainBfs.verify) {
				this.legacysilicon$shadow = TerrainBfs.shadow((WorldRenderer) (Object) this, queue, this.chunks.chunks, s.legacysilicon$sizeX(),
					s.legacysilicon$sizeY(), s.legacysilicon$sizeZ(), this.renderDistance, camX, camZ, cull, view, frame);
				return original.call(queue);
			}
			TerrainBfs.run((WorldRenderer) (Object) this, queue, this.visibleChunks, this.chunks.chunks, s.legacysilicon$sizeX(),
				s.legacysilicon$sizeY(), s.legacysilicon$sizeZ(), this.renderDistance, camX, camZ, cull, view, frame);
			return true;
		}
		boolean empty = original.call(queue);
		if (empty && this.legacysilicon$shadow != null) {
			TerrainBfs.compare(this.visibleChunks, this.legacysilicon$shadow);
			this.legacysilicon$shadow = null;
		}
		return empty;
	}

	/**
	 * Vanilla rescans all 4096 blocks of the camera's chunk on every visibility update. The result only
	 * depends on those blocks and the camera block, so it is reused until the chunk is marked dirty or moves.
	 */
	@WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;getOpenChunkFaces(Lnet/minecraft/util/math/BlockPos;)Ljava/util/Set;"))
	private Set<Direction> legacysilicon$cachedOpenFaces(WorldRenderer self, BlockPos pos, Operation<Set<Direction>> original) {
		if (!Config.fastTerrainSetup) {
			return original.call(self, pos);
		}
		BuiltChunk chunk = ((BuiltChunkStorageInvoker) this.chunks).legacysilicon$getRenderedChunk(pos);
		int changes = chunk == null ? 0 : ((SliceHolder) chunk).legacysilicon$changes();
		long key = pos.asLong();
		if (this.legacysilicon$faces == null || chunk != this.legacysilicon$facesChunk || changes != this.legacysilicon$facesChanges
			|| key != this.legacysilicon$facesPos || this.world != this.legacysilicon$facesWorld) {
			this.legacysilicon$faces = original.call(self, pos);
			this.legacysilicon$facesChunk = chunk;
			this.legacysilicon$facesChanges = changes;
			this.legacysilicon$facesPos = key;
			this.legacysilicon$facesWorld = this.world;
		}
		if (TerrainBfs.verify) {
			if (original.call(self, pos).equals(this.legacysilicon$faces)) {
				TerrainBfs.facesVerified++;
			} else {
				TerrainBfs.facesMismatched++;
			}
		}
		// The caller mutates the set it gets back, so hand out a copy.
		EnumSet<Direction> copy = EnumSet.noneOf(Direction.class);
		copy.addAll(this.legacysilicon$faces);
		return copy;
	}

	@Inject(method = "renderEntities", at = @At("HEAD"))
	private void legacysilicon$beginEntities(Entity entity, CameraView view, float tickDelta, CallbackInfo ci) {
		this.legacysilicon$entityFrame++;
	}

	/** Entity pass looks up the world chunk for every visible section; cache columns for the frame. */
	@Redirect(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getChunk(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/chunk/Chunk;"))
	private Chunk legacysilicon$cachedChunk(ClientWorld world, BlockPos pos) {
		if (!Config.fastTerrainSetup) {
			return world.getChunk(pos);
		}
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		int slot = (cx & 63) << 6 | (cz & 63);
		long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
		if (this.legacysilicon$colStamp[slot] == this.legacysilicon$entityFrame && this.legacysilicon$colKeys[slot] == key) {
			return this.legacysilicon$colChunks[slot];
		}
		Chunk c = world.getChunk(pos);
		this.legacysilicon$colStamp[slot] = this.legacysilicon$entityFrame;
		this.legacysilicon$colKeys[slot] = key;
		this.legacysilicon$colChunks[slot] = c;
		return c;
	}
}
