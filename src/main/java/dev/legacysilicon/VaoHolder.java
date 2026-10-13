package dev.legacysilicon;

/** Implemented on vanilla's VertexBuffer by VertexBufferMixin. */
public interface VaoHolder {
	/** Vertex array object that captures this VBO's BLOCK-format pointers; created on first use. */
	int legacysilicon$getVao();
}
