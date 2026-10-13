import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Renders docs/images/icon.png: a chip (Apple Silicon) with a grass block die. 512x512. */
public class Icon {
	public static void main(String[] a) throws Exception {
		int S = 512;
		BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x1a1a19));
		g.fill(new RoundRectangle2D.Float(0, 0, S, S, 112, 112));
		// pins
		g.setColor(new Color(0x9a9893));
		for (int i = 0; i < 5; i++) {
			int p = 146 + i * 50;
			g.fill(new RoundRectangle2D.Float(p - 9, 56, 18, 60, 8, 8));
			g.fill(new RoundRectangle2D.Float(p - 9, S - 116, 18, 60, 8, 8));
			g.fill(new RoundRectangle2D.Float(56, p - 9, 60, 18, 8, 8));
			g.fill(new RoundRectangle2D.Float(S - 116, p - 9, 60, 18, 8, 8));
		}
		// chip body
		g.setColor(new Color(0x2c2c2a));
		g.fill(new RoundRectangle2D.Float(104, 104, 304, 304, 40, 40));
		g.setColor(new Color(0x2a78d6));
		g.setStroke(new BasicStroke(10f));
		g.draw(new RoundRectangle2D.Float(109, 109, 294, 294, 36, 36));
		// grass block die: dirt with a grass top band, pixel-style
		int x0 = 166, y0 = 166, d = 180, px = d / 10;
		Color[] dirt = {new Color(0x866043), new Color(0x79553a), new Color(0x96704e)};
		Color[] grass = {new Color(0x5fa33a), new Color(0x4f8f2f), new Color(0x6db544)};
		java.util.Random r = new java.util.Random(7);
		for (int yy = 0; yy < 10; yy++) {
			for (int xx = 0; xx < 10; xx++) {
				boolean top = yy < 3 || (yy == 3 && r.nextInt(3) == 0);
				Color[] pal = top ? grass : dirt;
				g.setColor(pal[r.nextInt(pal.length)]);
				g.fillRect(x0 + xx * px, y0 + yy * px, px, px);
			}
		}
		g.dispose();
		ImageIO.write(img, "png", new File(a[0]));
	}
}
