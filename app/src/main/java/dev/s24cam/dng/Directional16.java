package dev.s24cam.dng;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

/**
 * Portable dir_port reconstruction of the native G B / R G 4x4-block mosaic.
 * Input is borrowed, starting at its position, and must remain unchanged. Output
 * is GBRG in the original black/white units. All frame statistics are immutable.
 *
 * The local working halo is 32 rows: one 12-pixel regression update, five
 * pixels for the separable bilateral (including its variance box), then a
 * 12-pixel output regression = 29. The initial coarse Menon interpolation
 * additionally reads at most 30 native rows; the RAW dependency is <=64 rows.
 * Band starts and halos are multiples of eight. True image edges use half-sample
 * reflection for boxes/Gaussians and whole-sample reflection for Menon/bilateral,
 * exactly as in Python; artificial band boundaries are discarded.
 *
 * One reusable workspace is shared under the band lock. Its width is partitioned
 * across up to eight workers. Completed bands are packed to RAW10, with a bounded
 * 250 MB LRU: the complete 16320x12240 result fits (249,696,000 bytes), avoiding
 * reconstruction on thumbnail, tile sizing, and tile writing passes. At that
 * size the 384-row workspace, coarse arrays, rings and output cache use about
 * 407 MB of primitive payload (including the direct output cache). Small stripes
 * are claimed dynamically by the workers to balance heterogeneous phone cores.
 * No per-pixel objects or allocations.
 * Close after use to release worker threads; threads are also daemon threads.
 * LinearRaw RGB mode optionally uses libs24lumdir's equivalent band kernels,
 * retaining this Java implementation for fallback and the legacy Bayer path.
 * See bench/algos/dir_port.py and bench/notes/dir_port.md for the specification.
 */
public final class Directional16 implements RowSource, AutoCloseable {
    static final int BAND_ROWS = 384, WORK_HALO = 32, RAW_HALO = 64;
    private static final double[] INTERPOLATION = {.625,.875,.125,.375};
    private static final int ROW_PARTS = 3;
    private final int rowParts;
    private static final int[] CFA = {1, 2, 0, 1};
    private static final long CACHE_LIMIT = 250_000_000L;
    private static final double[] RECIPROCAL = new double[170];
    static { for(int i=1;i<170;i++) RECIPROCAL[i]=1.0/i; }
    private static final float[] EXP = new float[4097];
    private static final float[] GAUSS_07 = gaussianKernel(.7, 1);
    static { for (int i = 0; i < EXP.length; i++) EXP[i] = (float) Math.exp(-i / 256.0); }

    private final boolean exactStatistics;
    private final ByteBuffer buffer;
    private final int stride, width, height, black, white, bandRows, threads;
    private final int workerThreads;
    private final float scale;
    private final float[] neutral;
    private final float[] gains = new float[64], offsets = new float[64];
    private final int[] interpolateLeft,interpolateRight;
    private final float[] balancedValues = new float[64*1024];
    private final float[][] noise = new float[3][2];
    private final ExecutorService pool;
    private final Scratch[] scratch;
    /** Finished bands, packed RAW10, kept in native memory: Android caps the Java heap at 512 MB. */
    private final java.nio.ByteBuffer[] cache;
    private final ThreadLocal<byte[]> rowBytes;
    /** Optional whole-frame store for finished bands (e.g. Android shared memory); null = per-band direct buffers. */
    private java.nio.ByteBuffer store;
    private final long[] ages;
    private long age, cacheBytes, peakArrayBytes;
    private float[] raw, guide, nativeFit;
    private int start, rows, lo, hi;
    private Coarse coarse;
    private final long[] timings = new long[5];
    private long bandComputations, hits;
    private boolean closed;
    private float[][] rgbOutput;
    private int rgbStart, rgbEnd;
    // Native RGB reconstruction owns bounded arenas; no Java arrays are pinned.
    private long nativeContext;
    private final boolean useNative;
    private final boolean nativeDag;
    private byte[] nativeInput;

    public Directional16(ByteBuffer raw10, int rowStride, int width, int height,
                         int black, int white, float[] asShotNeutral) {
        this(raw10, rowStride, width, height, black, white, asShotNeutral, BAND_ROWS);
    }

    /** Package-private band override for equivalence tests. Statistics never use it. */
    Directional16(ByteBuffer raw10, int rowStride, int width, int height,
                  int black, int white, float[] asShotNeutral, int bandRows) {
        this(raw10,rowStride,width,height,black,white,asShotNeutral,bandRows,false);
    }
    /** NumPy reductions for LinearRaw statistics; legacy Bayer arithmetic stays unchanged. */
    Directional16(ByteBuffer raw10,int rowStride,int width,int height,int black,int white,
                  float[] asShotNeutral,int bandRows,boolean exactStatistics) {
        this.exactStatistics=exactStatistics;
        useNative=exactStatistics&&LumdirNative.kernelsAvailable();
        nativeDag=useNative&&!Boolean.getBoolean("s24cam.lumdir.barriers");
        rowParts=exactStatistics?2:ROW_PARTS;
        if (width < 16 || height < 16 || width % 8 != 0 || height % 8 != 0
                || rowStride < width * 5L / 4 || black < 0 || white > 1023 || black >= white
                || bandRows < 8 || bandRows % 8 != 0)
            throw new IllegalArgumentException("Invalid 16-cell RAW10 geometry/levels/band size");
        if ((long) (height - 1) * rowStride + width * 5L / 4 > raw10.remaining())
            throw new IllegalArgumentException("Truncated RAW10 buffer");
        if (asShotNeutral == null || asShotNeutral.length != 3)
            throw new IllegalArgumentException("Three positive neutral values required");
        neutral = asShotNeutral.clone();
        for (float n : neutral) if (!(n > 0) || !Float.isFinite(n))
            throw new IllegalArgumentException("Invalid neutral");
        this.buffer = raw10.duplicate().slice().asReadOnlyBuffer();
        this.stride = rowStride; this.width = width; this.height = height;
        this.black = black; this.white = white; this.scale = white - black;
        this.bandRows = bandRows;
        interpolateLeft=new int[width]; interpolateRight=new int[width];
        for(int x=0;x<width;x++) {
            int x0=(x-2)>>2;
            interpolateLeft[x]=Math.max(0,Math.min(width/4-1,x0));
            interpolateRight[x]=Math.max(0,Math.min(width/4-1,x0+1));
        }
        threads = Math.max(1, Math.min(8, Math.min(width / 16, Runtime.getRuntime().availableProcessors())));
        // Scheduling concurrency may vary; stripe geometry/arithmetic must not.
        workerThreads=exactStatistics?Math.max(1,Math.min(threads,Integer.getInteger("s24cam.lumdir.directionalThreads",8))):threads;
        pool = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "Directional16"); t.setDaemon(true); return t;
        });
        // Several row chunks per original stripe keep its horizontal rounding
        // order while allowing fast cores to claim more work than slower cores.
        scratch = new Scratch[useNative?0:threads*rowParts];
        for(int t=0;t<scratch.length;t++) {
            int column=t%threads;
            scratch[t]=new Scratch(width*column/threads,width*(column+1)/threads);
        }
        int n = Math.multiplyExact(width, Math.min(height, bandRows + 2 * WORK_HALO));
        if(!useNative){raw = new float[n]; guide = new float[n]; nativeFit = new float[n];}
        cache = new java.nio.ByteBuffer[(height + bandRows - 1) / bandRows];
        rowBytes = ThreadLocal.withInitial(() -> new byte[width * 5 / 4]);
        ages = new long[cache.length];
        long t = System.nanoTime();
        try {
            calibrate();
            for (int p=0;p<64;p++) for(int v=0;v<1024;v++)
                balancedValues[p*1024+v]=(((v-black)/scale-offsets[p])/gains[p])/neutral[colour(p&7,p>>3)];
            estimateNoise();
            if(useNative) {
                float[] parameters={noise[0][0],noise[0][1],noise[1][0],noise[1][1],noise[2][0],noise[2][1]};
                nativeContext=LumdirNative.directionalCreate(buffer.isDirect()?buffer:null,stride,width,height,bandRows,threads,
                        balancedValues,neutral,parameters,EXP,GAUSS_07,Lumdir16.chromaKernel());
                if(!buffer.isDirect())nativeInput=new byte[Math.min(height,bandRows+2*RAW_HALO)*stride];
                coarse=null; // Statistics' Java coarse arrays are no longer needed.
            }
        } catch (RuntimeException | Error e) {
            if(nativeContext!=0){LumdirNative.directionalDestroy(nativeContext);nativeContext=0;}
            pool.shutdownNow();throw e;
        }
        timings[0] = System.nanoTime() - t;
        peakArrayBytes=arrayBytes();
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }

    @Override public void row(int y, int[] out) {
        if (y < 0 || y >= height || out.length < width) throw new IllegalArgumentException("Invalid row");
        java.nio.ByteBuffer packed = band(y / bandRows).duplicate();
        byte[] b = rowBytes.get();
        packed.position((y % bandRows) * width * 5 / 4);
        packed.get(b, 0, width * 5 / 4);
        int at = 0;
        for (int x = 0; x < width; x += 4, at += 5) {
            int low = b[at + 4] & 255;
            out[x] = ((b[at] & 255) << 2) | (low & 3);
            out[x+1] = ((b[at+1] & 255) << 2) | ((low >> 2) & 3);
            out[x+2] = ((b[at+2] & 255) << 2) | ((low >> 4) & 3);
            out[x+3] = ((b[at+3] & 255) << 2) | ((low >> 6) & 3);
        }
    }

    @Override public int sample(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IllegalArgumentException("Invalid sample");
        java.nio.ByteBuffer b = band(y / bandRows);
        int at = (y % bandRows) * width * 5 / 4 + (x >> 2) * 5;
        return ((b.get(at + (x & 3)) & 255) << 2) | ((b.get(at + 4) >> ((x & 3) * 2)) & 3);
    }

    /**
     * Keep finished bands in {@code wholeFrame} (capacity at least width * height * 5 / 4)
     * instead of allocating them. On Android, direct buffers count against the 512 MB Java
     * heap, so the app passes memory from android.os.SharedMemory. Nothing is evicted then.
     */
    public synchronized void setBandStore(java.nio.ByteBuffer wholeFrame) {
        if (wholeFrame.capacity() < (long) width * height * 5 / 4)
            throw new IllegalArgumentException("Band store too small");
        store = wholeFrame;
    }

    private synchronized java.nio.ByteBuffer band(int index) {
        if (closed) throw new IllegalStateException("Directional16 is closed");
        ages[index] = ++age;
        if (cache[index] != null) { hits++; return cache[index]; }
        int y0 = index * bandRows, y1 = Math.min(height, y0 + bandRows);
        int size = (y1 - y0) * width * 5 / 4;
        while (store == null && cacheBytes + size > CACHE_LIMIT && cacheBytes > 0) {
            int oldest = -1;
            for (int i = 0; i < cache.length; i++)
                if (cache[i] != null && (oldest < 0 || ages[i] < ages[oldest])) oldest = i;
            cacheBytes -= cache[oldest].capacity(); cache[oldest] = null;
        }
        start = Math.max(0, y0 - WORK_HALO);
        rows = Math.min(height, y1 + WORK_HALO) - start;
        lo = 0; hi = rows;
        long t = System.nanoTime();
        initialGuide();
        timings[1] += System.nanoTime() - t;
        t = System.nanoTime();
        fit(false);
        shrink(12);
        float[] previous = guide; guide = nativeFit; nativeFit = previous;
        timings[2] += System.nanoTime() - t;
        t = System.nanoTime();
        bilateral(); shrink(5);
        timings[3] += System.nanoTime() - t;
        t = System.nanoTime();
        fit(true);
        byte[] result = new byte[size];
        int chunks=threads*4;
        parallelChunks(chunks,k -> {
            // Partition rows here so every worker owns complete RAW10 groups.
            int first = y0 + (y1-y0)*k/chunks, last = y0 + (y1-y0)*(k+1)/chunks;
            for (int y = first; y < last; y++) {
                int dst = (y-y0)*width*5/4, src = (y-start)*width;
                for (int x = 0; x < width; x += 4, dst += 5) {
                    int low = 0;
                    for (int j = 0; j < 4; j++) {
                        int v = Math.max(0, Math.min(white, Math.round(black + nativeFit[src+x+j]*scale)));
                        result[dst+j] = (byte) (v >> 2); low |= (v & 3) << (j*2);
                    }
                    result[dst+4] = (byte) low;
                }
            }
        });
        timings[4] += System.nanoTime() - t;
        java.nio.ByteBuffer stored;
        if (store != null) {
            java.nio.ByteBuffer d = store.duplicate();
            d.position((int) ((long) y0 * width * 5 / 4));
            stored = d.slice();
            stored.limit(size);
            stored = stored.slice();
        } else {
            stored = java.nio.ByteBuffer.allocateDirect(size);
        }
        stored.put(result).rewind();
        cache[index] = stored; cacheBytes += size; bandComputations++;
        peakArrayBytes=Math.max(peakArrayBytes,arrayBytes());
        // All rows fit in the packed cache on the target frame. The two JPEG
        // passes can now use that cache without retaining reconstruction memory.
        if (bandComputations == cache.length && (long) width * height * 5 / 4 <= CACHE_LIMIT) {
            raw=null; guide=null; nativeFit=null; coarse=null;
            Arrays.fill(scratch,null);
        }
        return stored;
    }

    private void shrink(int radius) {
        if (start != 0) lo += radius;
        if (start + rows != height) hi -= radius;
    }

    private float read(int x, int y) {
        int at = y * stride + (x >> 2) * 5;
        int v = ((buffer.get(at + (x & 3)) & 255) << 2)
                | ((buffer.get(at + 4) >> ((x & 3) * 2)) & 3);
        return (v - black) / scale;
    }

    private float balanced(int x, int y) {
        int p = (y & 7)*8 + (x & 7);
        int at=y*stride+(x>>2)*5;
        int v=((buffer.get(at+(x&3))&255)<<2)|((buffer.get(at+4)>>((x&3)*2))&3);
        return balancedValues[p*1024+v];
    }

    private static int colour(int x, int y) { return 1+((x>>2)&1)-((y>>2)&1); }
    private static int bayerColour(int x, int y) { return 1+(x&1)-(y&1); }

    private void initialGuide() {
        int chunks=threads*4;
        parallelChunks(chunks,k -> {
            for(int y=rows*k/chunks;y<rows*(k+1)/chunks;y++) {
                int gy=start+y,base=y*width,phase=(gy&7)*8192;
                for(int x=0,at=gy*stride;x<width;x+=4,at+=5) {
                    int top=buffer.getInt(at),low=buffer.get(at+4)&255,p=phase+(x&7)*1024;
                    raw[base+x]=balancedValues[p+((top>>>24)<<2)+(low&3)];
                    raw[base+x+1]=balancedValues[p+1024+(((top>>>16)&255)<<2)+((low>>2)&3)];
                    raw[base+x+2]=balancedValues[p+2048+(((top>>>8)&255)<<2)+((low>>4)&3)];
                    raw[base+x+3]=balancedValues[p+3072+((top&255)<<2)+(low>>6)];
                }
            }
        });
        buildCoarse(start, start + rows);
        parallelStripes(k -> {
            Scratch s=scratch[k];
            float[] greens=coarse.g;
            int part=k/threads;
            for(int y=rows*part/rowParts;y<rows*(part+1)/rowParts;y++) {
                int gy=start+y,cy=(gy-2)>>2;
                int top=(Math.max(0,Math.min(height/4-1,cy))-coarse.first)*coarse.w;
                int bottom=(Math.max(0,Math.min(height/4-1,cy+1))-coarse.first)*coarse.w;
                double ay=INTERPOLATION[gy&3];
                for(int x=s.left;x<s.right;x++) {
                    int i=y*width+x,c=colour(x,gy),l=interpolateLeft[x],r=interpolateRight[x];
                    double ax=INTERPOLATION[x&3];
                    float g=interpolate(greens,top,bottom,l,r,ax,ay);
                    float p=c==1?g:interpolate(c==0?coarse.r:coarse.b,top,bottom,l,r,ax,ay);
                    guide[i]=raw[i]*clamp(g/Math.max(p,.01f),.25f,4f);
                }
            }
        });
    }

    private static float interpolate(float[] a,int top,int bottom,int l,int r,double ax,double ay) {
        // The original interpolate() rounds the right-corner product to float,
        // while 1.0-ax promotes the left product to double. Preserve that order.
        double t=a[top+l]*(1.0-ax)+a[top+r]*(float)ax;
        double b=a[bottom+l]*(1.0-ax)+a[bottom+r]*(float)ax;
        return (float)(t*(1.0-ay)+b*ay);
    }

    /** Stream all three masked regressions through stripe-local moment/coefficient rings.
     * Only the two estimates used by the update (or the one Bayer output) are emitted.
     * A stripe computes six extra coefficient columns, eliminating inter-worker barriers.
     */
    private void fit(boolean output) {
        int globalALo = lo + (start == 0 ? 0 : 6), globalAHi = hi - (start + rows == height ? 0 : 6);
        int globalOutLo = globalALo + (start == 0 ? 0 : 6), globalOutHi = globalAHi - (start + rows == height ? 0 : 6);
        parallelStripes(k -> {
            Scratch s = scratch[k];
            int part=k/threads;
            int outLo=globalOutLo+(globalOutHi-globalOutLo)*part/rowParts;
            int outHi=globalOutLo+(globalOutHi-globalOutLo)*(part+1)/rowParts;
            int aLo=Math.max(globalALo,outLo-6),aHi=Math.min(globalAHi,outHi+6);
            Arrays.fill(s.moments, 0);
            Arrays.fill(s.coefficients, 0);
            for (int j = 0; j < 13; j++) momentRow(s, reflect(aLo+j-6, rows), 0, j, true);
            int next = aLo;
            for (; next < Math.min(aHi,outLo+7); next++) coefficientRow(s, next, aLo, aHi);
            for (int j = -6; j <= 6; j++) {
                int slot = reflect(outLo+j, rows)%13*s.size*6;
                for (int q=0; q<s.size*6; q++) s.coefficients[q] += s.coefficientRing[slot+q];
            }
            for (int y=outLo; y<outHi; y++) {
                int base=y*width, gy=start+y;
                for (int x=s.left,q=0; x<s.right; x++,q+=6) {
                    int i=base+x;
                    int c=output?bayerColour(x,gy):colour(x,gy), z=q+c*2;
                    float v=(float)((s.coefficients[z]*guide[i]+s.coefficients[z+1])*(1.0/169.0));
                    if (output && rgbOutput != null) {
                        int dst=(gy-rgbStart)*width+x;
                        if(gy>=rgbStart && gy<rgbEnd) for(int channel=0;channel<3;channel++) {
                            int zc=q+channel*2;
                            float value=(float)((s.coefficients[zc]*guide[i]+s.coefficients[zc+1])*(1.0/169.0));
                            // camera_rgb multiplies by neutral, then lumdir divides it back.
                            rgbOutput[channel][dst]=Math.max(value*neutral[channel],0)/neutral[channel];
                        }
                    }
                    else if (output) nativeFit[i]=Math.max(v*neutral[c],0);
                    else {
                        float g=(float)((s.coefficients[q+2]*guide[i]+s.coefficients[q+3])*(1.0/169.0));
                        nativeFit[i]=raw[i]*clamp(g/Math.max(v,.01f),.3f,3f);
                    }
                }
                if (y+1==outHi) break;
                int old=reflect(y-6,rows)%13*s.size*6;
                for (int q=0; q<s.size*6; q++) s.coefficients[q]-=s.coefficientRing[old+q];
                if (next<aHi) coefficientRow(s,next++,aLo,aHi);
                int add=reflect(y+7,rows)%13*s.size*6;
                for (int q=0; q<s.size*6; q++) s.coefficients[q]+=s.coefficientRing[add+q];
            }
        });
    }

    private void momentRow(Scratch s,int y,int oldY,int slot,boolean initial) {
        // Each native row contains green and just one other colour. Eight ring
        // channels suffice; the vertical sums still retain all three colours.
        double gi=0,gp=0,gii=0,gip=0,ci=0,cp=0,cii=0,cip=0;
        int base=y*width,phase=(start+y)&4;
        int c=phase==0?8:0,oldC=((start+oldY)&4)==0?8:0;
        for(int dx=-6;dx<=6;dx++) {
            int x=reflect(s.extLeft+dx,width);
            float i=guide[base+x],p=raw[base+x];
            if((x&4)==phase) { gi+=i; gp+=p; gii+=(float)(i*i); gip+=(float)(i*p); }
            else { ci+=i; cp+=p; cii+=(float)(i*i); cip+=(float)(i*p); }
        }
        int ringBase=slot*s.extSize*8;
        for(int x=s.extLeft,e=0,q=0,r=ringBase;x<s.extRight;x++,e++,q+=12,r+=8) {
            if(!initial) s.moments[q+4]-=s.momentRing[r+0];
            s.momentRing[r+0]=(float)gi; s.moments[q+4]+=(float)gi;
            if(!initial) s.moments[q+5]-=s.momentRing[r+1];
            s.momentRing[r+1]=(float)gp; s.moments[q+5]+=(float)gp;
            if(!initial) s.moments[q+6]-=s.momentRing[r+2];
            s.momentRing[r+2]=(float)gii; s.moments[q+6]+=(float)gii;
            if(!initial) s.moments[q+7]-=s.momentRing[r+3];
            s.momentRing[r+3]=(float)gip; s.moments[q+7]+=(float)gip;
            if(!initial) s.moments[q+oldC+0]-=s.momentRing[r+4];
            s.momentRing[r+4]=(float)ci; s.moments[q+c+0]+=(float)ci;
            if(!initial) s.moments[q+oldC+1]-=s.momentRing[r+5];
            s.momentRing[r+5]=(float)cp; s.moments[q+c+1]+=(float)cp;
            if(!initial) s.moments[q+oldC+2]-=s.momentRing[r+6];
            s.momentRing[r+6]=(float)cii; s.moments[q+c+2]+=(float)cii;
            if(!initial) s.moments[q+oldC+3]-=s.momentRing[r+7];
            s.momentRing[r+7]=(float)cip; s.moments[q+c+3]+=(float)cip;
            int old=s.momentOld[e],add=s.momentAdd[e];
            float i=guide[base+old],p=raw[base+old];
            if((old&4)==phase) { gi-=i; gp-=p; gii-=(float)(i*i); gip-=(float)(i*p); }
            else { ci-=i; cp-=p; cii-=(float)(i*i); cip-=(float)(i*p); }
            i=guide[base+add]; p=raw[base+add];
            if((add&4)==phase) { gi+=i; gp+=p; gii+=(float)(i*i); gip+=(float)(i*p); }
            else { ci+=i; cp+=p; cii+=(float)(i*i); cip+=(float)(i*p); }
        }
    }

    private void coefficientRow(Scratch s,int y,int aLo,int aHi) {
        // Native R/B counts factor into horizontal and vertical block counts;
        // the green count is their complement. No count ring or float division.
        int ny=0;
        for(int j=-6;j<=6;j++) if((reflect(y+j,rows)&4)==0) ny++;
        for(int x=s.extLeft,q=0,z=0;x<s.extRight;x++,q+=12,z+=6) {
            int nx=s.counts[x-s.extLeft];
            {
                double inv=RECIPROCAL[nx*(13-ny)];
                double mi=s.moments[q+0]*inv,mp=s.moments[q+1]*inv;
                double var=Math.max(s.moments[q+2]*inv-mi*mi,0);
                double cov=s.moments[q+3]*inv-mi*mp;
                float a=(float)(cov/(var+.0001));
                s.rowCoefficients[z+0]=a; s.rowCoefficients[z+1]=(float)(mp-a*mi);
            }
            {
                double inv=RECIPROCAL[nx*ny+(13-nx)*(13-ny)];
                double mi=s.moments[q+4]*inv,mp=s.moments[q+5]*inv;
                double var=Math.max(s.moments[q+6]*inv-mi*mi,0);
                double cov=s.moments[q+7]*inv-mi*mp;
                float a=(float)(cov/(var+.0001));
                s.rowCoefficients[z+2]=a; s.rowCoefficients[z+3]=(float)(mp-a*mi);
            }
            {
                double inv=RECIPROCAL[(13-nx)*ny];
                double mi=s.moments[q+8]*inv,mp=s.moments[q+9]*inv;
                double var=Math.max(s.moments[q+10]*inv-mi*mi,0);
                double cov=s.moments[q+11]*inv-mi*mp;
                float a=(float)(cov/(var+.0001));
                s.rowCoefficients[z+4]=a; s.rowCoefficients[z+5]=(float)(mp-a*mi);
            }
        }
        double a0=0,b0=0,a1=0,b1=0,a2=0,b2=0;
        for(int dx=-6;dx<=6;dx++) {
            int z=(reflect(s.left+dx,width)-s.extLeft)*6;
            a0+=s.rowCoefficients[z+0];
            b0+=s.rowCoefficients[z+1];
            a1+=s.rowCoefficients[z+2];
            b1+=s.rowCoefficients[z+3];
            a2+=s.rowCoefficients[z+4];
            b2+=s.rowCoefficients[z+5];
        }
        int ringBase=y%13*s.size*6;
        for(int x=s.left,e=0,q=0;x<s.right;x++,e++,q+=6) {
            s.coefficientRing[ringBase+q+0]=(float)a0;
            s.coefficientRing[ringBase+q+1]=(float)b0;
            s.coefficientRing[ringBase+q+2]=(float)a1;
            s.coefficientRing[ringBase+q+3]=(float)b1;
            s.coefficientRing[ringBase+q+4]=(float)a2;
            s.coefficientRing[ringBase+q+5]=(float)b2;
            if(x+1<s.right) {
                int old=s.coefficientOld[e],add=s.coefficientAdd[e];
                a0+=s.rowCoefficients[add+0]-s.rowCoefficients[old+0];
                b0+=s.rowCoefficients[add+1]-s.rowCoefficients[old+1];
                a1+=s.rowCoefficients[add+2]-s.rowCoefficients[old+2];
                b1+=s.rowCoefficients[add+3]-s.rowCoefficients[old+3];
                a2+=s.rowCoefficients[add+4]-s.rowCoefficients[old+4];
                b2+=s.rowCoefficients[add+5]-s.rowCoefficients[old+5];
            }
        }
        if(y+1<aHi) momentRow(s,reflect(y+7,rows),reflect(y-6,rows),(y-aLo)%13,false);
    }

    /** Stripe-local streaming denoiser: retain seven horizontal rows, not whole bands. */
    private void bilateral() {
        parallelStripes(k -> {
            Scratch s=scratch[k];
            int globalFrom=lo+(start==0?0:5),globalTo=hi-(start+rows==height?0:5),part=k/threads;
            int from=globalFrom+(globalTo-globalFrom)*part/rowParts;
            int to=globalFrom+(globalTo-globalFrom)*(part+1)/rowParts;
            int hFrom=Math.max(lo+(start==0?0:2),from-3),hTo=Math.min(hi-(start+rows==height?0:2),to+3);
            int nextNoise=Math.max(0,hFrom-2),next=hFrom;
            for(;next<=from+3;next++) nextNoise=bilateralRow(s,next,nextNoise);
            for(int y=from;y<to;y++) {
                int center=y%7*s.size;
                int r0=mirror(y-3,rows)%7*s.size;
                int r1=mirror(y-2,rows)%7*s.size;
                int r2=mirror(y-1,rows)%7*s.size;
                int r4=mirror(y+1,rows)%7*s.size;
                int r5=mirror(y+2,rows)%7*s.size;
                int r6=mirror(y+3,rows)%7*s.size;
                for(int x=s.left,q=0;x<s.right;x++,q++) {
                    float ref=s.reference[center+q],inverse=s.variance[center+q];
                    float sum=0,total=0,w;
                    w=rangeWeight(s.reference[r0+q]-ref,inverse,2f);
                    sum+=w*s.filtered[r0+q]; total+=w;
                    w=rangeWeight(s.reference[r1+q]-ref,inverse,0.8888888888888888f);
                    sum+=w*s.filtered[r1+q]; total+=w;
                    w=rangeWeight(s.reference[r2+q]-ref,inverse,0.2222222222222222f);
                    sum+=w*s.filtered[r2+q]; total+=w;
                    sum+=s.filtered[center+q]; total+=1;
                    w=rangeWeight(s.reference[r4+q]-ref,inverse,0.2222222222222222f);
                    sum+=w*s.filtered[r4+q]; total+=w;
                    w=rangeWeight(s.reference[r5+q]-ref,inverse,0.8888888888888888f);
                    sum+=w*s.filtered[r5+q]; total+=w;
                    w=rangeWeight(s.reference[r6+q]-ref,inverse,2f);
                    sum+=w*s.filtered[r6+q]; total+=w;
                    nativeFit[y*width+x]=sum/total;
                }
                if(y+1<to&&next<hTo) nextNoise=bilateralRow(s,next++,nextNoise);
            }
        });
        float[] previous=guide; guide=nativeFit; nativeFit=previous;
    }

    private int bilateralRow(Scratch s,int y,int nextNoise) {
        int noiseWidth=s.size+4;
        for(;nextNoise<=Math.min(rows-1,y+2);nextNoise++) {
            int base=nextNoise*width,slot=nextNoise%5*noiseWidth;
            for(int x=s.left-2,q=0;x<s.right+2;x++,q++) {
                int px=reflect(x,width),c=colour(px,start+nextNoise);
                s.noiseRows[slot+q]=noise[c][0]*Math.max(guide[base+px],0)+noise[c][1];
            }
        }
        int n0=reflect(y-2,rows)%5*noiseWidth,n1=reflect(y-1,rows)%5*noiseWidth,n2=y%5*noiseWidth;
        int n3=reflect(y+1,rows)%5*noiseWidth,n4=reflect(y+2,rows)%5*noiseWidth;
        for(int q=0;q<noiseWidth;q++) s.varianceRow[q]=(float)(((double)s.noiseRows[n0+q]
                +s.noiseRows[n1+q]+s.noiseRows[n2+q]+s.noiseRows[n3+q]+s.noiseRows[n4+q])/5);
        int slot=y%7*s.size;
        double sum=0;
        for(int j=0;j<5;j++) sum+=s.varianceRow[j];
        for(int q=0;q<s.size;q++) {
            float variance=(float)(sum/5);
            // Both bilateral passes use exactly the same variance and reference.
            s.variance[slot+q]=1/(3*variance+1e-8f);
            if(q+1<s.size) sum+=(double)s.varianceRow[q+5]-s.varianceRow[q];
        }
        float side=GAUSS_07[0],center=GAUSS_07[1];
        int top=reflect(y-1,rows)*width,base=y*width,bottom=reflect(y+1,rows)*width;
        int left=s.blurLeft,right=s.blurRight;
        for(int x=left;x<right;x++) s.blurRow[x-left]=side*(guide[top+x]+guide[bottom+x])+center*guide[base+x];
        for(int q=0;q<s.referenceRow.length;q++)
            s.referenceRow[q]=side*(s.blurRow[s.referenceLeft[q]]+s.blurRow[s.referenceRight[q]])
                    +center*s.blurRow[s.referenceCenter[q]];
        for(int x=s.left,q=0;x<s.right;x++,q++) {
            float ref=s.referenceRow[q+3],inverse=s.variance[slot+q];
            float total=0,value=0,w;
            w=rangeWeight(s.referenceRow[q+0]-ref,inverse,2f);
            value+=w*guide[base+s.filterX[q*6+0]]; total+=w;
            w=rangeWeight(s.referenceRow[q+1]-ref,inverse,0.8888888888888888f);
            value+=w*guide[base+s.filterX[q*6+1]]; total+=w;
            w=rangeWeight(s.referenceRow[q+2]-ref,inverse,0.2222222222222222f);
            value+=w*guide[base+s.filterX[q*6+2]]; total+=w;
            value+=guide[base+x]; total+=1;
            w=rangeWeight(s.referenceRow[q+4]-ref,inverse,0.2222222222222222f);
            value+=w*guide[base+s.filterX[q*6+3]]; total+=w;
            w=rangeWeight(s.referenceRow[q+5]-ref,inverse,0.8888888888888888f);
            value+=w*guide[base+s.filterX[q*6+4]]; total+=w;
            w=rangeWeight(s.referenceRow[q+6]-ref,inverse,2f);
            value+=w*guide[base+s.filterX[q*6+5]]; total+=w;
            s.filtered[slot+q]=value/total;
            s.reference[slot+q]=ref;
        }
        return nextNoise;
    }

    private static float rangeWeight(float diff,float inverse,float spatial) {
        float t=Math.min((spatial+diff*diff*inverse)*256,4095.9998f);
        int idx=(int)t;
        return EXP[idx]+(EXP[idx+1]-EXP[idx])*(t-idx);
    }

    private static float[] gaussianKernel(double sigma,int radius) {
        float[] k=new float[2*radius+1]; double sum=0;
        for(int j=-radius;j<=radius;j++) sum+=Math.exp(-.5*j*j/(sigma*sigma));
        for(int j=-radius;j<=radius;j++) k[j+radius]=(float)(Math.exp(-.5*j*j/(sigma*sigma))/sum);
        return k;
    }

    private void parallelStripes(IntConsumer action) {
        parallelChunks(scratch.length,action);
    }

    private void parallelChunks(int chunks,IntConsumer action) {
        java.util.concurrent.atomic.AtomicInteger next=new java.util.concurrent.atomic.AtomicInteger();
        parallel(worker -> {
            for(int chunk;(chunk=next.getAndIncrement())<chunks;) action.accept(chunk);
        });
    }

    private void parallel(IntConsumer action) {
        Future<?>[] jobs=new Future<?>[workerThreads];
        for(int k=0;k<workerThreads;k++) { final int worker=k; jobs[k]=pool.submit(()->action.accept(worker)); }
        // Drain every worker even on failure before callers can unmap borrowed memory.
        Throwable failure=null; boolean interrupted=false;
        for(Future<?> job:jobs) for(;;) {
            try { job.get(); break; }
            catch(InterruptedException e) { interrupted=true; if(failure==null)failure=e; }
            catch(java.util.concurrent.ExecutionException e) { if(failure==null)failure=e.getCause(); break; }
        }
        if(interrupted)Thread.currentThread().interrupt();
        if(failure!=null)throw new IllegalStateException(failure);
    }

    private final class Scratch {
        final int left,right,size,extLeft,extRight,extSize,blurLeft,blurRight;
        final float[] momentRing,coefficientRing,rowCoefficients;
        final float[] noiseRows,varianceRow,blurRow,referenceRow,reference,variance,filtered;
        final double[] moments,coefficients,horizontal=new double[12];
        final int[] counts,momentOld,momentAdd,coefficientOld,coefficientAdd,filterX;
        final int[] referenceLeft,referenceRight,referenceCenter;
        Scratch(int left,int right) {
            this.left=left; this.right=right; size=right-left;
            extLeft=Math.max(0,left-6); extRight=Math.min(width,right+6); extSize=extRight-extLeft;
            momentRing=new float[13*extSize*8]; moments=new double[extSize*12];
            coefficientRing=new float[13*size*6]; coefficients=new double[size*6];
            rowCoefficients=new float[extSize*6]; counts=new int[extSize];
            filterX=new int[size*6];
            for(int x=left,q=0;x<right;x++,q+=6) {
                filterX[q]=mirror(x-3,width); filterX[q+1]=mirror(x-2,width); filterX[q+2]=mirror(x-1,width);
                filterX[q+3]=mirror(x+1,width); filterX[q+4]=mirror(x+2,width); filterX[q+5]=mirror(x+3,width);
            }
            blurLeft=Math.max(0,left-4); blurRight=Math.min(width,right+4);
            referenceLeft=new int[size+6]; referenceRight=new int[size+6]; referenceCenter=new int[size+6];
            for(int x=left-3,q=0;x<right+3;x++,q++) {
                int px=mirror(x,width);
                referenceLeft[q]=(px==0?0:px-1)-blurLeft;
                referenceRight[q]=(px==width-1?width-1:px+1)-blurLeft;
                referenceCenter[q]=px-blurLeft;
            }
            momentOld=new int[extSize]; momentAdd=new int[extSize];
            coefficientOld=new int[size]; coefficientAdd=new int[size];
            for(int x=extLeft;x<extRight;x++) {
                momentOld[x-extLeft]=reflect(x-6,width); momentAdd[x-extLeft]=reflect(x+7,width);
            }
            for(int x=left;x<right;x++) {
                coefficientOld[x-left]=(reflect(x-6,width)-extLeft)*6;
                coefficientAdd[x-left]=(reflect(x+7,width)-extLeft)*6;
            }
            noiseRows=new float[5*(size+4)]; varianceRow=new float[size+4];
            blurRow=new float[size+8]; referenceRow=new float[size+6];
            reference=new float[7*size]; variance=new float[7*size]; filtered=new float[7*size];
            for(int x=extLeft;x<extRight;x++) for(int j=-6;j<=6;j++)
                if((reflect(x+j,width)&4)==0) counts[x-extLeft]++;
        }
        long bytes() {
            return 4L*(filterX.length+referenceLeft.length+referenceRight.length+referenceCenter.length+momentOld.length+momentAdd.length+coefficientOld.length+coefficientAdd.length+noiseRows.length+varianceRow.length+blurRow.length+referenceRow.length
                    +reference.length+variance.length+filtered.length+momentRing.length+coefficientRing.length+rowCoefficients.length+counts.length)
                    +8L*(moments.length+coefficients.length+horizontal.length);
        }
    }

    private static int reflect(int i,int n) {
        if(i>=0&&i<n) return i;
        int p=Math.floorMod(i,2*n); return p<n?p:2*n-1-p;
    }
    private static int mirror(int i,int n) {
        if(i>=0&&i<n) return i;
        int p=Math.floorMod(i,2*n-2); return p<n?p:2*n-2-p;
    }
    private static float clamp(float v,float a,float b) { return Math.max(a,Math.min(b,v)); }

    /** Snapshot in seconds: statistics, guide, update completion, bilateral tail,
     * output tail. With the native dependency scheduler the last three are
     * exclusive completion intervals, while their worker tasks overlap. */
    public synchronized double[] stageSeconds() {
        double[] seconds=new double[timings.length];
        for(int i=0;i<seconds.length;i++) seconds[i]=timings[i]/1e9;
        return seconds;
    }
    /** White-balanced camera RGB for an aligned band. No Bayer quantization/cache is used.
     * Caller supplies three arrays, each at least (last-first)*width floats. */
    synchronized void rgbBand(int first,int last,float[][] destination) {
        if(closed) throw new IllegalStateException("Directional16 is closed");
        if(first<0 || last>height || first%8!=0 || last%8!=0 || last<=first
                || last-first>bandRows || destination.length!=3)
            throw new IllegalArgumentException("Invalid RGB band");
        for(float[] a:destination) if(a.length<(last-first)*width)
            throw new IllegalArgumentException("RGB band array size");
        if(useNative){nativeRgbBand(first,last);LumdirNative.directionalCopyRgb(nativeContext,destination[0],destination[1],destination[2]);return;}
        start=Math.max(0,first-WORK_HALO); rows=Math.min(height,last+WORK_HALO)-start;
        lo=0; hi=rows;
        long t=System.nanoTime(); initialGuide(); timings[1]+=System.nanoTime()-t;
        t=System.nanoTime(); fit(false); shrink(12);
        float[] previous=guide; guide=nativeFit; nativeFit=previous;
        timings[2]+=System.nanoTime()-t;
        t=System.nanoTime(); bilateral(); shrink(5); timings[3]+=System.nanoTime()-t;
        t=System.nanoTime(); rgbOutput=destination; rgbStart=first; rgbEnd=last;
        try { fit(true); } finally { rgbOutput=null; }
        timings[4]+=System.nanoTime()-t; bandComputations++;
        peakArrayBytes=Math.max(peakArrayBytes,arrayBytes());
    }
    boolean nativeKernels(){return useNative;}
    boolean nativeDag(){return nativeDag;}
    int workerThreads(){return workerThreads;}
    synchronized long nativeBytes(){return nativeContext==0?0:LumdirNative.directionalBytes(nativeContext);}
    /** Preserve stripe geometry, reflection and arithmetic; schedule dependencies when enabled. */
    synchronized void nativeRgbBand(int first,int last) {
        if(closed)throw new IllegalStateException("Directional16 is closed");
        if(!useNative||first<0||last>height||first%8!=0||last%8!=0||last<=first||last-first>bandRows)
            throw new IllegalArgumentException("Invalid native RGB band");
        if(nativeInput!=null) {
            int from=Math.max(0,first-RAW_HALO),to=Math.min(height,last+RAW_HALO);
            ByteBuffer src=buffer.duplicate();src.position(from*stride);
            int bytes=(to-from-1)*stride+width*5/4;
            src.get(nativeInput,0,bytes);LumdirNative.directionalInput(nativeContext,nativeInput,from,to-from);
        }
        LumdirNative.directionalBegin(nativeContext,first,last);
        long t=System.nanoTime();
        for(int stage=0;stage<8;stage++){final int s=stage;parallelChunks(threads*4,k->LumdirNative.directionalStage(nativeContext,s,k,threads*4));}
        timings[1]+=System.nanoTime()-t;
        if(nativeDag){nativeStripeStages();bandComputations++;return;}
        t=System.nanoTime();parallelChunks(threads*rowParts,k->LumdirNative.directionalStage(nativeContext,8,k,threads*rowParts));
        LumdirNative.directionalStage(nativeContext,9,0,1);timings[2]+=System.nanoTime()-t;
        t=System.nanoTime();parallelChunks(threads*rowParts,k->LumdirNative.directionalStage(nativeContext,10,k,threads*rowParts));
        LumdirNative.directionalStage(nativeContext,11,0,1);timings[3]+=System.nanoTime()-t;
        t=System.nanoTime();parallelChunks(threads*rowParts,k->LumdirNative.directionalStage(nativeContext,12,k,threads*rowParts));
        timings[4]+=System.nanoTime()-t;bandComputations++;
    }
    /** Dependency-ready column stripes fill slow-core barrier tails. Preserve the
     * original stripe widths, row parts and reduction order. Adjacent columns
     * protect both read halos and arenas reused by the following stage.
     * Timings are exclusive completion intervals: update, bilateral tail, RGB tail. */
    private void nativeStripeStages() {
        int count=threads*rowParts;
        java.util.concurrent.CompletionService<Integer> done=new java.util.concurrent.ExecutorCompletionService<>(pool);
        int[] finished=new int[3],completed=new int[3],queuedColumns=new int[3];
        int active=0;boolean interrupted=false;Throwable failure=null;long boundary=System.nanoTime();
        for(int k=0;k<count;k++){submitStripe(done,0,k,count);active++;}
        while(active>0) {
            Future<Integer> result;
            for(;;)try{result=done.take();break;}catch(InterruptedException e){interrupted=true;failure=e;}
            active--;
            int tag;
            try{tag=result.get();}catch(InterruptedException e){interrupted=true;failure=e;continue;}
            catch(java.util.concurrent.ExecutionException e){failure=e.getCause();continue;}
            int stage=tag/count,k=tag%count;finished[stage]|=1<<k;
            if(++completed[stage]==count){long now=System.nanoTime();timings[stage+2]+=now-boundary;boundary=now;}
            if(failure!=null)continue;
            for(int next=1;next<3;next++)for(int col=0;col<threads;col++) {
                if((queuedColumns[next]&(1<<col))!=0)continue;
                int dependency=0;
                for(int x=Math.max(0,col-1);x<=Math.min(threads-1,col+1);x++)
                    for(int part=0;part<rowParts;part++)dependency|=1<<(part*threads+x);
                if((finished[next-1]&dependency)!=dependency)continue;
                queuedColumns[next]|=1<<col;
                for(int part=0;part<rowParts;part++){submitStripe(done,next,part*threads+col,count);active++;}
            }
        }
        if(interrupted)Thread.currentThread().interrupt();
        if(failure!=null)throw new IllegalStateException("Native stripe stages failed",failure);
    }
    private void submitStripe(java.util.concurrent.CompletionService<Integer> done,int stage,int task,int count) {
        done.submit(()->{LumdirNative.directionalStripe(nativeContext,8+2*stage,task);return stage*count+task;});
    }
    synchronized void nativeChromaBand(int first,int last,int ringRows,ByteBuffer[] stores) {
        LumdirNative.chromaBegin(nativeContext,first,last,ringRows,stores[0],stores[1],stores[2],stores[3]);
        parallelChunks(threads*4,k->LumdirNative.directionalStage(nativeContext,13,k,threads*4));
        // Keep the colour producer independent of Lumdir's median/relative pool.
        // Otherwise its Gaussian rows can queue behind an entire median band.
        java.util.concurrent.atomic.AtomicInteger next=new java.util.concurrent.atomic.AtomicInteger(first);
        parallel(worker->{for(int y;(y=next.getAndIncrement())<last;)LumdirNative.chromaRows(nativeContext,worker,y,y+1);});
    }
    float[][] noiseParameters() { return new float[][]{noise[0].clone(),noise[1].clone(),noise[2].clone()}; }
    float[] balancedLookup() { return balancedValues.clone(); }
    public synchronized long computedBands() { return bandComputations; }
    public synchronized long cacheHits() { return hits; }
    public synchronized long cachedBytes() { return cacheBytes; }
    public synchronized long peakArrayBytes() { return peakArrayBytes; }
    /** Live primitive array payload, excluding the borrowed RAW buffer and task objects. */
    public synchronized long arrayBytes() {
        long n=(raw==null?0:3L*raw.length*4)+balancedValues.length*4L+interpolateLeft.length*8L+INTERPOLATION.length*8L+RECIPROCAL.length*8L+cacheBytes+64L*8+3L*2*4+ages.length*8L
                +neutral.length*4L+EXP.length*4L+GAUSS_07.length*4L+CFA.length*4L;
        if(nativeInput!=null)n+=nativeInput.length;
        for(Scratch s:scratch) if(s!=null) n+=s.bytes();
        return n+(coarse==null?0:coarse.bytes());
    }
    @Override public synchronized void close() {
        if(nativeContext!=0){LumdirNative.directionalDestroy(nativeContext);nativeContext=0;}
        closed=true; pool.shutdownNow(); Arrays.fill(cache,null); cacheBytes=0;
        raw=null; guide=null; nativeFit=null; coarse=null;
        Arrays.fill(scratch,null);
    }

    private float blockMean(int cy,int cx,int dy,int dx) {
        if(exactStatistics) {
            int x=cx*8+dx,y=cy*8+dy;
            float a0=read(x,y)+read(x,y+2),a1=read(x+1,y)+read(x+1,y+2);
            float a2=read(x+2,y)+read(x+2,y+2),a3=read(x+3,y)+read(x+3,y+2);
            float a4=read(x,y+1)+read(x,y+3),a5=read(x+1,y+1)+read(x+1,y+3);
            float a6=read(x+2,y+1)+read(x+2,y+3),a7=read(x+3,y+1)+read(x+3,y+3);
            return (((a0+a1)+(a2+a3))+((a4+a5)+(a6+a7)))/16;
        }
        float sum=0;
        for(int y=0;y<4;y++) for(int x=0;x<4;x++) sum+=read(cx*8+dx+x,cy*8+dy+y);
        return sum/16;
    }

    private void calibrate() {
        Arrays.fill(gains,1);
        if(exactStatistics&&!Boolean.getBoolean("s24cam.lumdir.serialSetup"))
            parallelChunks(4,p->calibratePhase((p>>1)*4,(p&1)*4));
        else for(int dy=0;dy<=4;dy+=4)for(int dx=0;dx<=4;dx+=4)calibratePhase(dy,dx);
    }
    /** Each physical 4x4 phase has independent reductions and 16 disjoint outputs. */
    private void calibratePhase(int dy,int dx) {
        int ch=height/8,cw=width/8;
        int step=Math.max(1,(int)Math.ceil(Math.sqrt((double)ch*cw/32768)));
        int capacity=((ch+step-1)/step)*((cw+step-1)/step);
        double[] signal=new double[capacity],tmp=new double[capacity];
        double[][] residual=new double[16][capacity];
        double[] values=new double[16];
        int n=0;
        for(int cy=0;cy<ch;cy+=step) for(int cx=0;cx<cw;cx+=step) {
            float mean=blockMean(cy,cx,dy,dx);
            if(mean<=.035||mean>=.72) continue;
            int ym=Math.max(cy-1,0),yp=Math.min(cy+1,ch-1);
            int xm=Math.max(cx-1,0),xp=Math.min(cx+1,cw-1);
            double gy=(blockMean(yp,cx,dy,dx)-blockMean(ym,cx,dy,dx))/(double)(yp-ym);
            double gx=(blockMean(cy,xp,dy,dx)-blockMean(cy,xm,dy,dx))/(double)(xp-xm);
            if(Math.hypot(gy,gx)>=.01+.08*mean) continue;
            double scatter=0; float max=-Float.MAX_VALUE;
            for(int y=0;y<4;y++) for(int x=0;x<4;x++) {
                float v=read(cx*8+dx+x,cy*8+dy+y); max=Math.max(max,v);
                double r=v-(mean+gy*(y-1.5)/8+gx*(x-1.5)/8);
                values[y*4+x]=r; scatter+=r*r;
            }
            if(max>=.95||Math.sqrt(scatter/16)>=.007+.045*mean) continue;
            signal[n]=mean;
            for(int p=0;p<16;p++) residual[p][n]=values[p];
            n++;
        }
        if(n<128) return;
        double[] cuts=cuts(signal,n,12),xs=new double[12],weights=new double[12];
        double[][] ys=new double[16][12]; int bins=0;
        for(int bin=0;bin+1<cuts.length;bin++) {
            int count=select(signal,signal,n,cuts[bin],cuts[bin+1],tmp,false);
            if(count<48) continue;
            xs[bins]=exactStatistics?(float)median(tmp,count):median(tmp,count); weights[bins]=count;
            for(int p=0;p<16;p++) {
                count=select(signal,residual[p],n,cuts[bin],cuts[bin+1],tmp,false);
                ys[p][bins]=median(tmp,count);
            }
            bins++;
        }
        if(bins<3) return;
        double[] gs=new double[16],os=new double[16]; double gm=0,om=0;
        for(int p=0;p<16;p++) {
            double[] fit=regression(xs,ys[p],weights,bins);
            gs[p]=fit[0]; os[p]=fit[1]; gm+=gs[p]/16; om+=os[p]/16;
        }
        for(int y=0;y<4;y++) for(int x=0;x<4;x++) {
            int p=y*4+x,i=(dy+y)*8+dx+x;
            gains[i]=(float)Math.max(.9,Math.min(1.1,1+gs[p]-gm));
            offsets[i]=(float)Math.max(-.01,Math.min(.01,os[p]-om));
        }
    }
    private void estimateNoise() {
        int step=Math.max(1,(int)Math.ceil(Math.sqrt((height/2.0)*(width/2)/65536)));
        step|=1; // Odd in 2x2 units: cover every CFA phase, even on full frames.
        int capacity=((height/2+step-1)/step)*((width/2+step-1)/step);
        double[][] means=new double[3][capacity],haar=new double[3][capacity];
        int[] sizes=new int[3]; int chunk=-1;
        for(int y=0;y<height;y+=step*2) {
            if(y/384!=chunk) {
                chunk=y/384; buildCoarse(chunk*384,Math.min(height,chunk*384+384));
            }
            for(int x=0;x<width;x+=step*2) {
                float a=initialSample(x,y),b=initialSample(x+1,y);
                float c=initialSample(x,y+1),d=initialSample(x+1,y+1);
                float mean=(a+b+c+d)*.25f,hh=(a-b-c+d)*.5f;
                if(mean>.03&&mean<1.3) {
                    int colour=colour(x,y),i=sizes[colour]++;
                    means[colour][i]=mean; haar[colour][i]=hh;
                }
            }
        }
        double[] tmp=new double[capacity];
        for(int c=0;c<3;c++) {
            int n=sizes[c],bins=0;
            double[] xs=new double[9],ys=new double[9],ww=new double[9];
            if(n>=128) {
                double[] cuts=cuts(means[c],n,9);
                for(int bin=0;bin+1<cuts.length;bin++) {
                    int count=select(means[c],means[c],n,cuts[bin],cuts[bin+1],tmp,false);
                    if(count<=64) continue;
                    xs[bins]=exactStatistics?(float)median(tmp,count):median(tmp,count);
                    count=select(means[c],haar[c],n,cuts[bin],cuts[bin+1],tmp,true);
                    double sigma=exactStatistics?(float)median(tmp,count)/.67449f:median(tmp,count)/.67449;
                    ys[bins]=exactStatistics?(float)(sigma*sigma):sigma*sigma; ww[bins]=1; bins++;
                }
            }
            double[] fit=bins<2?new double[]{0,.0001}:regression(xs,ys,ww,bins);
            noise[c][0]=(float)Math.max(fit[0],0);
            noise[c][1]=(float)Math.max(fit[1],1e-7);
        }
    }

    private float initialSample(int x,int y) {
        return balanced(x,y)*clamp(coarse.interpolate(1,x,y)
                /Math.max(coarse.interpolate(colour(x,y),x,y),.01f),.25f,4f);
    }

    private static int select(double[] signal,double[] values,int n,double low,double high,
                              double[] dst,boolean absolute) {
        int count=0;
        for(int i=0;i<n;i++) if(signal[i]>=low&&signal[i]<=high)
            dst[count++]=absolute?Math.abs(values[i]):values[i];
        return count;
    }
    private static double median(double[] values,int n) {
        Arrays.sort(values,0,n);
        return n%2==1?values[n/2]:(values[n/2-1]+values[n/2])*.5;
    }
    private static double[] cuts(double[] values,int n,int bins) {
        double[] sorted=Arrays.copyOf(values,n),result=new double[bins+1];
        Arrays.sort(sorted); int count=0;
        for(int i=0;i<=bins;i++) {
            double pos=(n-1)*(i/(double)bins); int j=(int)pos;
            double v=sorted[j]+(sorted[Math.min(j+1,n-1)]-sorted[j])*(pos-j);
            if(count==0||v!=result[count-1]) result[count++]=v;
        }
        return Arrays.copyOf(result,count);
    }
    private static double[] regression(double[] xs,double[] ys,double[] ws,int n) {
        double sw=0,sx=0,sy=0;
        for(int i=0;i<n;i++) { sw+=ws[i]; sx+=ws[i]*xs[i]; sy+=ws[i]*ys[i]; }
        double mx=sx/sw,my=sy/sw,num=0,den=0;
        for(int i=0;i<n;i++) { num+=ws[i]*(xs[i]-mx)*(ys[i]-my); den+=ws[i]*(xs[i]-mx)*(xs[i]-mx); }
        double a=den>1e-20?num/den:0;
        return new double[]{a,my-a*mx};
    }

    private void buildCoarse(int from,int to) {
        int first=Math.max(0,from/4-8),last=Math.min(height/4,(to+3)/4+8);
        int n=(last-first)*(width/4);
        if(coarse==null||coarse.m.length<n) coarse=new Coarse(n);
        coarse.first=first; coarse.h=last-first;
        coarse.compute();
    }

    /** Menon 2007 coarse GBRG, omitting the optional refining step. */
    private final class Coarse {
        final int w=width/4;
        int first,h;
        final float[] m,gh,gv,dh,dv,r,g,b;
        Coarse(int n) {
            m=new float[n]; gh=new float[n]; gv=new float[n]; dh=new float[n];
            dv=new float[n]; r=new float[n]; g=new float[n]; b=new float[n];
        }
        long bytes() { return m.length*32L; }
        float at(float[] a,int x,int y) { return a[mirror(y,h)*w+mirror(x,w)]; }
        float constant(float[] a,int x,int y) { return x<0||x>=w||y<0||y>=h?0:a[y*w+x]; }
        void compute() {
            int n=w*h,chunks=threads*4;
            parallelChunks(chunks,k -> {
                for(int i=n*k/chunks;i<n*(k+1)/chunks;i++) {
                    int x=i%w,y=i/w; float sum=0;
                    for(int dy=0;dy<4;dy++) {
                        int gy=(y+first)*4+dy,base=(gy-start)*width+x*4;
                        if(exactStatistics) {
                            float row=0;
                            for(int j=0;j<4;j++) row+=gy>=start&&gy<start+rows?raw[base+j]:balanced(x*4+j,gy);
                            sum+=row;
                        } else if(gy>=start&&gy<start+rows) {
                            sum+=raw[base]; sum+=raw[base+1]; sum+=raw[base+2]; sum+=raw[base+3];
                        } else {
                            int at=gy*stride+x*5,top=buffer.getInt(at),low=buffer.get(at+4)&255;
                            int p=(gy&7)*8192+(x&1)*4096;
                            sum+=balancedValues[p+((top>>>24)<<2)+(low&3)];
                            sum+=balancedValues[p+1024+(((top>>>16)&255)<<2)+((low>>2)&3)];
                            sum+=balancedValues[p+2048+(((top>>>8)&255)<<2)+((low>>4)&3)];
                            sum+=balancedValues[p+3072+((top&255)<<2)+(low>>6)];
                        }
                    }
                    m[i]=Math.max(sum/16,0);
                }
            });
            parallelChunks(chunks,k -> {
                for(int i=n*k/chunks;i<n*(k+1)/chunks;i++) {
                    int x=i%w,y=i/w,c=bayerColour(x,y+first);
                    gh[i]=c==1?m[i]:.5f*(at(m,x-1,y)+at(m,x+1,y))+.5f*m[i]
                            -.25f*(at(m,x-2,y)+at(m,x+2,y));
                    gv[i]=c==1?m[i]:.5f*(at(m,x,y-1)+at(m,x,y+1))+.5f*m[i]
                            -.25f*(at(m,x,y-2)+at(m,x,y+2));
                }
            });
            parallelChunks(chunks,k -> {
                for(int i=n*k/chunks;i<n*(k+1)/chunks;i++) {
                    int x=i%w,y=i/w;
                    dh[i]=Math.abs((m[i]-gh[i])-(at(m,x+2,y)-at(gh,x+2,y)));
                    dv[i]=Math.abs((m[i]-gv[i])-(at(m,x,y+2)-at(gv,x,y+2)));
                }
            });
            parallelChunks(chunks,k -> {
                for(int i=n*k/chunks;i<n*(k+1)/chunks;i++) {
                    int x=i%w,y=i/w,c=bayerColour(x,y+first);
                    float horizontal=energy(dh,x,y,false),vertical=energy(dv,x,y,true);
                    // Reuse gh as a 0/1 direction mask only after all gradients exist.
                    g[i]=vertical>=horizontal?gh[i]:gv[i];
                    r[i]=c==0?m[i]:0; b[i]=c==2?m[i]:0;
                    gv[i]=vertical>=horizontal?1:0;
                }
            });
            parallelChunks(chunks,k -> {
                for(int i=n*k/chunks;i<n*(k+1)/chunks;i++) {
                    int x=i%w,y=i/w;
                    if(bayerColour(x,y+first)!=1) continue;
                    boolean redRow=((y+first)&1)==1;
                    r[i]=g[i]+difference(m,g,x,y,redRow);
                    b[i]=g[i]+difference(m,g,x,y,!redRow);
                }
            });
            parallelChunks(chunks,k -> {
                for(int i=n*k/chunks;i<n*(k+1)/chunks;i++) {
                    int x=i%w,y=i/w,c=bayerColour(x,y+first);
                    if(c==2) r[i]=b[i]+difference(r,b,x,y,gv[i]==1);
                    if(c==0) b[i]=r[i]+difference(b,r,x,y,gv[i]==1);
                }
            });
        }
        float difference(float[] a,float[] other,int x,int y,boolean horizontal) {
            int dx=horizontal?1:0,dy=horizontal?0:1;
            return .5f*(at(a,x-dx,y-dy)+at(a,x+dx,y+dy))
                    -.5f*(at(other,x-dx,y-dy)+at(other,x+dx,y+dy));
        }
        float energy(float[] a,int x,int y,boolean transpose) {
            // Nonzero entries of scipy convolve(D, k, mode='constant').
            if(transpose) {
                return constant(a,x+2,y)+constant(a,x+2,y-2)+constant(a,x+1,y-1)
                        +3*constant(a,x,y)+3*constant(a,x,y-2)+constant(a,x-1,y-1)
                        +constant(a,x-2,y)+constant(a,x-2,y-2);
            }
            return constant(a,x,y+2)+constant(a,x-2,y+2)+constant(a,x-1,y+1)
                    +3*constant(a,x,y)+3*constant(a,x-2,y)+constant(a,x-1,y-1)
                    +constant(a,x,y-2)+constant(a,x-2,y-2);
        }
        float interpolate(int c,int x,int y) {
            int x0=(x-2)>>2,y0=(y-2)>>2;
            float ax=((x+2)&3)*.25f+.125f,ay=((y+2)&3)*.25f+.125f;
            int x1=Math.max(0,Math.min(w-1,x0+1)),y1=Math.max(0,Math.min(height/4-1,y0+1))-first;
            x0=Math.max(0,Math.min(w-1,x0)); y0=Math.max(0,Math.min(height/4-1,y0))-first;
            float[] a=c==0?r:c==1?g:b;
            // Match map_coordinates' double-precision interpolation accumulation.
            double top=a[y0*w+x0]*(1.0-ax)+(exactStatistics?a[y0*w+x1]*(double)ax:a[y0*w+x1]*ax);
            double bottom=a[y1*w+x0]*(1.0-ax)+(exactStatistics?a[y1*w+x1]*(double)ax:a[y1*w+x1]*ax);
            return (float)(top*(1.0-ay)+bottom*ay);
        }
    }
}
