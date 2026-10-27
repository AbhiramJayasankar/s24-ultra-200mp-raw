package dev.s24cam.dng;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import org.junit.Test;

/** The native 16-cell frame is stored with an 8x8 CFA repeat and must survive unchanged. */
public class NativeCfaTest {
    static int[] sixteenCellPattern() {
        int[] p = new int[64];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int by = y >> 2, bx = x >> 2;
                p[y * 8 + x] = by == bx ? 1 : by == 0 ? 2 : 0; // G B / R G in 4x4 blocks
            }
        return p;
    }

    @Test
    public void eightByEightPatternRoundTrips() throws Exception {
        for (boolean ljpeg : new boolean[]{false, true}) {
            int w = 64, h = 48;
            ByteBuffer raw16 = ByteBuffer.allocate(w * h * 2);
            int[] expected = new int[w * h];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = 64 + (i * 37) % 900;
                raw16.put(i * 2, (byte) expected[i]);
                raw16.put(i * 2 + 1, (byte) (expected[i] >> 8));
            }
            DngParams p = new DngParams();
            p.width = w;
            p.height = h;
            p.blackLevel = new int[]{64, 64, 64, 64};
            p.whiteLevel = 1023;
            p.cfaCustom = sixteenCellPattern();
            p.cfaRows = 8;
            p.cfaCols = 8;
            p.losslessJpeg = ljpeg;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DngWriter.writeRaw16(out, raw16, w * 2, p);
            TiffTestReader tiff = new TiffTestReader(out.toByteArray());
            Map<Integer, TiffTestReader.Tag> rawIfd = tiff.ifd(tiff.root().get(330).first());
            assertArrayEquals(new long[]{8, 8}, rawIfd.get(33421).integers());
            long[] cfa = rawIfd.get(33422).integers();
            assertEquals(64, cfa.length);
            for (int i = 0; i < 64; i++) assertEquals(p.cfaCustom[i], cfa[i]);
            assertArrayEquals(expected, tiff.raw());
        }
    }
}
