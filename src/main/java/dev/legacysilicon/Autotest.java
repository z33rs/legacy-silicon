package dev.legacysilicon;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import dev.legacysilicon.mixin.ProfilerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.ScreenshotUtils;

/**
 * Dev-only harness, enabled with -Dlegacysilicon.autotest=true. Never active in normal play.
 *
 *  1. Self-test: BufferBuilder replacements produce byte-identical output to vanilla.
 *  2. In a world, for each render distance:
 *     SETTLE  - pan the camera until every chunk is built and the builder has been idle for 3 s
 *     PIXEL   - render the same frame vanilla / optimized / vanilla and compare every pixel
 *     BENCH   - alternate vanilla/optimized 10 s segments (V O V O), each sweeping exactly 360 degrees
 *     PROFILE - vanilla profiler breakdown (ms per frame) for vanilla and optimized
 *  3. Quit. Results: run/autotest-results.txt, screenshots and diff images in run/screenshots/.
 */
public final class Autotest {
	public static final boolean ENABLED = Boolean.getBoolean("legacysilicon.autotest");
	public static volatile boolean forceProfiler;

	private static final int[] DISTANCES = parseDistances(System.getProperty("legacysilicon.autotest.distances", "12,16,24,32"));
	/** Scene: x,y,z,pitch. Default: above the taiga forest at the test world's spawn. */
	private static final String SCENE = System.getProperty("legacysilicon.autotest.scene", "-55,92,-3,20");
	private static final long SEGMENT_NS = 10_000_000_000L;
	private static final long PROFILE_NS = 8_000_000_000L;

	private enum Stage { JOIN, SETTLE, PIXEL, TIMING, BENCH, PROFILE, FLY_SETTLE, FLY, DONE }

	private static Stage stage = Stage.JOIN;
	private static long stageStart;
	private static int distIndex;
	private static int step;
	private static long lastBusy;
	private static float appliedYaw;
	private static long panStart;
	private static long panPeriod = SEGMENT_NS;

	private static long lastFrame;
	private static long[] frameTimes = new long[4096];
	private static int frameCount;
	private static final double[][] segFps = new double[2][2];
	private static final double[][] segP99 = new double[2][2];
	private static Map<String, Long> profileStart;
	private static int profileFrames;
	private static int pixelPass;
	private static int pixelFail;
	private static int pixelAttempts;
	private static final String[] TIMING_NAMES = {"vanilla calls", "per-chunk exact", "region multi-draw"};
	private static final long TIMING_NS = 5_000_000_000L;

	private static final StringBuilder report = new StringBuilder();

	// Fly mode (-Dlegacysilicon.autotest.mode=fly): straight-line flights through loading terrain.
	private static final boolean FLY_MODE = "fly".equals(System.getProperty("legacysilicon.autotest.mode"));
	private static final String[] FLY_PASSES = {"warmup", "vanilla", "optimized", "vanilla", "optimized"};
	private static final long FLY_NS = 30_000_000_000L;
	private static final double FLY_SPEED = 20.0; // blocks per second, about elytra cruising speed
	private static int flyPass;
	private static long lastTp;
	private static int extraPagesStart;

	private Autotest() {
	}

	public static void selfTestBuffers() {
		DrawCallBench.run();
		boolean grow = Config.fastBufferGrowth;
		boolean sort = Config.fastTranslucentSort;
		try {
			// Chunk meshing path (putArray), which vanilla handles correctly: outputs must be identical.
			for (int seed = 0; seed < 20; seed++) {
				int quads = 2000 + seed * 3000; // up to ~6.6 MB: crosses several grow steps
				Config.fastBufferGrowth = false;
				Config.fastTranslucentSort = false;
				int[] vanilla = build(seed, quads);
				Config.fastBufferGrowth = true;
				Config.fastTranslucentSort = true;
				int[] fast = build(seed, quads);
				if (!Arrays.equals(vanilla, fast)) {
					log("BufferBuilder MISMATCH seed=" + seed + " quads=" + quads);
					return;
				}
			}
			log("BufferBuilder grow+sort: 20/20 cases identical to vanilla");

			// Per-vertex path from a tiny buffer: vanilla overflows on its 2nd grow; ours must not.
			Config.fastBufferGrowth = true;
			BufferBuilder b = new BufferBuilder(64);
			b.begin(7, VertexFormats.BLOCK);
			for (int i = 0; i < 400_000; i++) {
				b.vertex(i, 0, 0).color(255, 255, 255, 255).texture(0, 0).texture2(0, 0);
				b.next();
			}
			b.end();
			log("per-vertex growth to " + b.getByteBuffer().capacity() + " bytes OK (vanilla would overflow)");
		} finally {
			Config.fastBufferGrowth = grow;
			Config.fastTranslucentSort = sort;
		}
	}

	private static int[] build(int seed, int quads) {
		Random r = new Random(seed);
		BufferBuilder b = new BufferBuilder(64);
		b.begin(7, VertexFormats.BLOCK);
		int[] quad = new int[28]; // 4 vertices * 7 ints (BLOCK format)
		for (int q = 0; q < quads; q++) {
			for (int v = 0; v < 4; v++) {
				int o = v * 7;
				quad[o] = Float.floatToRawIntBits(r.nextFloat() * 16);
				quad[o + 1] = Float.floatToRawIntBits(r.nextFloat() * 16);
				quad[o + 2] = Float.floatToRawIntBits(r.nextFloat() * 16);
				quad[o + 3] = r.nextInt();
				quad[o + 4] = Float.floatToRawIntBits(r.nextFloat());
				quad[o + 5] = Float.floatToRawIntBits(r.nextFloat());
				quad[o + 6] = r.nextInt();
			}
			b.putArray(quad);
		}
		b.sortQuads(r.nextFloat() * 32 - 8, r.nextFloat() * 32 - 8, r.nextFloat() * 32 - 8);
		b.end();
		IntBuffer ints = b.getByteBuffer().asIntBuffer();
		int[] out = new int[ints.remaining()];
		ints.get(out);
		return out;
	}

	/** Called once per frame, before vanilla renders the world. */
	public static void onFrame(MinecraftClient client, float tickDelta) {
		if (client.world == null || client.player == null || stage == Stage.DONE) {
			return;
		}
		// Ignore stray clicks/mouse moves on the test window: no mouse-look, no pause menu.
		client.focused = false;
		if (client.currentScreen != null) {
			client.currentScreen = null;
		}
		long now = System.nanoTime();
		long frameNs = lastFrame == 0L ? 0L : now - lastFrame;
		lastFrame = now;
		if (!client.worldRenderer.method_12339()) {
			lastBusy = now;
		}
		long elapsed = now - stageStart;

		switch (stage) {
			case JOIN: {
				String[] sc = SCENE.split(",");
				client.player.sendChatMessage("/gamerule doDaylightCycle false");
				client.player.sendChatMessage("/time set 6000");
				client.player.sendChatMessage("/weather clear 1000000");
				client.player.sendChatMessage("/tp @p " + sc[0] + " " + sc[1] + " " + sc[2] + " 0 " + sc[3]);
				log("in world " + client.width + "x" + client.height + ", scene " + SCENE);
				if (FLY_MODE) {
					client.options.viewDistance = DISTANCES[0];
					beginFlySettle(client, now);
				} else {
					beginSettle(client, now);
				}
				break;
			}
			case SETTLE:
				pan(client, now);
				if ((elapsed > 8_000_000_000L && now - lastBusy > 3_000_000_000L) || elapsed > 180_000_000_000L) {
					log(String.format("--- render distance %d: settled in %.0fs%s, %s", DISTANCES[distIndex], elapsed / 1e9,
						elapsed > 180_000_000_000L ? " (TIMEOUT, builder still busy)" : "", client.worldRenderer.getChunksDebugString()));
					enter(Stage.PIXEL, now);
				}
				break;
			case PIXEL:
				// One attempt per second; a vanilla/optimized/vanilla triple is only counted if both vanilla renders match.
				if (elapsed > (pixelAttempts + 1) * 1_000_000_000L) {
					pixelAttempts++;
					if (pixelCompare(client, tickDelta)) {
						step++;
						client.player.increaseTransforms(120.0F / 0.15F, 0.0F);
					}
				}
				if (step >= 3 || pixelAttempts >= 8) {
					TerrainBfs.verify = false;
					TerrainBfs.releaseHeld();
					log("regions " + dev.legacysilicon.region.ChunkRegions.stats());
					log(String.format("bfs     %d searches identical to vanilla, %d changed-world-between-runs (re-run matched), %d real differences; open-faces cache %d identical, %d different",
						TerrainBfs.verifiedRuns, TerrainBfs.racedRuns, TerrainBfs.mismatchedRuns, TerrainBfs.facesVerified, TerrainBfs.facesMismatched));
					pixelAttempts = 0;
					beginTiming(client, 0, now);
				}
				break;
			case TIMING:
				pan(client, now);
				if (elapsed >= TIMING_NS) {
					log(String.format("timing  %-24s per frame: matrix %.2f ms, bind %.2f ms, draw %.2f ms, total %.2f ms (%d chunk draws/frame)",
						TIMING_NAMES[step], DrawTiming.matrixNs / 1e6 / frameCount, DrawTiming.bindNs / 1e6 / frameCount,
						DrawTiming.drawNs / 1e6 / frameCount, (DrawTiming.matrixNs + DrawTiming.bindNs + DrawTiming.drawNs) / 1e6 / frameCount,
						DrawTiming.chunks / Math.max(1, frameCount)));
					if (step < 2) {
						beginTiming(client, step + 1, now);
					} else {
						DrawTiming.enabled = false;
						beginSegment(client, Stage.BENCH, 0, now);
					}
				} else {
					frameCount++;
				}
				break;
			case BENCH:
				pan(client, now);
				record(frameNs);
				if (elapsed >= SEGMENT_NS) {
					int mode = step % 2; // 0 = vanilla, 1 = optimized
					segFps[mode][step / 2] = frameCount * 1e9 / elapsed;
					segP99[mode][step / 2] = percentile99(frameCount) / 1e6;
					if (step == 3) {
						ScreenshotUtils.method_12154(FabricLoader.getInstance().getGameDir().toFile(),
							"autotest-rd" + DISTANCES[distIndex] + ".png", client.width, client.height, client.getFramebuffer());
						double v = (segFps[0][0] + segFps[0][1]) / 2;
						double o = (segFps[1][0] + segFps[1][1]) / 2;
						log(String.format("bench   vanilla %.1f FPS (segments %.1f / %.1f, 1%% low frame %.2f ms)", v, segFps[0][0], segFps[0][1],
							Math.max(segP99[0][0], segP99[0][1])));
						log(String.format("bench   optimized %.1f FPS (segments %.1f / %.1f, 1%% low frame %.2f ms)  => %+.1f%%", o, segFps[1][0], segFps[1][1],
							Math.max(segP99[1][0], segP99[1][1]), (o / v - 1) * 100));
						beginSegment(client, Stage.PROFILE, 0, now);
						forceProfiler = true;
					} else {
						beginSegment(client, Stage.BENCH, step + 1, now);
					}
				}
				break;
			case PROFILE:
				pan(client, now);
				if (profileStart == null && elapsed > 1_000_000_000L) {
					profileStart = new HashMap<String, Long>(((ProfilerAccessor) client.profiler).legacysilicon$getTimes());
					profileFrames = 0;
				}
				if (profileStart != null) {
					profileFrames++;
				}
				if (elapsed >= PROFILE_NS) {
					dumpProfile(client, step == 0 ? "vanilla" : "optimized");
					profileStart = null;
					if (step == 0) {
						beginSegment(client, Stage.PROFILE, 1, now);
					} else {
						forceProfiler = false;
						distIndex++;
						if (distIndex < DISTANCES.length) {
							beginSettle(client, now);
						} else {
							log(String.format("pixel test: %d identical, %d different", pixelPass, pixelFail));
							stage = Stage.DONE;
							client.scheduleStop();
						}
					}
				}
				break;
			case FLY_SETTLE:
				if ((elapsed > 8_000_000_000L && now - lastBusy > 3_000_000_000L) || elapsed > 60_000_000_000L) {
					enter(Stage.FLY, now);
					Config.setOptimizations(!FLY_PASSES[flyPass].equals("vanilla"));
					extraPagesStart = dev.legacysilicon.region.ChunkRegions.extraPages();
					lastTp = now;
				}
				break;
			case FLY:
				record(frameNs);
				if (now - lastTp >= 250_000_000L) {
					lastTp = now;
					String[] sc = SCENE.split(",");
					double z = Double.parseDouble(sc[2]) + FLY_SPEED * elapsed / 1e9;
					client.player.sendChatMessage("/tp @p " + sc[0] + " " + sc[1] + " " + String.format("%.2f", z) + " 0 " + sc[3]);
				}
				if (elapsed >= FLY_NS) {
					if (flyPass > 0) {
						long[] sorted = Arrays.copyOf(frameTimes, frameCount);
						Arrays.sort(sorted);
						int hitches = 0;
						for (int k = 0; k < frameCount; k++) {
							if (frameTimes[k] > 33_333_333L) {
								hitches++;
							}
						}
						log(String.format("fly     %-9s avg %.1f FPS, 1%% low %.2f ms, 0.1%% low %.2f ms, worst %.1f ms, %d frames >33ms; extra pages %d",
							FLY_PASSES[flyPass], frameCount * 1e9 / elapsed, sorted[Math.min(frameCount - 1, (int) (frameCount * 0.99))] / 1e6,
							sorted[Math.min(frameCount - 1, (int) (frameCount * 0.999))] / 1e6, sorted[frameCount - 1] / 1e6, hitches,
							dev.legacysilicon.region.ChunkRegions.extraPages() - extraPagesStart));
					}
					flyPass++;
					if (flyPass < FLY_PASSES.length) {
						beginFlySettle(client, now);
					} else {
						log("regions " + dev.legacysilicon.region.ChunkRegions.stats());
						stage = Stage.DONE;
						client.scheduleStop();
					}
				}
				break;
			default:
				break;
		}
	}

	private static void beginFlySettle(MinecraftClient client, long now) {
		String[] sc = SCENE.split(",");
		client.player.sendChatMessage("/tp @p " + sc[0] + " " + sc[1] + " " + sc[2] + " 0 " + sc[3]);
		Config.setOptimizations(true);
		enter(Stage.FLY_SETTLE, now);
	}

	private static void beginSettle(MinecraftClient client, long now) {
		client.options.viewDistance = DISTANCES[distIndex];
		Config.setOptimizations(true);
		panPeriod = 6_000_000_000L;
		enter(Stage.SETTLE, now);
		TerrainBfs.verify = true;
	}

	private static void beginTiming(MinecraftClient client, int which, long now) {
		enter(Stage.TIMING, now);
		step = which;
		panPeriod = TIMING_NS;
		Config.setOptimizations(true);
		Config.debugMatrixMode = which == 0 ? 0 : -1;
		Config.debugBindMode = 0;
		Config.debugRegions = which == 2 ? -1 : 0;
		DrawTiming.reset();
		DrawTiming.enabled = true;
	}

	private static void beginSegment(MinecraftClient client, Stage s, int seg, long now) {
		enter(s, now);
		step = seg;
		Config.setOptimizations(s == Stage.BENCH ? seg % 2 == 1 : seg == 1);
		panPeriod = s == Stage.BENCH ? SEGMENT_NS : PROFILE_NS;
	}

	private static void enter(Stage s, long now) {
		stage = s;
		stageStart = now;
		panStart = now;
		appliedYaw = 0.0F;
		step = 0;
		frameCount = 0;
	}

	/** Time-based pan: a full 360 degrees per panPeriod, so every segment sees the same set of views. */
	private static void pan(MinecraftClient client, long now) {
		float target = (float) ((now - panStart) / (double) panPeriod * 360.0);
		client.player.increaseTransforms((target - appliedYaw) / 0.15F, 0.0F);
		appliedYaw = target;
	}

	private static void record(long frameNs) {
		if (frameNs <= 0L) {
			return;
		}
		if (frameCount == frameTimes.length) {
			frameTimes = Arrays.copyOf(frameTimes, frameTimes.length * 2);
		}
		frameTimes[frameCount++] = frameNs;
	}

	private static long percentile99(int n) {
		if (n == 0) {
			return 0L;
		}
		long[] sorted = Arrays.copyOf(frameTimes, n);
		Arrays.sort(sorted);
		return sorted[Math.min(n - 1, (int) (n * 0.99))];
	}

	/**
	 * Renders the world three times in the same frame (same tickDelta, camera and world state):
	 * vanilla, optimized, vanilla. If the two vanilla renders differ the frame wasn't deterministic and is skipped.
	 */
	private static boolean pixelCompare(MinecraftClient client, float tickDelta) {
		int[] a = renderAndRead(client, tickDelta, false);
		int[] b = renderAndRead(client, tickDelta, true);
		int[] a2 = renderAndRead(client, tickDelta, false);
		Config.setOptimizations(true);
		if (!Arrays.equals(a, a2)) {
			log("pixel   skipped: vanilla frame not deterministic (" + countDiff(a, a2) + " px changed between two vanilla renders)");
			return false;
		}
		int diff = countDiff(a, b);
		if (diff == 0) {
			pixelPass++;
			log("pixel   identical (" + a.length + " px compared)");
		} else {
			pixelFail++;
			int maxDelta = 0;
			BufferedImage img = new BufferedImage(client.width, client.height, BufferedImage.TYPE_INT_RGB);
			for (int i = 0; i < a.length; i++) {
				int d = channelDelta(a[i], b[i]);
				maxDelta = Math.max(maxDelta, d);
				img.setRGB(i % client.width, i / client.width, d == 0 ? (a[i] >> 2) & 0x3F3F3F : 0xFF0000);
			}
			String name = "autotest-diff-rd" + DISTANCES[distIndex] + "-" + step + ".png";
			try {
				File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "screenshots");
				dir.mkdirs();
				ImageIO.write(img, "png", new File(dir, name));
			} catch (Exception ignored) {
			}
			log(String.format("pixel   DIFFERENT: %d px (%.4f%%), max channel delta %d, see %s", diff, diff * 100.0 / a.length, maxDelta, name));
		}
		return true;
	}

	private static int[] renderAndRead(MinecraftClient client, float tickDelta, boolean optimized) {
		Config.setOptimizations(optimized);
		client.gameRenderer.renderWorld(tickDelta, System.nanoTime());
		BufferedImage img = ScreenshotUtils.method_12153(client.width, client.height, client.getFramebuffer());
		return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
	}

	private static int countDiff(int[] a, int[] b) {
		int n = 0;
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
				n++;
			}
		}
		return n;
	}

	private static int channelDelta(int x, int y) {
		int d = 0;
		for (int sh = 0; sh <= 16; sh += 8) {
			d = Math.max(d, Math.abs(((x >> sh) & 0xFF) - ((y >> sh) & 0xFF)));
		}
		return d;
	}

	private static void dumpProfile(MinecraftClient client, String label) {
		Map<String, Long> end = ((ProfilerAccessor) client.profiler).legacysilicon$getTimes();
		List<String> keys = new ArrayList<String>(end.keySet());
		java.util.Collections.sort(keys);
		StringBuilder sb = new StringBuilder("profile " + label + " (ms per frame, " + profileFrames + " frames):\n");
		for (String k : keys) {
			int depth = k.length() - k.replace(".", "").length();
			if (depth > 5) {
				continue;
			}
			Long before = profileStart.get(k);
			double ms = (end.get(k) - (before == null ? 0L : before)) / 1e6 / Math.max(1, profileFrames);
			if (ms >= 0.05) {
				sb.append(String.format("  %-60s %7.2f%n", k, ms));
			}
		}
		log(sb.toString().trim());
	}

	private static int[] parseDistances(String s) {
		String[] parts = s.split(",");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			out[i] = Integer.parseInt(parts[i].trim());
		}
		return out;
	}

	/** Dev logging config drops INFO lines, so results also go to run/autotest-results.txt. */
	public static void log(String line) {
		LegacySilicon.LOGGER.info("[autotest] " + line);
		report.append(line).append('\n');
		try {
			java.nio.file.Files.write(FabricLoader.getInstance().getGameDir().resolve("autotest-results.txt"),
				report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (java.io.IOException ignored) {
		}
	}
}
