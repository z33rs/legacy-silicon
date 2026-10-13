package dev.legacysilicon;

import net.minecraft.client.MinecraftClient;

/** Runs once per frame, just before the world renders. Benchmark logging and the dev autotest only. */
public final class FrameHook {
	private static long windowStart;
	private static int frames;
	private static long worstFrameNs;
	private static long lastFrameNs;

	private FrameHook() {
	}

	public static void onFrame(MinecraftClient client, float tickDelta) {
		if (Autotest.ENABLED) {
			Autotest.onFrame(client, tickDelta);
		}
		if (Config.benchmarkLog) {
			benchmarkTick();
		}
	}

	private static void benchmarkTick() {
		long now = System.nanoTime();
		if (windowStart == 0L) {
			windowStart = now;
			lastFrameNs = now;
			return;
		}
		long dt = now - lastFrameNs;
		lastFrameNs = now;
		if (dt > worstFrameNs) {
			worstFrameNs = dt;
		}
		frames++;
		long elapsed = now - windowStart;
		if (elapsed >= 5_000_000_000L) {
			double fps = frames * 1e9 / elapsed;
			LegacySilicon.LOGGER.info(String.format("[bench] avg %.1f FPS (%.2f ms), worst frame %.2f ms",
				fps, 1000.0 / fps, worstFrameNs / 1e6));
			windowStart = now;
			frames = 0;
			worstFrameNs = 0L;
		}
	}
}
