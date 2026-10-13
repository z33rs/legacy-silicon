package dev.legacysilicon;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

/**
 * Dev-only measurement: how much does the driver charge per draw call, and does glMultiDrawArrays
 * (Sodium's batching primitive) avoid that cost? Draws zero-area quads so only submission cost counts.
 * Includes glFinish so work the driver defers to flush time is counted too.
 */
final class DrawCallBench {
	private static final int CHUNKS = 1500;
	private static final int QUADS_PER_CHUNK = 1; // tiny draws: isolate per-call driver cost from GPU vertex work

	private DrawCallBench() {
	}

	static void run() {
		int verts = CHUNKS * QUADS_PER_CHUNK * 4;
		ByteBuffer data = BufferUtils.createByteBuffer(verts * 28); // all zeros: degenerate, nothing rasterized
		int vbo = GL15.glGenBuffers();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
		GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
		GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();
		FloatBuffer m = BufferUtils.createFloatBuffer(16);
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, m);

		IntBuffer firstAll = BufferUtils.createIntBuffer(CHUNKS);
		IntBuffer countAll = BufferUtils.createIntBuffer(CHUNKS);
		for (int i = 0; i < CHUNKS; i++) {
			firstAll.put(i * QUADS_PER_CHUNK * 4);
			countAll.put(QUADS_PER_CHUNK * 4);
		}
		firstAll.flip();
		countAll.flip();

		long[] best = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
		long[] submit = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
		long s;
		for (int iter = 0; iter < 30; iter++) {
			// A: vanilla-like, one matrix load + one draw per chunk.
			GL11.glFinish();
			long t = System.nanoTime();
			for (int i = 0; i < CHUNKS; i++) {
				GL11.glLoadMatrix(m);
				GL11.glDrawArrays(GL11.GL_QUADS, i * QUADS_PER_CHUNK * 4, QUADS_PER_CHUNK * 4);
			}
			s = System.nanoTime();
			GL11.glFinish();
			submit[0] = Math.min(submit[0], s - t);
			best[0] = Math.min(best[0], System.nanoTime() - t);

			// B: Sodium-like regions, ~8x4x8 chunks per region: one matrix + one multi-draw per region.
			GL11.glFinish();
			t = System.nanoTime();
			int perRegion = 50;
			for (int start = 0; start < CHUNKS; start += perRegion) {
				GL11.glLoadMatrix(m);
				firstAll.limit(Math.min(CHUNKS, start + perRegion)).position(start);
				countAll.limit(Math.min(CHUNKS, start + perRegion)).position(start);
				GL14.glMultiDrawArrays(GL11.GL_QUADS, firstAll, countAll);
			}
			firstAll.clear();
			countAll.clear();
			s = System.nanoTime();
			GL11.glFinish();
			submit[1] = Math.min(submit[1], s - t);
			best[1] = Math.min(best[1], System.nanoTime() - t);

			// C: one multi-draw for everything.
			GL11.glFinish();
			t = System.nanoTime();
			GL11.glLoadMatrix(m);
			GL14.glMultiDrawArrays(GL11.GL_QUADS, firstAll, countAll);
			s = System.nanoTime();
			GL11.glFinish();
			submit[2] = Math.min(submit[2], s - t);
			best[2] = Math.min(best[2], System.nanoTime() - t);
		}

		GL11.glPopMatrix();
		GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		GL15.glDeleteBuffers(vbo);
		Autotest.log(String.format("drawcalls %d tiny draws (submit / incl. finish): separate %.2f / %.2f ms | %d region multi-draws %.2f / %.2f ms | 1 multi-draw %.2f / %.2f ms",
			CHUNKS, submit[0] / 1e6, best[0] / 1e6, (CHUNKS + 49) / 50, submit[1] / 1e6, best[1] / 1e6, submit[2] / 1e6, best[2] / 1e6));
	}
}
