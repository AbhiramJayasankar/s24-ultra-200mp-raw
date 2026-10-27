package dev.s24cam.dng;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class DngSampleTest {
    @Test public void convertRealSampleWhenAvailable() throws Exception {
        assumeTrue("Optional phone capture is absent", Files.isRegularFile(DngSampleMain.SAMPLE)
                && Files.isRegularFile(DngSampleMain.META));
        DngSampleMain.main(new String[0]);
        Path build = DngSampleMain.projectRoot().resolve("build");
        assertTrue(Files.size(build.resolve("sample50mp.dng")) > 8160L * 6120 * 2);
        assertTrue(Files.size(build.resolve("sample50mp_ljpeg.dng")) > 0);
    }

    @Test public void parsePhoneMetadataWithFractionsAndDynamicLevels() {
        Properties v = new Properties();
        v.setProperty("cfa", "2"); v.setProperty("sensorOrientation", "90");
        v.setProperty("whiteLevel", "1023"); v.setProperty("dynamicWhiteLevel", "1000");
        v.setProperty("blackLevelPattern", "[1, 2, 3, 4]");
        v.setProperty("dynamicBlackLevel", "[60.1, 61.7, 62.2, 63.9]");
        v.setProperty("colorTransform1", "[1/2, -1/4, 0/1, 0/1, 1/1, 0/1, 0/1, 0/1, 1/1]");
        v.setProperty("asShotNeutral", "[495/1024, 1/1, 285/512]");
        v.setProperty("iso", "1600"); v.setProperty("postRawBoost", "150");
        DngParams p = DngSampleMain.fromMetadata(v, 8160, 6120);
        assertEquals(2, p.cfaPattern); assertEquals(6, p.orientation); assertEquals(1000, p.whiteLevel);
        assertArrayEquals(new int[]{60, 62, 62, 64}, p.blackLevel);
        assertEquals(.5, p.colorMatrix1[0], 0); assertEquals(-.25, p.colorMatrix1[1], 0);
        assertArrayEquals(new double[]{495.0 / 1024, 1, 285.0 / 512}, p.asShotNeutral, 0);
        assertEquals(2400, p.iso);
    }
}
