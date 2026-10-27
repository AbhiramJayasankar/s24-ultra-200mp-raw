package dev.s24cam;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.s24cam.CompactStatus.*;

public class CompactStatusTest {
    static final class Memory implements Persistence {
        Saved saved=new Saved(Phase.IDLE,0,0,null);int writes;
        public Saved load() { return saved; }
        public void save(Saved s) { saved=s;writes++; }
    }
    @Test public void schedulingRunProgressResultsAndRestart() {
        Memory p=new Memory();Model m=new Model(p,false,1000);
        assertEquals(Phase.IDLE,m.value.phase);
        assertTrue(m.waiting(1100));assertEquals(Phase.WAITING_FOR_CHARGER,m.value.phase);
        assertTrue(m.start(1200));assertTrue(m.active);assertFalse(m.waiting(1300));
        m.left(2,1400);m.file("a.dng",null,1500);
        assertEquals("a.dng",m.value.currentName);assertEquals(2,m.value.filesLeft);
        assertTrue(m.progress(0,100,1600));int writes=p.writes;
        assertFalse(m.progress(1,100,1700));assertEquals(0,m.value.tilesDone);
        assertTrue(m.progress(2,100,1850));assertEquals(writes,p.writes);
        m.result(1234,"a.dng 231 MB -> 152 MB",2000);
        assertEquals(100,m.value.tilesDone);
        assertEquals(1234,m.value.savedBytesTotal);assertEquals(1,m.value.compressedTotal);assertEquals(1,m.value.filesLeft);
        m.file("b.dng",null,2100);assertEquals(0,m.value.tilesDone);assertEquals(0,m.value.tilesTotal);
        m.result(4321,"b.dng compressed",2200);m.finish(false,2300);
        assertFalse(m.active);assertEquals(Phase.DONE,m.value.phase);assertNull(m.value.currentName);assertNull(m.value.currentUri);
        assertEquals(0,m.value.filesLeft);assertEquals(2300,m.value.lastChangeMs);
        Model restarted=new Model(p,false,3000);
        assertEquals(5555,restarted.value.savedBytesTotal);assertEquals(2,restarted.value.compressedTotal);
        assertEquals("b.dng compressed",restarted.value.lastMessage);assertEquals(Phase.DONE,restarted.value.phase);
    }
    @Test public void pausesAndErrorsSurviveFinishAndPendingRestartsShowWaiting() {
        for(Phase phase:new Phase[]{Phase.PAUSED_WARM,Phase.PAUSED_STOPPED,Phase.ERROR}) {
            Memory p=new Memory();Model m=new Model(p,false,1000);m.start(1100);m.file("a.dng",null,1200);
            m.phase(phase,"paused or failed",1300);
            // A stop cannot release the active-file guard before the worker/transaction exits.
            assertEquals("a.dng",m.value.currentName);assertTrue(m.active);
            m.finish(true,1400);assertEquals(phase,m.value.phase);assertNull(m.value.currentName);
            assertEquals(phase,new Model(p,false,1600).value.phase);
            assertEquals(Phase.WAITING_FOR_CHARGER,new Model(p,true,1700).value.phase);
            assertTrue(m.waiting(1750));assertEquals("paused or failed",m.value.lastMessage);
            m.start(1800);assertEquals(Phase.RUNNING,m.value.phase);
        }
    }
    @Test public void interruptedRunsAreNeverResurrectedAsRunningAndSkipsDoNotIncreaseTotals() {
        Memory p=new Memory();Model m=new Model(p,false,1000);m.start(1100);m.left(3,1200);
        m.result(0,"skip: not enough space",1300);assertEquals(0,m.value.compressedTotal);assertEquals(3,m.value.filesLeft);
        assertEquals(Phase.IDLE,new Model(p,false,1400).value.phase);
        assertEquals(Phase.WAITING_FOR_CHARGER,new Model(p,true,1500).value.phase);
        m.finish(true,1600);assertEquals(Phase.WAITING_FOR_CHARGER,m.value.phase);assertEquals(3,m.value.filesLeft);
        assertFalse(m.finish(true,1700));assertEquals(1600,m.value.lastChangeMs);
    }
    @Test public void retainedOriginalsRemainInBestKnownCountAfterNoSmallerReplacement() {
        Memory p=new Memory();Model m=new Model(p,false,1000);m.start(1100);m.left(1,1200);
        m.result(0,"a.dng: already at smallest size",1300);m.finish(false,1400);
        assertEquals(1,m.value.filesLeft);assertEquals(0,m.value.compressedTotal);assertEquals(0,m.value.savedBytesTotal);
        assertEquals(Phase.IDLE,m.value.phase);
    }
}
