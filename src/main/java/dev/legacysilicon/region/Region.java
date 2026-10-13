package dev.legacysilicon.region;

/** 8x4x8 chunk sections (128 x 64 x 128 blocks); shared buffer pages per render layer. */
final class Region {
	static final int SHIFT_XZ = 7;
	static final int SHIFT_Y = 6;

	final long key;
	final int originX;
	final int originY;
	final int originZ;
	final LayerPages[] layers = new LayerPages[4];

	Region(long key, int originX, int originY, int originZ) {
		this.key = key;
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
	}

	static long key(int blockX, int blockY, int blockZ) {
		long rx = blockX >> SHIFT_XZ;
		long ry = blockY >> SHIFT_Y;
		long rz = blockZ >> SHIFT_XZ;
		return (rx & 0x3FFFFFL) << 42 | (rz & 0x3FFFFFL) << 20 | (ry & 0xFFFFFL);
	}

	LayerPages layer(int ordinal) {
		LayerPages b = this.layers[ordinal];
		if (b == null) {
			b = new LayerPages(this, ordinal);
			this.layers[ordinal] = b;
		}
		return b;
	}

	boolean isEmpty() {
		for (LayerPages b : this.layers) {
			if (b != null && !b.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	void delete() {
		for (LayerPages b : this.layers) {
			if (b != null) {
				b.delete();
			}
		}
	}
}
