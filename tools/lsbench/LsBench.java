import java.io.File;
import java.io.FileWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loader-agnostic Minecraft 1.9.4 FPS benchmark agent (dev tool, not part of the mod).
 *
 * Works the same on Forge/OptiFine (SRG names) and Legacy Fabric (intermediary names): it finds the client,
 * waits until the player is in a world, sweeps the camera 360 degrees every 10 seconds (writing the player's yaw),
 * samples the game's own once-per-second FPS counter, writes a result file and asks the game to quit.
 *
 * usage: -javaagent:lsbench.jar  (optional =label=NAME,out=/path/result.txt,warmup=90,measure=40)
 */
public final class LsBench {
	// Names per environment: Forge (SRG/MCP), Legacy Fabric (intermediary), and vanilla/standalone OptiFine
	// (official obfuscated 1.9.4 names, from the Legacy Fabric mappings).
	private static final String[] CLIENT = {"net.minecraft.client.Minecraft", "net.minecraft.class_1600", "bcd"};
	private static final String[] ENTITY = {"net.minecraft.entity.Entity", "net.minecraft.class_864", "rr"};
	private static final String[] INSTANCE = {"field_71432_P", "theMinecraft", "field_3774", "T"};
	private static final String[] PLAYER = {"field_71439_g", "thePlayer", "field_10310", "h"};
	private static final String[] FPS = {"field_71470_ab", "debugFPS", "field_3787", "ar"};
	private static final String[] YAW = {"field_70177_z", "rotationYaw", "field_3258", "v"};
	private static final String[] STOP = {"func_71400_g", "shutdown", "method_2949", "n"};

	public static void premain(final String args, final Instrumentation inst) {
		Thread t = new Thread(() -> {
			try {
				run(args == null ? "" : args, inst);
			} catch (Throwable e) {
				e.printStackTrace();
			}
		}, "LsBench");
		t.setDaemon(true);
		t.start();
	}

	private static void run(String args, Instrumentation inst) throws Exception {
		// Label defaults to the Prism instance folder name (working dir is <instance>/.minecraft), so no agent
		// arguments are needed; Prism's config format would split arguments containing commas.
		File gameDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
		String label = arg(args, "label", gameDir.getParentFile() != null ? gameDir.getParentFile().getName() : "run");
		// Tag the result with the render distance from the game's options.txt (working dir = game dir).
		try {
			for (String line : java.nio.file.Files.readAllLines(new File("options.txt").toPath())) {
				if (line.startsWith("renderDistance:")) {
					label += " rd" + line.substring("renderDistance:".length()).trim();
				}
			}
		} catch (Exception ignored) {
		}
		File out = new File(arg(args, "out", "/tmp/lsbench/results.txt"));
		int warmup = Integer.parseInt(arg(args, "warmup", "90"));
		int measure = Integer.parseInt(arg(args, "measure", "40"));

		Class<?> mc = null;
		while (mc == null) {
			for (Class<?> c : inst.getAllLoadedClasses()) {
				for (String n : CLIENT) {
					if (c.getName().equals(n)) {
						mc = c;
					}
				}
			}
			Thread.sleep(500);
		}
		Field instF = field(mc, INSTANCE);
		Field playerF = field(mc, PLAYER);
		Field fpsF = field(mc, FPS);
		Method stop = method(mc, STOP);
		log("client " + mc.getName() + " fields: instance=" + name(instF) + " player=" + name(playerF) + " fps=" + name(fpsF) + " stop=" + (stop == null ? "-" : stop.getName()));

		Object client = null;
		Object player = null;
		while (player == null) {
			Thread.sleep(250);
			client = instF.get(null);
			player = client == null ? null : playerF.get(client);
		}
		Field yawF = entityField(player.getClass(), YAW);
		log("in world; yaw field " + name(yawF) + "; warmup " + warmup + "s, measure " + measure + "s");

		float yaw0 = yawF.getFloat(player);
		long start = System.nanoTime();
		long nextSample = start + (warmup + 1) * 1_000_000_000L;
		long end = start + (long) (warmup + measure + 1) * 1_000_000_000L;
		List<Integer> samples = new ArrayList<Integer>();
		while (true) {
			long now = System.nanoTime();
			Object p = playerF.get(client);
			if (p != null) {
				// 360 degrees every 10 seconds, continuous.
				yawF.setFloat(p, yaw0 + (float) ((now - start) / 1e9 * 36.0));
			}
			if (now >= nextSample) {
				samples.add(fpsF.getInt(null));
				nextSample += 1_000_000_000L;
			}
			if (now >= end) {
				break;
			}
			Thread.sleep(5);
		}

		List<Integer> sorted = new ArrayList<Integer>(samples);
		Collections.sort(sorted);
		double avg = 0;
		for (int s : samples) {
			avg += s;
		}
		avg /= samples.size();
		String result = String.format("%s avg %.1f FPS, median %d, min %d, max %d over %d s; samples %s%n", label, avg,
			sorted.get(sorted.size() / 2), sorted.get(0), sorted.get(sorted.size() - 1), samples.size(), samples);
		try (FileWriter w = new FileWriter(out, true)) {
			w.write(result);
		}
		log(result.trim());
		if (stop != null) {
			stop.invoke(client);
		}
		Thread.sleep(15000);
		System.exit(0);
	}

	private static Field field(Class<?> c, String[] names) throws NoSuchFieldException {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			for (String n : names) {
				try {
					Field f = k.getDeclaredField(n);
					f.setAccessible(true);
					return f;
				} catch (NoSuchFieldException ignored) {
				}
			}
		}
		throw new NoSuchFieldException(String.join("/", names) + " in " + c.getName());
	}

	/** Like field(), but only accepts a float field declared on the Entity class (short obfuscated names repeat). */
	private static Field entityField(Class<?> c, String[] names) throws NoSuchFieldException {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			if (!java.util.Arrays.asList(ENTITY).contains(k.getName())) {
				continue;
			}
			for (String n : names) {
				try {
					Field f = k.getDeclaredField(n);
					if (f.getType() == float.class) {
						f.setAccessible(true);
						return f;
					}
				} catch (NoSuchFieldException ignored) {
				}
			}
		}
		throw new NoSuchFieldException("Entity yaw in hierarchy of " + c.getName());
	}

	private static Method method(Class<?> c, String[] names) {
		for (String n : names) {
			try {
				Method m = c.getDeclaredMethod(n);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	private static String name(Field f) {
		return f.getDeclaringClass().getSimpleName() + "." + f.getName();
	}

	private static String arg(String args, String key, String def) {
		for (String kv : args.split(",")) {
			if (kv.startsWith(key + "=")) {
				return kv.substring(key.length() + 1);
			}
		}
		return def;
	}

	private static void log(String s) {
		System.out.println("[LsBench] " + s);
	}
}
