package dev.legacysilicon.region;

/** One chunk's mesh for one render layer, living inside a region's shared buffer. Units are vertices. */
public final class Slice {
	final LayerBuffer owner;
	int first;
	final int count;

	Slice(LayerBuffer owner, int first, int count) {
		this.owner = owner;
		this.first = first;
		this.count = count;
	}
}
