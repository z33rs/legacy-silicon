package dev.legacysilicon;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs before any Minecraft or LWJGL class is loaded. LWJGL 2 reads org.lwjgl.librarypath in Sys's static
 * initializer, so setting it here still decides where the natives come from.
 *
 * Note: the JVM's own architecture is fixed by the launcher. An x86_64 JVM (Rosetta) can never load arm64
 * natives and vice versa, so this only verifies and reports — the real fix is an arm64 Java in Prism.
 */
public class LegacySiliconPreLaunch implements PreLaunchEntrypoint {
	/**
	 * Base library -> file names to look for. Vanilla/Prism ship one universal file per library; Legacy Fabric's
	 * patched LWJGL ships per-architecture files (e.g. libopenal-aarch64.dylib) and picks the right one itself.
	 */
	private static final String[][] NATIVES = {
		{"LWJGL", "liblwjgl-macos-aarch64.dylib", "liblwjgl-macos-amd64.dylib", "liblwjgl.dylib"},
		{"OpenAL (sound)", "libopenal-aarch64.dylib", "libopenal-amd64.dylib", "openal.dylib", "libopenal.dylib"},
		{"jinput (controllers only)", "libjinput-osx.jnilib", "libjinput-osx.dylib"},
	};

	@Override
	public void onPreLaunch() {
		Config.load();

		if (!Config.nativesDir.isEmpty()) {
			File dir = new File(Config.nativesDir);
			if (dir.isDirectory()) {
				System.setProperty("org.lwjgl.librarypath", dir.getAbsolutePath());
				LegacySilicon.LOGGER.info("Forcing LWJGL natives from {}", dir.getAbsolutePath());
			} else {
				LegacySilicon.LOGGER.error("nativesDir '{}' is not a directory; using launcher natives", Config.nativesDir);
			}
		}

		if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
			return;
		}

		String jvmArch = System.getProperty("os.arch");
		boolean jvmArm = "aarch64".equals(jvmArch) || "arm64".equals(jvmArch);
		boolean hostArm = jvmArm || isAppleSiliconHost();
		LegacySilicon.LOGGER.info("JVM: Java {} ({}) arch={}, host={}",
			System.getProperty("java.version"), System.getProperty("java.vendor"), jvmArch, hostArm ? "Apple Silicon" : "Intel");

		if (hostArm && !jvmArm) {
			LegacySilicon.LOGGER.error("==============================================================");
			LegacySilicon.LOGGER.error(" This JVM is x86_64 running under Rosetta 2 on Apple Silicon.");
			LegacySilicon.LOGGER.error(" Point this Prism instance at an arm64 Java 8 (Azul Zulu 8,");
			LegacySilicon.LOGGER.error(" aarch64) in Edit Instance > Settings > Java for native speed.");
			LegacySilicon.LOGGER.error("==============================================================");
		}

		String wanted = jvmArm ? "arm64" : "x86_64";
		String libPath = System.getProperty("org.lwjgl.librarypath");
		if (libPath == null) {
			libPath = System.getProperty("java.library.path", "");
		}
		String[] dirs = libPath.split(File.pathSeparator);
		for (String[] lib : NATIVES) {
			String found = null;
			List<String> foundArches = null;
			boolean ok = false;
			for (int n = 1; n < lib.length && !ok; n++) {
				for (String dirName : dirs) {
					File f = new File(dirName, lib[n]);
					if (!f.isFile()) {
						continue;
					}
					List<String> arches = NativeArch.archesOf(f);
					if (found == null) {
						found = lib[n];
						foundArches = arches;
					}
					if (arches.contains(wanted)) {
						ok = true;
						found = lib[n];
						foundArches = arches;
						break;
					}
				}
			}
			if (found == null) {
				continue;
			}
			if (ok) {
				LegacySilicon.LOGGER.info("Native {}: {} {} OK", lib[0], found, foundArches);
			} else {
				LegacySilicon.LOGGER.warn("Native {}: {} is {} but this JVM needs {}; it will not load.", lib[0], found, foundArches, wanted);
			}
		}
	}

	private static boolean isAppleSiliconHost() {
		try {
			Process p = new ProcessBuilder("sysctl", "-n", "hw.optional.arm64").redirectErrorStream(true).start();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				return "1".equals(r.readLine());
			}
		} catch (Exception e) {
			return false;
		}
	}
}
