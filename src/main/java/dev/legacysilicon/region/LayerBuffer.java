package dev.legacysilicon.region;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;

/**
 * One fixed-size GL buffer ("page") holding chunk meshes of one render layer in one region, with a
 * first-fit allocator (units: vertices). Pages never resize: a full region layer gets another page
 * (see {@link LayerPages}), so there is never a copy or GPU readback stall while terrain loads.
 */
final class LayerBuffer {
	static final int VERTEX_BYTES = 28;

	final Region region;
	final int layer;
	int vbo;
	final int capacity; // vertices
	private final List<Slice> live = new ArrayList<Slice>();
	private int used;
	/** Sorted, coalesced free ranges as [start, length] pairs. */
	private int[] free = new int[16];
	private int freeCount;

	// Per-frame draw list, filled by the renderer.
	int stamp = -1;
	IntBuffer firsts = BufferUtils.createIntBuffer(64);
	IntBuffer counts = BufferUtils.createIntBuffer(64);

	LayerBuffer(Region region, int layer, int capacityVertices) {
		this.region = region;
		this.layer = layer;
		this.capacity = capacityVertices;
		this.vbo = GL15.glGenBuffers();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) this.capacity * VERTEX_BYTES, GL15.GL_DYNAMIC_DRAW);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		this.freeCount = 1;
		this.free[0] = 0;
		this.free[1] = this.capacity;
	}

	boolean isEmpty() {
		return this.live.isEmpty();
	}

	int usedVertices() {
		return this.used;
	}

	/** Copies {@code data} (already region-relative) into a new slice, or returns null if it doesn't fit. */
	Slice tryUpload(ByteBuffer data) {
		int vertices = data.remaining() / VERTEX_BYTES;
		int at = allocate(vertices);
		if (at < 0) {
			return null;
		}
		Slice slice = new Slice(this, at, vertices);
		this.live.add(slice);
		this.used += vertices;
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
		GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) at * VERTEX_BYTES, data);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		return slice;
	}

	void release(Slice slice) {
		if (this.live.remove(slice)) {
			this.used -= slice.count;
			insertFree(slice.first, slice.count);
		}
	}

	void delete() {
		if (this.vbo != 0) {
			GL15.glDeleteBuffers(this.vbo);
			this.vbo = 0;
		}
	}

	/** Adds a slice to this frame's multi-draw list. */
	void queue(Slice slice) {
		if (!this.firsts.hasRemaining()) {
			this.firsts = growInts(this.firsts);
			this.counts = growInts(this.counts);
		}
		this.firsts.put(slice.first);
		this.counts.put(slice.count);
	}

	private static IntBuffer growInts(IntBuffer old) {
		IntBuffer n = BufferUtils.createIntBuffer(old.capacity() * 2);
		old.flip();
		n.put(old);
		return n;
	}

	private int allocate(int vertices) {
		if (vertices == 0) {
			return 0;
		}
		for (int i = 0; i < this.freeCount; i++) {
			int start = this.free[i * 2];
			int len = this.free[i * 2 + 1];
			if (len >= vertices) {
				if (len == vertices) {
					removeFree(i);
				} else {
					this.free[i * 2] = start + vertices;
					this.free[i * 2 + 1] = len - vertices;
				}
				return start;
			}
		}
		return -1;
	}

	private void insertFree(int start, int len) {
		if (len == 0) {
			return;
		}
		int i = 0;
		while (i < this.freeCount && this.free[i * 2] < start) {
			i++;
		}
		// Merge with previous / next neighbours where they touch.
		boolean mergePrev = i > 0 && this.free[(i - 1) * 2] + this.free[(i - 1) * 2 + 1] == start;
		boolean mergeNext = i < this.freeCount && start + len == this.free[i * 2];
		if (mergePrev && mergeNext) {
			this.free[(i - 1) * 2 + 1] += len + this.free[i * 2 + 1];
			removeFree(i);
		} else if (mergePrev) {
			this.free[(i - 1) * 2 + 1] += len;
		} else if (mergeNext) {
			this.free[i * 2] = start;
			this.free[i * 2 + 1] += len;
		} else {
			if ((this.freeCount + 1) * 2 > this.free.length) {
				this.free = Arrays.copyOf(this.free, this.free.length * 2);
			}
			System.arraycopy(this.free, i * 2, this.free, i * 2 + 2, (this.freeCount - i) * 2);
			this.free[i * 2] = start;
			this.free[i * 2 + 1] = len;
			this.freeCount++;
		}
	}

	private void removeFree(int i) {
		System.arraycopy(this.free, i * 2 + 2, this.free, i * 2, (this.freeCount - i - 1) * 2);
		this.freeCount--;
	}
}
