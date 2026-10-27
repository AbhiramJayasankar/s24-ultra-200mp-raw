package dev.s24cam.dng;

import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public class Remosaic16Test {
    private static final int BLACK = 64, WHITE = 1023;

    /** Smooth colour field (no sharp colour edges), so the remosaic should be near exact. */
    private static int truth(int colour, int x, int y) {
        double base = 200 + 150 * Math.sin(x / 37.0) * Math.cos(y / 29.0);
        double tint = colour == 0 ? 0.55 : colour == 2 ? 0.7 : 1.0;
        return (int) Math.round(BLACK + base * tint * (1 + 0.2 * Math.sin((x + y) / 90.0)));
    }

    @Test
    public void smoothSceneRemosaicsToBayerWithinOnePercent() {
        int w = 256, h = 192, stride = w * 5 / 4 + 16;
        int[] pattern = {1, 2, 0, 1}; // G B / R G
        ByteBuffer buf = ByteBuffer.allocate(stride * h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x += 4) {
                int[] v = new int[4];
                for (int i = 0; i < 4; i++) {
                    int xx = x + i;
                    v[i] = truth(pattern[((y >> 2) & 1) * 2 + ((xx >> 2) & 1)], xx, y);
                }
                int at = y * stride + x / 4 * 5;
                int low = 0;
                for (int i = 0; i < 4; i++) {
                    buf.put(at + i, (byte) (v[i] >> 2));
                    low |= (v[i] & 3) << (2 * i);
                }
                buf.put(at + 4, (byte) low);
            }
        }
        Remosaic16 r = new Remosaic16(buf, stride, w, h, BLACK, WHITE);
        int[] row = new int[w];
        double err = 0, sum = 0;
        for (int y = 8; y < h - 8; y++) {
            r.row(y, row);
            for (int x = 8; x < w - 8; x++) {
                int expected = truth(pattern[(y & 1) * 2 + (x & 1)], x, y);
                err += Math.abs(row[x] - expected);
                sum += expected - BLACK;
            }
        }
        assertTrue("relative error " + err / sum, err / sum < 0.01);
    }
}
