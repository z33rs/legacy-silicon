package dev.legacysilicon.mixin;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import dev.legacysilicon.Config;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.util.GlAllocationUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {
	@Shadow private ByteBuffer buffer;
	@Shadow private IntBuffer intBuffer;
	@Shadow private ShortBuffer shortBuffer;
	@Shadow private FloatBuffer floatBuffer;
	@Shadow private int vertexCount;
	@Shadow private int currentElementId;
	@Shadow private VertexFormat format;
	@Shadow private double offsetX;
	@Shadow private double offsetY;
	@Shadow private double offsetZ;

	/**
	 * Vanilla grows by +2 MB each time and copies the whole buffer, so a dense chunk can reallocate
	 * a direct buffer many times per rebuild (and old direct buffers linger until GC). Doubling makes
	 * total copy cost linear and reallocation rare. grow() runs on every vertex, so the fast path
	 * (no growth needed) is kept to the same two comparisons vanilla does.
	 */
	@Inject(method = "grow", at = @At("HEAD"), cancellable = true)
	private void legacysilicon$growGeometric(int size, CallbackInfo ci) {
		if (!Config.fastBufferGrowth) {
			return;
		}
		ci.cancel();
		int nextByte = (this.vertexCount + 1) * this.format.getVertexSize() + this.format.getIndex(this.currentElementId);
		int capacity = this.buffer.capacity();
		if (size <= this.intBuffer.remaining() && nextByte < capacity) {
			return;
		}

		int intPos = this.intBuffer.position();
		long needed = Math.max((long) (intPos + size) * 4L, (long) nextByte + 1L);
		long newCap = Math.max(capacity, 2097152);
		while (newCap < needed) {
			newCap <<= 1;
		}
		if (newCap == capacity) {
			newCap <<= 1;
		}
		if (newCap > Integer.MAX_VALUE - 8) {
			newCap = Integer.MAX_VALUE - 8;
		}

		ByteBuffer grown = GlAllocationUtils.allocateByteBuffer((int) newCap);
		this.buffer.position(0);
		grown.put(this.buffer);
		grown.rewind();
		this.buffer = grown;
		this.floatBuffer = grown.asFloatBuffer();
		this.intBuffer = grown.asIntBuffer();
		this.intBuffer.position(intPos);
		this.shortBuffer = grown.asShortBuffer();
		this.shortBuffer.position(intPos << 1);
	}

	/**
	 * Vanilla boxes every quad index into Integer[], sorts with a Comparator, then permutes in place
	 * allocating an IntBuffer slice per moved quad. Here: one long[] of (distance desc, index) keys
	 * sorted with a primitive sort, then a single bulk read and ordered bulk write.
	 * Tie order matches vanilla's stable sort (ascending original index).
	 */
	@Inject(method = "sortQuads", at = @At("HEAD"), cancellable = true)
	private void legacysilicon$sortQuadsPrimitive(float cameraX, float cameraY, float cameraZ, CallbackInfo ci) {
		if (!Config.fastTranslucentSort) {
			return;
		}
		ci.cancel();
		int quads = this.vertexCount / 4;
		if (quads == 0) {
			return;
		}
		int vertexInts = this.format.getVertexSizeInteger();
		int quadInts = vertexInts * 4;
		float x = (float) (cameraX + this.offsetX);
		float y = (float) (cameraY + this.offsetY);
		float z = (float) (cameraZ + this.offsetZ);
		FloatBuffer fb = this.floatBuffer;

		long[] keys = new long[quads];
		for (int q = 0; q < quads; q++) {
			int base = q * quadInts;
			int v1 = base + vertexInts;
			int v2 = v1 + vertexInts;
			int v3 = v2 + vertexInts;
			float cx = (fb.get(base) + fb.get(v1) + fb.get(v2) + fb.get(v3)) * 0.25F - x;
			float cy = (fb.get(base + 1) + fb.get(v1 + 1) + fb.get(v2 + 1) + fb.get(v3 + 1)) * 0.25F - y;
			float cz = (fb.get(base + 2) + fb.get(v1 + 2) + fb.get(v2 + 2) + fb.get(v3 + 2)) * 0.25F - z;
			float d = cx * cx + cy * cy + cz * cz;
			// d >= 0, so its IEEE bits order like ints; invert so ascending sort = farthest first.
			int bits = Float.floatToIntBits(d) & 0x7FFFFFFF;
			keys[q] = ((long) (0x7FFFFFFF - bits) << 32) | q;
		}
		Arrays.sort(keys);

		int total = quads * quadInts;
		int[] src = new int[total];
		IntBuffer ib = this.intBuffer;
		ib.limit(ib.capacity());
		ib.position(0);
		ib.get(src, 0, total);
		ib.position(0);
		for (int i = 0; i < quads; i++) {
			ib.put(src, (int) keys[i] * quadInts, quadInts);
		}
	}
}
