package dev.legacysilicon;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Plain static fields so the hot paths read a single field instead of going through getters or maps.
 * Loaded once in preLaunch from config/legacysilicon.properties.
 */
public final class Config {
	/** Replace vanilla's per-chunk draw loop with a pixel-identical one that makes ~3 driver calls instead of ~12. */
	public static boolean fastChunkDraw = true;
	/** Sodium-style batching: per-region shared chunk buffers drawn with glMultiDrawArrays. Read once at startup. */
	public static boolean regionBatching = true;
	/** Autotest override: 0 = per-chunk path even when regions are active. -1 = automatic. */
	public static int debugRegions = -1;
	/** Use one vertex array object per chunk buffer. Off: measured slower on Apple's driver (cheaper bind, costlier draw). */
	public static boolean chunkVao = false;
	/** Autotest overrides: matrix 0=vanilla calls 1=CPU exact 2=driver fallback; bind 0=pointers 1=VAO. -1 = automatic. */
	public static int debugMatrixMode = -1;
	public static int debugBindMode = -1;
	/** Faster visible-chunk search, camera-chunk scan cache and entity-pass chunk cache. Output identical to vanilla. */
	public static boolean fastTerrainSetup = true;
	/** Geometric (doubling) growth for BufferBuilder instead of linear 2 MB steps. */
	public static boolean fastBufferGrowth = true;
	/** Primitive-array translucent quad sort instead of boxed Integer[] + per-quad slice() allocations. */
	public static boolean fastTranslucentSort = true;

	/** Log average FPS and 1% low frame time every 5 seconds while in a world. */
	public static boolean benchmarkLog = false;
	/** Optional directory of native libraries to force LWJGL to load from (org.lwjgl.librarypath). Empty = launcher default. */
	public static String nativesDir = "";

	private Config() {
	}

	/** Switches every rendering optimization at once (used by the A/B benchmark and the pixel test). */
	public static void setOptimizations(boolean on) {
		fastChunkDraw = on;
		fastTerrainSetup = on;
		debugMatrixMode = -1;
		debugBindMode = -1;
		debugRegions = -1;
		fastBufferGrowth = on;
		fastTranslucentSort = on;
	}

	static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("legacysilicon.properties");
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException e) {
				LegacySilicon.LOGGER.warn("Could not read {}, using defaults", file, e);
			}
		}

		fastChunkDraw = parseBool(p, "fastChunkDraw", fastChunkDraw);
		chunkVao = parseBool(p, "chunkVao", chunkVao);
		regionBatching = parseBool(p, "regionBatching", regionBatching);
		fastTerrainSetup = parseBool(p, "fastTerrainSetup", fastTerrainSetup);
		fastBufferGrowth = parseBool(p, "fastBufferGrowth", fastBufferGrowth);
		fastTranslucentSort = parseBool(p, "fastTranslucentSort", fastTranslucentSort);
		benchmarkLog = parseBool(p, "benchmarkLog", benchmarkLog);
		nativesDir = p.getProperty("nativesDir", nativesDir).trim();

		// Rewrite so new keys show up with their defaults after an update.
		save(file);
	}

	private static void save(Path file) {
		Properties p = new Properties();
		p.setProperty("fastChunkDraw", Boolean.toString(fastChunkDraw));
		p.setProperty("chunkVao", Boolean.toString(chunkVao));
		p.setProperty("regionBatching", Boolean.toString(regionBatching));
		p.setProperty("fastTerrainSetup", Boolean.toString(fastTerrainSetup));
		p.setProperty("fastBufferGrowth", Boolean.toString(fastBufferGrowth));
		p.setProperty("fastTranslucentSort", Boolean.toString(fastTranslucentSort));
		p.setProperty("benchmarkLog", Boolean.toString(benchmarkLog));
		p.setProperty("nativesDir", nativesDir);
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				p.store(w, "Legacy Silicon. All optimizations are visually identical to vanilla. nativesDir: leave empty unless overriding launcher natives.");
			}
		} catch (IOException e) {
			LegacySilicon.LOGGER.warn("Could not write {}", file, e);
		}
	}

	private static float parseFloat(Properties p, String key, float def) {
		try {
			return Float.parseFloat(p.getProperty(key, Float.toString(def)).trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static boolean parseBool(Properties p, String key, boolean def) {
		return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(def)).trim());
	}
}
