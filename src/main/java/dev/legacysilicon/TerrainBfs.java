package dev.legacysilicon;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import dev.legacysilicon.mixin.ChunkInfoAccessor;
import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

/**
 * The visible-chunk flood fill from WorldRenderer.setupTerrain, rewritten for speed with identical output:
 * same chunks, same order, same directions, levels and flags, and the same frame-stamp side effects
 * (evaluated in vanilla's short-circuit order).
 *
 * Differences are only in how answers are fetched: see-through data from one cached long per helper,
 * neighbours by integer arithmetic on the storage grid instead of per-direction BlockPos objects.
 */
public final class TerrainBfs {
	private static final Direction[] DIRS = Direction.values();
	private static final int[] OPP = new int[6];
	private static final int[] DX = new int[6];
	private static final int[] DY = new int[6];
	private static final int[] DZ = new int[6];

	static {
		for (Direction d : DIRS) {
			int i = d.ordinal();
			OPP[i] = d.getOpposite().ordinal();
			DX[i] = d.getOffsetX() * 16;
			DY[i] = d.getOffsetY() * 16;
			DZ[i] = d.getOffsetZ() * 16;
		}
	}

	/**
	 * ChunkInfo is a package-private inner class, so it can't be named (or given a Mixin constructor invoker).
	 * Its constructor is looked up once from a live instance's class, which also works after remapping.
	 */
	private static MethodHandle newChunkInfo;

	private static void bindConstructor(Object sample) {
		try {
			Constructor<?> c = sample.getClass().getDeclaredConstructor(WorldRenderer.class, BuiltChunk.class, Direction.class, int.class);
			c.setAccessible(true);
			newChunkInfo = MethodHandles.lookup().unreflectConstructor(c)
				.asType(MethodType.methodType(Object.class, WorldRenderer.class, BuiltChunk.class, Direction.class, int.class));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Legacy Silicon: cannot access WorldRenderer.ChunkInfo constructor", e);
		}
	}

	private static Object newChunkInfo(WorldRenderer outer, BuiltChunk chunk, Direction dir, int level) {
		try {
			return (Object) newChunkInfo.invokeExact(outer, chunk, dir, level);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	/** Autotest: run vanilla for real and this algorithm in shadow, then compare. */
	public static boolean verify;
	public static int verifiedRuns;
	public static int mismatchedRuns;
	public static int facesVerified;
	public static int facesMismatched;
	/** Mismatches where re-running our search after vanilla's matched it: the world changed between the two runs. */
	public static int racedRuns;
	/** Verification: chunk publications held back so the world can't change mid-frame. */
	public static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> held = new java.util.concurrent.ConcurrentLinkedQueue<Object[]>();
	public static volatile boolean draining;

	/** Applies held publications in arrival order; called on the render thread before a visibility update. */
	public static void releaseHeld() {
		draining = true;
		try {
			Object[] e;
			while ((e = held.poll()) != null) {
				((BuiltChunk) e[0]).method_10159((net.minecraft.client.world.ChunkAssemblyHelper) e[1]);
			}
		} finally {
			draining = false;
		}
	}
	private static List<Object> lastSeeds;
	private static Object[] lastArgs;

	private TerrainBfs() {
	}

	/**
	 * Drains {@code queue} exactly like vanilla's loop, appending to {@code visible}.
	 * camX/camZ are vanilla's blockPos2 (camera chunk origin); cull is vanilla's bl2.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void run(WorldRenderer outer, Queue queue, List visible, BuiltChunk[] grid, int sizeX, int sizeY, int sizeZ,
			int renderDistance, int camX, int camZ, boolean cull, CameraView view, int frame) {
		int maxDist = renderDistance * 16;
		if (newChunkInfo == null && !queue.isEmpty()) {
			bindConstructor(queue.peek());
		}
		while (!queue.isEmpty()) {
			Object info = queue.poll();
			ChunkInfoAccessor ia = (ChunkInfoAccessor) info;
			BuiltChunk chunk = ia.legacysilicon$chunk();
			Direction dir = ia.legacysilicon$direction();
			visible.add(info);

			BlockPos p = chunk.getPos();
			int px = p.getX();
			int py = p.getY();
			int pz = p.getZ();
			byte flags = ia.legacysilicon$flags();
			int level = ia.legacysilicon$level();
			boolean checkThrough = cull && dir != null;
			long mask = checkThrough ? ((VisibilityMask) chunk.method_10170()).legacysilicon$mask() : 0L;
			int from = checkThrough ? OPP[dir.ordinal()] * 6 : 0;

			for (int d = 0; d < 6; d++) {
				// !bl2 || !chunkInfo.method_12341(direction.getOpposite())
				if (cull && (flags & 1 << OPP[d]) > 0) {
					continue;
				}
				// !bl2 || direction2 == null || helper.isVisibleThrough(direction2.getOpposite(), direction3)
				if (checkThrough && (mask & 1L << (from + d)) == 0L) {
					continue;
				}
				// getAdjacentChunk(blockPos2, chunk, direction3) != null
				int nx = px + DX[d];
				int ny = py + DY[d];
				int nz = pz + DZ[d];
				if (MathHelper.abs(camX - nx) > maxDist || ny < 0 || ny >= 256 || MathHelper.abs(camZ - nz) > maxDist) {
					continue;
				}
				int j = MathHelper.floorDiv(ny, 16);
				if (j < 0 || j >= sizeY) {
					continue;
				}
				int i = MathHelper.floorDiv(nx, 16) % sizeX;
				if (i < 0) {
					i += sizeX;
				}
				int k = MathHelper.floorDiv(nz, 16) % sizeZ;
				if (k < 0) {
					k += sizeZ;
				}
				BuiltChunk n = grid[(k * sizeY + j) * sizeX + i];
				if (n == null || !n.method_10156(frame) || !view.isBoxInFrustum(n.field_13615)) {
					continue;
				}
				Object next = newChunkInfo(outer, n, DIRS[d], level + 1);
				((ChunkInfoAccessor) next).legacysilicon$addFlags(flags, DIRS[d]);
				queue.add(next);
			}
		}
	}

	/** Verify mode: run on a copy with a private frame number, restoring the seeds' stamps afterwards. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static List shadow(WorldRenderer outer, Queue queue, BuiltChunk[] grid, int sizeX, int sizeY, int sizeZ,
			int renderDistance, int camX, int camZ, boolean cull, CameraView view, int frame) {
		lastSeeds = new ArrayList<Object>(queue);
		lastArgs = new Object[] {outer, grid, sizeX, sizeY, sizeZ, renderDistance, camX, camZ, cull, view, frame};
		return shadowRun(queue, frame ^ 0x40000000, outer, grid, sizeX, sizeY, sizeZ, renderDistance, camX, camZ, cull, view, frame);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static List shadowRun(java.util.Collection seeds, int shadowFrame, WorldRenderer outer, BuiltChunk[] grid, int sizeX, int sizeY, int sizeZ,
			int renderDistance, int camX, int camZ, boolean cull, CameraView view, int frame) {
		ArrayDeque copy = new ArrayDeque(seeds);
		for (Object seed : copy) {
			((ChunkInfoAccessor) seed).legacysilicon$chunk().method_10156(shadowFrame);
		}
		List out = new ArrayList();
		run(outer, copy, out, grid, sizeX, sizeY, sizeZ, renderDistance, camX, camZ, cull, view, shadowFrame);
		for (Object seed : seeds) {
			((ChunkInfoAccessor) seed).legacysilicon$chunk().method_10156(frame);
		}
		return out;
	}

	@SuppressWarnings("rawtypes")
	private static boolean sameList(List vanilla, List ours) {
		boolean same = vanilla.size() == ours.size();
		for (int i = 0; same && i < vanilla.size(); i++) {
			ChunkInfoAccessor a = (ChunkInfoAccessor) vanilla.get(i);
			ChunkInfoAccessor b = (ChunkInfoAccessor) ours.get(i);
			same = a.legacysilicon$chunk() == b.legacysilicon$chunk() && a.legacysilicon$direction() == b.legacysilicon$direction()
				&& a.legacysilicon$level() == b.legacysilicon$level() && a.legacysilicon$flags() == b.legacysilicon$flags();
		}
		return same;
	}

	/**
	 * Compares vanilla's result with the shadow run made just before it. On a mismatch, our search is run
	 * again now (after vanilla): if that matches, chunk data changed between the two runs (vanilla publishes
	 * some compiled chunks from worker threads), so it is not a logic difference.
	 */
	@SuppressWarnings("rawtypes")
	public static void compare(List vanilla, List ours) {
		if (sameList(vanilla, ours)) {
			verifiedRuns++;
			return;
		}
		Object[] a = lastArgs;
		int frame = (Integer) a[10];
		List again = shadowRun(lastSeeds, frame ^ 0x20000000, (WorldRenderer) a[0], (BuiltChunk[]) a[1], (Integer) a[2], (Integer) a[3],
			(Integer) a[4], (Integer) a[5], (Integer) a[6], (Integer) a[7], (Boolean) a[8], (CameraView) a[9], frame);
		if (sameList(vanilla, again)) {
			racedRuns++;
		} else {
			mismatchedRuns++;
			if (mismatchedRuns <= 3) {
				Autotest.log("bfs     MISMATCH: vanilla " + vanilla.size() + " chunks vs ours " + ours.size() + " (re-run " + again.size() + ")");
			}
		}
	}
}
