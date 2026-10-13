package dev.legacysilicon.region;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.legacysilicon.Config;
import dev.legacysilicon.DrawTiming;
import dev.legacysilicon.LegacySilicon;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

/**
 * Sodium-style chunk batching for 1.9's fixed-function pipeline.
 *
 * Chunk meshes are stored in per-region shared buffers with positions rewritten relative to the region
 * origin (including vanilla's 1.000001 seam scale), so a whole region draws with one matrix and one
 * glMultiDrawArrays. Opaque layers are grouped by region; the translucent layer keeps vanilla's exact
 * back-to-front chunk order and only batches chunks that are already consecutive in it.
 *
 * All methods run on the render thread.
 */
public final class ChunkRegions {
	/** Decided once at startup; vanilla per-chunk VBOs are not filled while this is on (except in the autotest). */
	public static boolean active;
	/** Autotest: also keep vanilla's per-chunk VBOs filled so the pixel test can render both ways. */
	public static boolean dualUpload;

	private static final float SEAM = 1.000001F;
	private static final float SEAM_SHIFT = 8.0F * SEAM - 8.0F;

	private static final Map<Long, Region> REGIONS = new HashMap<Long, Region>();
	private static final List<LayerBuffer> ORDER = new ArrayList<LayerBuffer>();
	private static final FloatBuffer FB = BufferUtils.createFloatBuffer(16);
	private static final float[] BASE = new float[16];
	private static ByteBuffer scratch = BufferUtils.createByteBuffer(1 << 20);
	private static int frameStamp;
	public static int oddUploads;

	private ChunkRegions() {
	}

	public static void upload(BuiltChunk chunk, RenderLayer layer, ByteBuffer data) {
		Slice[] slices = ((SliceHolder) chunk).legacysilicon$getSlices();
		int ordinal = layer.ordinal();
		if (slices[ordinal] != null) {
			release(slices[ordinal]);
			slices[ordinal] = null;
		}
		int bytes = data.limit();
		int partial = bytes % LayerBuffer.VERTEX_BYTES;
		if (partial != 0) {
			// Vanilla race: a cancelled build's buffer can be reused by a worker while its upload is still
			// queued, so the length isn't whole vertices. Vanilla just copies the bytes (and rebuilds the chunk
			// shortly after); do the same with whole vertices only instead of reading past the end.
			if (oddUploads++ == 0) {
				LegacySilicon.LOGGER.warn("Chunk upload of {} bytes is not whole vertices (buffer capacity {}); using {} bytes",
					bytes, data.capacity(), bytes - partial);
			}
			bytes -= partial;
		}
		if (bytes == 0) {
			return;
		}

		BlockPos pos = chunk.getPos();
		long key = Region.key(pos.getX(), pos.getY(), pos.getZ());
		Region region = REGIONS.get(key);
		if (region == null) {
			region = new Region(key, pos.getX() >> Region.SHIFT_XZ << Region.SHIFT_XZ,
				pos.getY() >> Region.SHIFT_Y << Region.SHIFT_Y, pos.getZ() >> Region.SHIFT_XZ << Region.SHIFT_XZ);
			REGIONS.put(key, region);
		}
		float dx = pos.getX() - region.originX + SEAM_SHIFT;
		float dy = pos.getY() - region.originY + SEAM_SHIFT;
		float dz = pos.getZ() - region.originZ + SEAM_SHIFT;

		if (scratch.capacity() < bytes) {
			scratch = BufferUtils.createByteBuffer(Integer.highestOneBit(bytes) << 1);
		}
		scratch.clear();
		int oldLimit = data.limit();
		data.limit(bytes).position(0);
		scratch.put(data);
		data.limit(oldLimit).position(0);
		scratch.flip();
		scratch.order(ByteOrder.nativeOrder());
		// Bake the chunk's place in the region (vanilla's per-chunk translate * seam matrix) into positions.
		for (int v = 0; v < bytes; v += LayerBuffer.VERTEX_BYTES) {
			scratch.putFloat(v, scratch.getFloat(v) * SEAM + dx);
			scratch.putFloat(v + 4, scratch.getFloat(v + 4) * SEAM + dy);
			scratch.putFloat(v + 8, scratch.getFloat(v + 8) * SEAM + dz);
		}
		slices[ordinal] = region.layer(ordinal).upload(scratch);
	}

	private static int failedUploads;

	public static void uploadFailed(BuiltChunk chunk, RenderLayer layer, RuntimeException e) {
		Slice[] slices = ((SliceHolder) chunk).legacysilicon$getSlices();
		if (slices[layer.ordinal()] != null) {
			release(slices[layer.ordinal()]);
			slices[layer.ordinal()] = null;
		}
		chunk.method_10162(false); // schedule a rebuild so the layer comes back
		if (failedUploads++ < 3) {
			LegacySilicon.LOGGER.error("Region upload failed for a chunk; skipped it and scheduled a rebuild", e);
		}
	}

	/** Chunk moved to a new position or was deleted: its meshes no longer belong to that region. */
	public static void releaseAll(BuiltChunk chunk) {
		Slice[] slices = ((SliceHolder) chunk).legacysilicon$getSlices();
		for (int i = 0; i < slices.length; i++) {
			if (slices[i] != null) {
				release(slices[i]);
				slices[i] = null;
			}
		}
	}

	private static void release(Slice slice) {
		Region region = slice.owner.region;
		region.layers[slice.owner.layer].release(slice);
		if (region.isEmpty()) {
			region.delete();
			REGIONS.remove(region.key);
		}
	}

	public static void render(RenderLayer layer, List<BuiltChunk> chunks, double vx, double vy, double vz) {
		int ordinal = layer.ordinal();
		boolean timing = DrawTiming.enabled;
		frameStamp++;
		GlStateManager.pushMatrix();
		readBase();

		if (layer == RenderLayer.TRANSLUCENT) {
			// Keep vanilla's back-to-front order exactly; batch only runs of consecutive same-region chunks.
			LayerBuffer current = null;
			for (int i = 0, n = chunks.size(); i < n; i++) {
				Slice s = ((SliceHolder) chunks.get(i)).legacysilicon$getSlices()[ordinal];
				if (s == null) {
					continue;
				}
				if (s.owner != current) {
					if (current != null) {
						flushAndDraw(current, vx, vy, vz, timing);
					}
					current = s.owner;
					current.firsts.clear();
					current.counts.clear();
				}
				current.queue(s);
			}
			if (current != null) {
				flushAndDraw(current, vx, vy, vz, timing);
			}
		} else {
			// Depth-tested opaque layers: group by region, regions in order of first appearance (front to back).
			ORDER.clear();
			for (int i = 0, n = chunks.size(); i < n; i++) {
				Slice s = ((SliceHolder) chunks.get(i)).legacysilicon$getSlices()[ordinal];
				if (s == null) {
					continue;
				}
				LayerBuffer b = s.owner;
				if (b.stamp != frameStamp) {
					b.stamp = frameStamp;
					b.firsts.clear();
					b.counts.clear();
					ORDER.add(b);
				}
				b.queue(s);
			}
			for (int i = 0, n = ORDER.size(); i < n; i++) {
				flushAndDraw(ORDER.get(i), vx, vy, vz, timing);
			}
		}

		GlStateManager.popMatrix();
		GLX.gl15BindBuffer(GLX.arrayBuffer, 0);
		GlStateManager.clearColor();
	}

	private static void flushAndDraw(LayerBuffer b, double vx, double vy, double vz, boolean timing) {
		b.firsts.flip();
		b.counts.flip();
		drawBatchLoaded(b, vx, vy, vz, timing);
	}

	private static void readBase() {
		FB.clear();
		GlStateManager.getFloat(GL11.GL_MODELVIEW_MATRIX, FB);
		FB.get(BASE);
	}

	private static void drawBatchLoaded(LayerBuffer b, double vx, double vy, double vz, boolean timing) {
		long t = timing ? System.nanoTime() : 0L;
		FB.clear();
		FB.put(BASE).flip();
		GL11.glLoadMatrix(FB);
		Region r = b.region;
		GlStateManager.translate((float) (r.originX - vx), (float) (r.originY - vy), (float) (r.originZ - vz));
		long t1 = 0L;
		if (timing) {
			t1 = System.nanoTime();
			DrawTiming.matrixNs += t1 - t;
		}
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, b.vbo);
		GlStateManager.method_12307(3, GL11.GL_FLOAT, 28, 0);
		GlStateManager.method_12311(4, GL11.GL_UNSIGNED_BYTE, 28, 12);
		GlStateManager.method_12302(2, GL11.GL_FLOAT, 28, 16);
		GLX.gl13ClientActiveTexture(GLX.lightmapTextureUnit);
		GlStateManager.method_12302(2, GL11.GL_SHORT, 28, 24);
		GLX.gl13ClientActiveTexture(GLX.textureUnit);
		long t2 = 0L;
		if (timing) {
			t2 = System.nanoTime();
			DrawTiming.bindNs += t2 - t1;
		}
		GL14.glMultiDrawArrays(GL11.GL_QUADS, b.firsts, b.counts);
		if (timing) {
			DrawTiming.drawNs += System.nanoTime() - t2;
			DrawTiming.chunks += b.firsts.remaining();
		}
	}

	/** Autotest: regions, pages, GPU bytes allocated vs holding meshes, extra pages created. */
	public static String stats() {
		long cap = 0L;
		long used = 0L;
		int pages = 0;
		for (Region r : REGIONS.values()) {
			for (LayerPages lp : r.layers) {
				if (lp != null) {
					for (LayerBuffer b : lp.pages) {
						pages++;
						cap += (long) b.capacity * LayerBuffer.VERTEX_BYTES;
						used += (long) b.usedVertices() * LayerBuffer.VERTEX_BYTES;
					}
				}
			}
		}
		return String.format("%d regions, %d pages, %.1f MB allocated, %.1f MB used (%.0f%%), %d extra pages created, %d partial-vertex uploads",
			REGIONS.size(), pages, cap / 1048576.0, used / 1048576.0, cap == 0 ? 0.0 : used * 100.0 / cap, LayerPages.extraPages, oddUploads);
	}

	public static int extraPages() {
		return LayerPages.extraPages;
	}

	public static int regionCount() {
		return REGIONS.size();
	}

}
