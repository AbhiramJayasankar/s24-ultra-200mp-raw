package dev.s24cam.dng;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** The entire destructive transaction and its recovery. Call under the single run lock.
 * Cancellation is allowed before journaling only. Once journaled, finish/repair the copy.
 * The durable verified temp is the recovery copy until the destination hash is confirmed.
 */
public final class SafeDngReplace {
    public static final long RESERVE = 512L * 1024 * 1024;
    public interface Store {
        /** Authoritative existence check. Throw on query/access failure; never infer absence from a read error. */
        boolean exists(String uri) throws IOException;
        FileChannel read(String uri) throws IOException;
        /** Must truncate, copy ALL bytes, fsync, and close, without cooperative cancellation. */
        void overwrite(String uri, File verifiedTemp) throws IOException;
        void refresh(String uri, long size) throws IOException;
        void syncDirectory(File directory) throws IOException;
        void log(String reason);
    }
    private final File directory, journal;
    private final Store store;
    public SafeDngReplace(File directory, Store store) throws IOException {
        this.directory=directory.getCanonicalFile(); this.store=store;
        Files.createDirectories(this.directory.toPath());
        journal=new File(this.directory,"journal.json");
    }
    public static boolean enoughSpace(long free,long originalSize) {
        return originalSize>=0 && free>=RESERVE && free-RESERVE>=originalSize;
    }
    public boolean hasJournal() { return !Files.notExists(journal.toPath()); }

    /** For deletion guards. A malformed/unreadable journal must block deletion until repaired. */
    public static String journalUri(File directory) throws IOException {
        File journal=new File(directory,"journal.json");
        if(Files.notExists(journal.toPath())) return null;
        return readRecord(journal).uri;
    }
    private static Record readRecord(File journal) throws IOException {
        if(journal.length()>16384) throw new IOException("Journal too large; preserving recovery files");
        return Record.parse(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
    }

    /** No writes to the destination until temp fsync + hash + independent disk verification. */
    public void replace(String uri, File temp, long originalSize, String originalHash,
                        DngCompactor.TileCodec codec, DngCompactor.Check check) throws IOException {
        if(hasJournal()) throw new IOException("Unrecovered journal; refusing another transaction");
        temp=checkedTemp(temp);
        try(FileChannel f=FileChannel.open(temp.toPath(),StandardOpenOption.WRITE)) { f.force(true); }
        store.syncDirectory(directory); // persist temp's name before publishing the journal
        String newHash=hash(temp); long newSize=temp.length();
        try(FileChannel original=store.read(uri);
            FileChannel replacement=FileChannel.open(temp.toPath(),StandardOpenOption.READ)) {
            if(original.size()!=originalSize || !hash(original).equals(originalHash))
                throw new IOException("Original changed during compaction");
            DngCompactor.verify(original,replacement,codec,check);
            if(!hash(replacement).equals(newHash)) throw new IOException("Temp changed during verification");
        }
        check.check();
        // Reopen immediately before journaling: never replace a changed source.
        if(!hashOriginal(uri).equals(originalHash)) throw new IOException("Original changed before overwrite");
        Record record=new Record(uri,temp.getCanonicalPath(),originalSize,originalHash,newSize,newHash);
        writeJournal(record);
        finish(record); // no cancellation from this point; errors leave both journal and temp
    }

    /** First operation of every run, before thermal/cancellation checks or new compactions. */
    public void recover() throws IOException {
        if(!hasJournal()) { cleanOrphans(); return; }
        Record r=readRecord(journal);
        checkedTemp(new File(r.temp));
        if(cleanMissing(r)) return;
        String current=hashOriginal(r.uri);
        if(current.equals(r.newHash)) {
            store.log("recovery: overwrite complete; refreshing and cleaning up");
            store.refresh(r.uri,r.newSize); cleanup(r);
        } else if(current.equals(r.originalHash)) {
            store.log("recovery: original intact; discarding unstarted replacement");
            cleanup(r);
        } else {
            store.log("recovery: partial/truncated original; restoring verified temp");
            finish(r);
        }
    }
    private void finish(Record r) throws IOException {
        File temp=checkedTemp(new File(r.temp));
        if(temp.length()!=r.newSize || !hash(temp).equals(r.newHash))
            throw new IOException("Recovery temp missing/corrupt: retaining journal and temp; original not opened for write");
        store.overwrite(r.uri,temp);
        if(!hashOriginal(r.uri).equals(r.newHash))
            throw new IOException("Destination hash mismatch: retaining journal and temp for recovery");
        store.refresh(r.uri,r.newSize);
        cleanup(r);
    }
    private void cleanup(Record r) throws IOException {
        if(cleanMissing(r)) return;
        // Recheck even on cleanup paths. NEVER delete the recovery copy for a partial original.
        String current=hashOriginal(r.uri);
        if(!current.equals(r.originalHash) && !current.equals(r.newHash))
            throw new IOException("Original changed before cleanup; retaining recovery copy");
        Files.deleteIfExists(journal.toPath()); store.syncDirectory(directory);
        Files.deleteIfExists(checkedTemp(new File(r.temp)).toPath()); store.syncDirectory(directory);
    }
    private boolean cleanMissing(Record r) throws IOException {
        if(store.exists(r.uri)) return false;
        store.log("recovery: original was deleted; discarding journal and temp");
        // Same durable ordering as normal cleanup. Absence is confirmed, not a failed open/hash.
        Files.deleteIfExists(journal.toPath()); store.syncDirectory(directory);
        Files.deleteIfExists(checkedTemp(new File(r.temp)).toPath()); store.syncDirectory(directory);
        return true;
    }
    private File checkedTemp(File file) throws IOException {
        File f=file.getCanonicalFile();
        if(!directory.equals(f.getParentFile()) || !f.getName().matches("[0-9]+\\.tmp"))
            throw new IOException("Invalid recovery temp path");
        return f;
    }
    private String hashOriginal(String uri) throws IOException {
        try(FileChannel f=store.read(uri)) { return hash(f); }
    }
    private void writeJournal(Record r) throws IOException {
        File staging=new File(directory,"journal.json.tmp");
        try(FileOutputStream out=new FileOutputStream(staging)) {
            out.write(r.json().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(staging.toPath(),journal.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        store.syncDirectory(directory);
    }
    private void cleanOrphans() throws IOException {
        // No durable journal means no overwrite may have started (journal deletion is synced first).
        File[] files=directory.listFiles();
        if(files==null) throw new IOException("Cannot list compact directory");
        for(File f:files) if(f.getName().matches("[0-9]+\\.tmp") || f.getName().equals("journal.json.tmp")) {
            store.log("recovery: deleting pre-journal/completed orphan " + f.getName());
            Files.deleteIfExists(f.toPath());
        }
        store.syncDirectory(directory);
    }
    public static String hash(File file) throws IOException {
        try(FileChannel f=FileChannel.open(file.toPath(),StandardOpenOption.READ)) { return hash(f); }
    }
    public static String hash(FileChannel f) throws IOException {
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
        ByteBuffer buffer=ByteBuffer.allocate(1<<20); long pos=0;
        for(;;) {
            buffer.clear(); int n=f.read(buffer,pos); if(n<0) break;
            if(n==0) continue; digest.update(buffer.array(),0,n); pos+=n;
        }
        StringBuilder s=new StringBuilder(64);
        for(byte b:digest.digest()) s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private static final class Record {
        final String uri,temp,originalHash,newHash; final long originalSize,newSize;
        Record(String uri,String temp,long originalSize,String originalHash,long newSize,String newHash) {
            this.uri=uri;this.temp=temp;this.originalSize=originalSize;this.originalHash=originalHash;this.newSize=newSize;this.newHash=newHash;
        }
        String json() {
            return "{\n\"uri\":"+quote(uri)+",\n\"temp\":"+quote(temp)+",\n\"originalSize\":"+originalSize
                    +",\n\"originalSHA256\":"+quote(originalHash)+",\n\"newSize\":"+newSize+",\n\"newSHA256\":"+quote(newHash)+"\n}\n";
        }
        static String quote(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""; }
        static Record parse(String json) throws IOException {
            // Fixed, deliberately small JSON schema; no library/Android dependency in recovery tests.
            if(json.length()>16384) throw new IOException("Journal too large");
            Pattern item=Pattern.compile("\\s*\"([A-Za-z0-9]+)\"\\s*:\\s*(\"(?:[^\"\\\\\\x00-\\x1f]|\\\\[\\\\\"])*\"|[0-9]+)\\s*");
            String s=json.trim(); if(!s.startsWith("{")||!s.endsWith("}")) throw new IOException("Invalid journal JSON");
            s=s.substring(1,s.length()-1); Map<String,String> m=new HashMap<>(); int at=0;
            while(at<s.length()) {
                Matcher match=item.matcher(s); match.region(at,s.length());
                if(!match.lookingAt()) throw new IOException("Invalid journal field");
                String v=match.group(2);
                if(v.startsWith("\"")) v=v.substring(1,v.length()-1).replace("\\\"","\"").replace("\\\\","\\");
                if(m.put(match.group(1),v)!=null) throw new IOException("Duplicate journal field");
                at=match.end(); if(at==s.length()) break;
                if(s.charAt(at++)!=',') throw new IOException("Invalid journal separator");
            }
            if(!m.keySet().equals(new HashSet<>(Arrays.asList("uri","temp","originalSize","originalSHA256","newSize","newSHA256"))))
                throw new IOException("Invalid journal schema");
            try {
                long old=Long.parseLong(m.get("originalSize")),size=Long.parseLong(m.get("newSize"));
                if(old<=0 || size<=0 || !m.get("originalSHA256").matches("[0-9a-f]{64}") || !m.get("newSHA256").matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException();
                return new Record(m.get("uri"),m.get("temp"),old,m.get("originalSHA256"),size,m.get("newSHA256"));
            } catch(IllegalArgumentException e) { throw new IOException("Invalid journal values",e); }
        }
    }
}
