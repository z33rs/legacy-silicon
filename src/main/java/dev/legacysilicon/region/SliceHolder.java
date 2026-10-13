package dev.legacysilicon.region;

/** Implemented on BuiltChunk: per-layer region slices (null = nothing uploaded) and a change counter. */
public interface SliceHolder {
	Slice[] legacysilicon$getSlices();

	int legacysilicon$changes();
}
