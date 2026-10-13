package dev.legacysilicon;

/** CPU time spent per GL call category in the chunk draw loop. Only collected while the autotest asks for it. */
public final class DrawTiming {
	public static boolean enabled;
	public static long matrixNs;
	public static long bindNs;
	public static long drawNs;
	public static long chunks;

	private DrawTiming() {
	}

	public static void reset() {
		matrixNs = 0L;
		bindNs = 0L;
		drawNs = 0L;
		chunks = 0L;
	}
}
