import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/** Groups the red pixels of an autotest diff image into 8-connected clusters and prints their size histogram. */
public class DiffClusters {
	public static void main(String[] args) throws Exception {
		for (String path : args) {
			BufferedImage img = ImageIO.read(new File(path));
			int w = img.getWidth(), h = img.getHeight();
			boolean[] red = new boolean[w * h];
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++)
					red[y * w + x] = (img.getRGB(x, y) & 0xFFFFFF) == 0xFF0000;
			boolean[] seen = new boolean[w * h];
			TreeMap<Integer, Integer> hist = new TreeMap<>();
			int largest = 0;
			for (int i = 0; i < w * h; i++) {
				if (!red[i] || seen[i]) continue;
				int size = 0;
				ArrayDeque<Integer> q = new ArrayDeque<>();
				q.add(i);
				seen[i] = true;
				while (!q.isEmpty()) {
					int p = q.poll();
					size++;
					int px = p % w, py = p / w;
					for (int dy = -1; dy <= 1; dy++)
						for (int dx = -1; dx <= 1; dx++) {
							int nx = px + dx, ny = py + dy;
							if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
							int n = ny * w + nx;
							if (red[n] && !seen[n]) { seen[n] = true; q.add(n); }
						}
				}
				hist.merge(size, 1, Integer::sum);
				largest = Math.max(largest, size);
			}
			System.out.println(new File(path).getName() + ": cluster sizes " + hist + ", largest " + largest + " px");
		}
	}
}
