package dev.legacysilicon;

import java.nio.FloatBuffer;
import java.util.Random;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Vanilla positions each chunk with glTranslatef + glMultMatrixf, letting the driver do the math.
 * To replace those calls with one glLoadMatrixf without changing a single pixel, the CPU must produce
 * the exact same float bits the driver would. Rounding depends on operation order and on whether the
 * driver uses fused multiply-add, so at startup we try several emulations against the real driver and
 * only use one that matches bit-for-bit on every test case. If none match, callers keep the driver
 * calls (still exact, just one call cheaper than vanilla).
 */
public final class ChunkMatrix {
	private static final String[] NAMES = {"float sequential", "double per element", "fma from translation column",
		"fma vector (simd_mul style)", "float translation-first", "double whole chain"};
	/** Index of the bit-exact emulation, or -1 if none matched this driver. */
	public static int variant = -1;

	private ChunkMatrix() {
	}

	/** Must run on the render thread with a current GL context. */
	public static void verify() {
		FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		float[] c = new float[16];
		float[] base = new float[16];
		float[] driver = new float[16];
		float[] cpu = new float[16];
		float[] tmp = new float[16];
		int[] matches = new int[NAMES.length];
		int cases = 4000;
		Random r = new Random(1234);

		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();
		// The constant chunk matrix, built exactly the way BuiltChunk builds it.
		GL11.glLoadIdentity();
		GL11.glTranslatef(-8.0F, -8.0F, -8.0F);
		GL11.glScalef(1.000001F, 1.000001F, 1.000001F);
		GL11.glTranslatef(8.0F, 8.0F, 8.0F);
		read(fb, c);

		for (int i = 0; i < cases; i++) {
			// Camera-like base: rotations and a small translation, sometimes a fully random matrix.
			GL11.glLoadIdentity();
			if (i % 4 == 3) {
				for (int k = 0; k < 16; k++) {
					base[k] = (r.nextFloat() - 0.5F) * 4.0F;
				}
				load(fb, base);
			} else {
				GL11.glTranslatef(0.0F, 0.0F, -0.05F - r.nextFloat() * 0.1F);
				GL11.glRotatef(r.nextFloat() * 180.0F - 90.0F, 1.0F, 0.0F, 0.0F);
				GL11.glRotatef(r.nextFloat() * 360.0F + 180.0F, 0.0F, 1.0F, 0.0F);
				GL11.glTranslatef(0.0F, -r.nextFloat() * 1.8F, 0.0F);
			}
			read(fb, base);
			double view = (r.nextDouble() - 0.5) * 60000.0;
			float tx = (float) ((r.nextInt(64) - 32) * 16 + Math.floor(view / 16) * 16 - view);
			float ty = (float) (r.nextInt(16) * 16 - r.nextDouble() * 256.0);
			float tz = (float) ((r.nextInt(64) - 32) * 16 - r.nextDouble() * 16.0);

			load(fb, base);
			GL11.glTranslatef(tx, ty, tz);
			fb.clear();
			fb.put(c).flip();
			GL11.glMultMatrix(fb);
			read(fb, driver);

			for (int v = 0; v < NAMES.length; v++) {
				compute(v, base, tx, ty, tz, c, cpu, tmp);
				if (bitsEqual(cpu, driver)) {
					matches[v]++;
				}
			}
		}
		GL11.glPopMatrix();

		StringBuilder sb = new StringBuilder("Chunk matrix emulation vs driver (" + cases + " cases):");
		for (int v = 0; v < NAMES.length; v++) {
			sb.append(String.format(" [%s: %d]", NAMES[v], matches[v]));
			if (variant < 0 && matches[v] == cases) {
				variant = v;
			}
		}
		LegacySilicon.LOGGER.info(sb.toString());
		LegacySilicon.LOGGER.info(variant >= 0 ? "Using bit-exact CPU chunk matrices (" + NAMES[variant] + ")"
			: "No bit-exact emulation for this driver; keeping driver matrix math");
		if (Autotest.ENABLED) {
			Autotest.log(sb.toString());
			Autotest.log(variant >= 0 ? "matrix  using " + NAMES[variant] : "matrix  no exact emulation, driver fallback");
		}
	}

	/** out = base * translate(tx,ty,tz) * c, rounded the way the selected driver emulation rounds. */
	public static void compute(int v, float[] b, float tx, float ty, float tz, float[] c, float[] out, float[] m1) {
		if (v == 5) {
			double[] d = new double[16];
			for (int k = 0; k < 12; k++) {
				d[k] = b[k];
			}
			for (int row = 0; row < 4; row++) {
				d[12 + row] = (double) b[row] * tx + (double) b[4 + row] * ty + (double) b[8 + row] * tz + b[12 + row];
			}
			for (int col = 0; col < 4; col++) {
				for (int row = 0; row < 4; row++) {
					out[col * 4 + row] = (float) (d[row] * c[col * 4] + d[4 + row] * c[col * 4 + 1] + d[8 + row] * c[col * 4 + 2] + d[12 + row] * c[col * 4 + 3]);
				}
			}
			return;
		}
		System.arraycopy(b, 0, m1, 0, 12);
		for (int row = 0; row < 4; row++) {
			m1[12 + row] = dot(v, b[row], b[4 + row], b[8 + row], b[12 + row], tx, ty, tz, 1.0F);
		}
		for (int col = 0; col < 4; col++) {
			int o = col * 4;
			for (int row = 0; row < 4; row++) {
				out[o + row] = dot(v, m1[row], m1[4 + row], m1[8 + row], m1[12 + row], c[o], c[o + 1], c[o + 2], c[o + 3]);
			}
		}
	}

	/** a0*x + a1*y + a2*z + a3*w with the rounding behaviour of variant v. */
	private static float dot(int v, float a0, float a1, float a2, float a3, float x, float y, float z, float w) {
		switch (v) {
			case 0:
				return a0 * x + a1 * y + a2 * z + a3 * w;
			case 1:
				return (float) ((double) a0 * x + (double) a1 * y + (double) a2 * z + (double) a3 * w);
			case 2:
				return fma(a0, x, fma(a1, y, fma(a2, z, a3 * w)));
			case 3:
				return fma(a3, w, fma(a2, z, fma(a1, y, a0 * x)));
			case 4:
				return a3 * w + a0 * x + a1 * y + a2 * z;
			default:
				throw new IllegalArgumentException();
		}
	}

	/** Fused multiply-add for Java 8: the float product is exact in double. */
	private static float fma(float a, float b, float c) {
		return (float) ((double) a * (double) b + (double) c);
	}

	private static boolean bitsEqual(float[] a, float[] b) {
		for (int i = 0; i < 16; i++) {
			if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i]) && !(a[i] == 0.0F && b[i] == 0.0F)) {
				return false;
			}
		}
		return true;
	}

	private static void read(FloatBuffer fb, float[] dst) {
		fb.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, fb);
		fb.get(dst);
	}

	private static void load(FloatBuffer fb, float[] src) {
		fb.clear();
		fb.put(src).flip();
		GL11.glLoadMatrix(fb);
	}
}
