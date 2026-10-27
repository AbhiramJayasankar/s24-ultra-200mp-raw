package dev.s24cam.dng;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class SafeDngReplaceTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    File source,temp,dir;String oldHash,newHash;Store store;SafeDngReplace tx;
    @Before public void setup() throws Exception {
        source=folder.newFile("original.dng");dir=folder.newFolder("compact");temp=new File(dir,"42.tmp");
        DngCompactorTest.fixture(source,16,16,1,true);oldHash=SafeDngReplace.hash(source);
        try(FileChannel in=DngCompactorTest.read(source)){DngCompactor.compact(in,temp,new DngCompactorTest.Codec(),DngCompactorTest.GO,1);}
        newHash=SafeDngReplace.hash(temp);store=new Store();tx=new SafeDngReplace(dir,store);
    }
    final class Store implements SafeDngReplace.Store {
        int crash=-1,opens,refreshes,syncs;final List<String> logs=new ArrayList<>();
        public FileChannel read(String uri)throws IOException {assertEquals("content://media/external/images/media/42",uri);return DngCompactorTest.read(source);}
        public void overwrite(String uri,File verified)throws IOException {
            opens++;
            if(crash==0)throw new IOException("crash before overwrite");
            try(FileOutputStream out=new FileOutputStream(source)) {
                if(crash==1){out.getFD().sync();throw new IOException("crash after truncate");}
                byte[] bytes=Files.readAllBytes(verified.toPath());
                out.write(bytes,0,crash==2?bytes.length/2:bytes.length);out.getFD().sync();
                if(crash==2)throw new IOException("crash during copy");
            }
            if(crash==3)throw new IOException("crash after fsync/close");
        }
        public void refresh(String uri,long size)throws IOException {refreshes++;assertEquals(size,source.length());if(crash==4)throw new IOException("crash before cleanup");}
        public void syncDirectory(File dir){syncs++;}
        public void log(String reason){logs.add(reason);}
    }
    void replace()throws Exception {tx.replace("content://media/external/images/media/42",temp,source.length(),oldHash,new DngCompactorTest.Codec(),DngCompactorTest.GO);}
    @Test public void normalTransactionVerifiesThenCleansUp()throws Exception {
        replace();assertEquals(newHash,SafeDngReplace.hash(source));assertFalse(temp.exists());assertFalse(tx.hasJournal());assertEquals(1,store.opens);assertTrue(store.syncs>=4);
    }
    @Test public void beforeOverwriteOriginalRemainsAndRecoveryDiscardsTemp()throws Exception {crashAndRecover(0,false);}
    @Test public void afterTruncateRecoversFromVerifiedTemp()throws Exception {crashAndRecover(1,true);}
    @Test public void partialCopyRecoversFromVerifiedTemp()throws Exception {crashAndRecover(2,true);}
    @Test public void afterOverwriteBeforeHashRecoversWithoutRewrite()throws Exception {crashAndRecover(3,true);assertEquals(1,store.opens);}
    @Test public void afterHashBeforeCleanupRecoversWithoutRewrite()throws Exception {crashAndRecover(4,true);assertEquals(1,store.opens);}
    void crashAndRecover(int point,boolean expectNew)throws Exception {
        store.crash=point;assertThrows(IOException.class,this::replace);assertTrue(tx.hasJournal());assertTrue(temp.exists());
        String json=new String(Files.readAllBytes(new File(dir,"journal.json").toPath()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains(oldHash));assertTrue(json.contains(newHash));assertTrue(json.contains("originalSize"));
        store.crash=-1;new SafeDngReplace(dir,store).recover();
        assertEquals(expectNew?newHash:oldHash,SafeDngReplace.hash(source));assertFalse(temp.exists());assertFalse(tx.hasJournal());
    }
    @Test public void corruptOrMissingRecoveryTempNeverOverwritesPartialOriginal()throws Exception {
        store.crash=2;assertThrows(IOException.class,this::replace);String partial=SafeDngReplace.hash(source);
        Files.write(temp.toPath(),new byte[]{1,2,3});store.crash=-1;
        assertThrows(IOException.class,tx::recover);assertEquals(1,store.opens);assertTrue(tx.hasJournal());assertTrue(temp.exists());assertEquals(partial,SafeDngReplace.hash(source));
        Files.delete(temp.toPath());assertThrows(IOException.class,tx::recover);assertTrue(tx.hasJournal());assertEquals(1,store.opens);
    }
    @Test public void diskMismatchAndCancellationPreventJournalAndDestructiveOpen()throws Exception {
        DngCompactorTest.Codec bad=new DngCompactorTest.Codec() {
            public short[] decode(byte[] bytes,int w,int h,int c)throws IOException {short[] s=super.decode(bytes,w,h,c);if(bytes[0]==1)s[0]^=1;return s;}
        };
        assertThrows(IOException.class,()->tx.replace("content://media/external/images/media/42",temp,source.length(),oldHash,bad,DngCompactorTest.GO));
        assertFalse(tx.hasJournal());assertEquals(0,store.opens);assertEquals(oldHash,SafeDngReplace.hash(source));
        assertThrows(DngCompactor.Stopped.class,()->tx.replace("content://media/external/images/media/42",temp,source.length(),oldHash,new DngCompactorTest.Codec(),()->{throw new DngCompactor.Stopped("cancelled");}));
        assertFalse(tx.hasJournal());assertEquals(0,store.opens);
    }
    @Test public void changedOriginalAndBadJournalFailClosed()throws Exception {
        Files.write(source.toPath(),new byte[]{1,2,3});assertThrows(IOException.class,this::replace);assertEquals(0,store.opens);assertFalse(tx.hasJournal());
        Files.write(new File(dir,"journal.json").toPath(),"{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThrows(IOException.class,tx::recover);assertTrue(temp.exists());assertTrue(tx.hasJournal());
    }
    @Test public void freeSpaceSkipBoundaryAndOverflow() {
        assertFalse(SafeDngReplace.enoughSpace(SafeDngReplace.RESERVE+999,1000));
        assertTrue(SafeDngReplace.enoughSpace(SafeDngReplace.RESERVE+1000,1000));
        assertFalse(SafeDngReplace.enoughSpace(Long.MAX_VALUE,Long.MAX_VALUE));assertFalse(SafeDngReplace.enoughSpace(0,0));
    }
    @Test public void orphanBeforeJournalCanBeDiscardedWithoutTouchingOriginal()throws Exception {
        tx.recover();assertFalse(temp.exists());assertEquals(oldHash,SafeDngReplace.hash(source));assertEquals(0,store.opens);
    }
}
