package dev.legacysilicon.region;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * All pages of one render layer in one region. New pages are sized from what regions of this layer
 * typically need, so most region layers live in a single page (one multi-draw), and a full page is
 * followed by another instead of being copied into a bigger one. Empty pages are freed immediately.
 */
final class LayerPages {
	/** Smallest page, in vertices (~112 KB). */
	private static final int MIN_VERTICES = 4096;
	/**
	 * Per render layer: running average of how many vertices a region layer peaked at. Seeded with sizes
	 * measured in a dense forest at render distance 16 (solid, cutout-mipped, cutout, translucent), so a
	 * fresh world doesn't start every region with tiny pages.
	 */
	private static final int[] TYPICAL = {65536, 32768, 16384, 16384};
	static int extraPages;

	final Region region;
	final int layer;
	final List<LayerBuffer> pages = new ArrayList<LayerBuffer>(2);
	private int used;
	private int peakUsed;

	LayerPages(Region region, int layer) {
		this.region = region;
		this.layer = layer;
	}

	Slice upload(ByteBuffer data) {
		int vertices = data.remaining() / LayerBuffer.VERTEX_BYTES;
		Slice slice = null;
		for (int i = 0; i < this.pages.size() && slice == null; i++) {
			slice = this.pages.get(i).tryUpload(data);
		}
		if (slice == null) {
			int allocated = 0;
			for (LayerBuffer p : this.pages) {
				allocated += p.capacity;
			}
			int size;
			if (this.pages.isEmpty()) {
				size = Math.max(Math.max(MIN_VERTICES, vertices * 4), TYPICAL[this.layer]);
			} else {
				// Each extra page doubles the layer's total, so even huge regions need only a few pages (draws).
				extraPages++;
				size = Math.max(Math.max(MIN_VERTICES, vertices * 2), allocated);
			}
			LayerBuffer page = new LayerBuffer(this.region, this.layer, size);
			this.pages.add(page);
			slice = page.tryUpload(data);
		}
		this.used += vertices;
		this.peakUsed = Math.max(this.peakUsed, this.used);
		return slice;
	}

	void release(Slice slice) {
		LayerBuffer page = slice.owner;
		page.release(slice);
		this.used -= slice.count;
		if (page.isEmpty()) {
			page.delete();
			this.pages.remove(page);
		}
	}

	boolean isEmpty() {
		return this.pages.isEmpty();
	}

	void delete() {
		if (this.peakUsed > 0) {
			TYPICAL[this.layer] = Math.max(MIN_VERTICES, (TYPICAL[this.layer] * 3 + this.peakUsed) / 4);
		}
		for (LayerBuffer p : this.pages) {
			p.delete();
		}
		this.pages.clear();
	}
}
