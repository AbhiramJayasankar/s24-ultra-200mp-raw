package dev.s24cam;

import dev.s24cam.dng.DngCompactor;
import dev.s24cam.dng.DngCompactorTest;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static org.junit.Assert.*;
import static dev.s24cam.PhotoLibrary.State.*;

public class PhotoLibraryTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test public void modeUsesOnlyInformationPresentInName() {
        assertEquals("200MP",PhotoLibrary.mode("S24HR_200MP_20261027_074342.dng"));
        assertEquals("200MP",PhotoLibrary.mode("S24HR_200MP_20261027_074342_sensor.dng"));
        assertEquals("50MP",PhotoLibrary.mode("S24HR_50MP_20261027_074342.dng"));
        assertEquals("50MP 5x",PhotoLibrary.mode("S24HR_50MP_5x_20261027_074342.dng"));
        assertEquals("50MP 5x",PhotoLibrary.mode("s24hr_50mp_20261027_074342_5x.dng"));
        assertEquals("DNG",PhotoLibrary.mode("other.dng"));assertEquals("DNG",PhotoLibrary.mode(null));
    }
    @Test public void savingAndCompactingTakePrecedenceOverHeaderState() {
        assertEquals(SAVING,PhotoLibrary.state(true,false,true,7,null));
        assertEquals(SAVING,PhotoLibrary.state(false,true,true,7,null));
        assertEquals(COMPRESSING,PhotoLibrary.state(false,false,true,7,null));
        assertEquals(COMPRESSED,PhotoLibrary.state(false,false,false,8,"foreign"));
        assertEquals(WAITING,PhotoLibrary.state(false,false,false,1,null));
        assertEquals(ORIGINAL,PhotoLibrary.state(false,false,false,-1,"unreadable"));
    }
    @Test public void stateFromRealWriterHeaders() throws Exception {
        for(int effort:new int[]{1,6,7,8}) {
            File file=folder.newFile();DngCompactorTest.fixture(file,16,16,effort,true);
            try(FileChannel source=FileChannel.open(file.toPath(),StandardOpenOption.READ)) {
                DngCompactor.Header h=DngCompactor.inspect(source);
                assertEquals(effort,h.effort);
                assertEquals(effort>=7?COMPRESSED:WAITING,PhotoLibrary.state(false,false,false,h.effort,h.skipReason));
            }
        }
        for(boolean foreign:new boolean[]{true,false}) {
            File file=folder.newFile();DngCompactorTest.fixture(file,16,16,1,true);
            byte[] bytes=Files.readAllBytes(file.toPath());ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int root=b.getInt(4),raw=0;
            for(int i=0;i<(b.getShort(root)&65535);i++) {
                int p=root+2+12*i,id=b.getShort(p)&65535;
                if(id==330) raw=b.getInt(p+8);
                if(id==305 && foreign) bytes[b.getInt(p+8)]='X';
            }
            if(!foreign) for(int i=0;i<(b.getShort(raw)&65535);i++) {
                int p=raw+2+12*i;if((b.getShort(p)&65535)==259) b.putShort(p+8,(short)7);
            }
            Files.write(file.toPath(),bytes);
            try(FileChannel source=FileChannel.open(file.toPath(),StandardOpenOption.READ)) {
                DngCompactor.Header h=DngCompactor.inspect(source);assertNotNull(h.skipReason);
                assertEquals(ORIGINAL,PhotoLibrary.state(false,false,false,h.effort,h.skipReason));
            }
        }
    }
    @Test public void everyDeleteGuardBlocksIndependentlyAndAllowsIdleOwnPhoto() {
        assertNull(PhotoLibrary.deleteSkipReason(true,false,false,false,false,false));
        assertTrue(PhotoLibrary.deleteSkipReason(false,false,false,false,false,false).contains("app-owned"));
        assertTrue(PhotoLibrary.deleteSkipReason(true,true,false,false,false,false).contains("saved"));
        assertTrue(PhotoLibrary.deleteSkipReason(true,false,true,false,false,false).contains("saved"));
        assertTrue(PhotoLibrary.deleteSkipReason(true,false,false,true,false,false).contains("compressed"));
        assertTrue(PhotoLibrary.deleteSkipReason(true,false,false,false,true,false).contains("journal"));
        assertTrue(PhotoLibrary.deleteSkipReason(true,false,false,false,false,true).contains("recovery required"));
        // Every combination with any protection is blocked, regardless of other flags.
        for(int bits=1;bits<32;bits++) assertNotNull(PhotoLibrary.deleteSkipReason(true,
                (bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0,(bits&16)!=0));
    }
}
