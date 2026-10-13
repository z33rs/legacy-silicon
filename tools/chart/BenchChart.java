import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Renders docs/images/benchmark.png from BENCHMARKS.md numbers (emphasis bar chart, light surface, 2x). */
public class BenchChart {
	static final Color SURFACE = new Color(0xfcfcfb), INK = new Color(0x0b0b0b), INK2 = new Color(0x52514e),
		MUTED = new Color(0x898781), GRID = new Color(0xe1e0d9), BASE = new Color(0xc3c2b7), ACCENT = new Color(0x2a78d6);
	static final String[] NAMES = {"Legacy Silicon", "Vanilla 1.9.4", "OptiFine (standalone)", "OptiFine + Forge"};
	static final int[][] FPS = {{248, 129, 91, 88}, {136, 74, 44, 44}};
	static final String[] PANELS = {"Render distance 16", "Render distance 24"};
	static final int S = 2, W = 800, H = 352, MAX = 260;

	public static void main(String[] a) throws Exception {
		BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.scale(S, S);
		g.setColor(SURFACE);
		g.fillRect(0, 0, W, H);
		String face = "Helvetica Neue";

		text(g, "Minecraft 1.9.4 on an M1 Max: average FPS", 32, 44, new Font(face, Font.BOLD, 22), INK);
		text(g, "Same world, spot and 360° camera sweep · 1728×1080 · Fancy graphics · higher is better", 32, 70,
			new Font(face, Font.PLAIN, 14), INK2);

		int labelW = 168, panelW = (W - 64 - labelW) / 2, top = 112, barH = 22, gap = 18;
		for (int p = 0; p < 2; p++) {
			int x0 = 32 + labelW + p * panelW + (p == 1 ? 16 : 0);
			int plotW = panelW - 48;
			text(g, PANELS[p], x0, top - 14, new Font(face, Font.BOLD, 14), INK);
			int plotH = NAMES.length * (barH + gap) - gap;
			// recessive hairline grid + tick labels
			for (int t = 0; t <= 250; t += 50) {
				float gx = x0 + (float) t / MAX * plotW;
				g.setColor(t == 0 ? BASE : GRID);
				g.setStroke(new BasicStroke(1f));
				g.draw(new Line2D.Float(gx, top - 4, gx, top + plotH + 4));
				Font tf = new Font(face, Font.PLAIN, 12);
				float tw = g.getFontMetrics(tf).stringWidth(String.valueOf(t));
				text(g, String.valueOf(t), gx - tw / 2, top + plotH + 22, tf, MUTED);
			}
			for (int i = 0; i < NAMES.length; i++) {
				int y = top + i * (barH + gap);
				if (p == 0) {
					text(g, NAMES[i], 32, y + 16, new Font(face, i == 0 ? Font.BOLD : Font.PLAIN, 14), i == 0 ? INK : INK2);
				}
				float w = (float) FPS[p][i] / MAX * plotW;
				g.setColor(i == 0 ? ACCENT : BASE);
				g.fill(roundedEnd(x0, y, w, barH, 4));
				text(g, String.valueOf(FPS[p][i]), x0 + w + 6, y + 16, new Font(face, i == 0 ? Font.BOLD : Font.PLAIN, 14), i == 0 ? INK : INK2);
			}
		}
		text(g, "Vanilla RD 16 and Legacy Silicon RD 16 are averages of two runs. OptiFine's Render Regions are unavailable on Apple's OpenGL driver.",
			32, H - 46, new Font(face, Font.PLAIN, 12), MUTED);
		text(g, "Method and raw data: BENCHMARKS.md in the Legacy Silicon repository.", 32, H - 26, new Font(face, Font.PLAIN, 12), MUTED);
		g.dispose();
		ImageIO.write(img, "png", new File(a[0]));
	}

	/** Bar anchored square at the baseline, 4px rounded data-end. */
	static Shape roundedEnd(float x, float y, float w, float h, float r) {
		Path2D.Float s = new Path2D.Float();
		s.moveTo(x, y);
		s.lineTo(x + w - r, y);
		s.quadTo(x + w, y, x + w, y + r);
		s.lineTo(x + w, y + h - r);
		s.quadTo(x + w, y + h, x + w - r, y + h);
		s.lineTo(x, y + h);
		s.closePath();
		return s;
	}

	static void text(Graphics2D g, String s, float x, float y, Font f, Color c) {
		g.setFont(f);
		g.setColor(c);
		g.drawString(s, x, y);
	}
}
