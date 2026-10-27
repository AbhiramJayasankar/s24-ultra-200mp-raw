package dev.s24cam.dng;

import java.nio.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Bounded streaming lumdir. Four caller-owned R/G/B uint16-ratio and float-Y
 * rings replace the frame maps. Shared-memory access is bulk row transfer only;
 * Native Directional/chroma keep persistent band arenas; Java fallback and the
 * uniform, median preparation and output loops operate on primitive arrays.
 * Directional work/RAW halos are 32/64; colour adds eight aligned rows for the
 * radius-four Gaussian. Uniform uses [-4,+3], phase median [-6,+5] eight-pixel
 * cells, hence luminance dependencies [-52,+43] around an output band.
 * One native colour band can overlap Java phase work. Y is snapshotted and
 * current ratio rows are reserved against producer ring-slot collisions.
 * Borrowed buffers must remain unchanged until close; their positions are kept.
 */
public final class Lumdir16 implements RowSource, AutoCloseable {
    static final int COLOUR_ROWS=192, OUTPUT_ROWS=128, RING_ROWS=576, PHASE_COLUMNS=256;
    private static final float RATIO_SCALE=65535f/4, RATIO_UNIT=4f/65535;
    private static final double[] G=new double[5];
    static double[] chromaKernel(){return G.clone();}
    static {double sum=0;for(int k=-4;k<=4;k++)sum+=Math.exp(-.5*k*k);
        for(int k=0;k<=4;k++)G[k]=Math.exp(-.5*(4-k)*(4-k))/sum;}
    private final int width,height,stride,bandRows,ringRows,threads;
    private final ByteBuffer raw;
    private final ByteBuffer[] ringStores=new ByteBuffer[4];
    private final boolean nativeChroma;
    private final ShortBuffer[] ratios=new ShortBuffer[3];
    private final FloatBuffer luminance;
    private final float[] neutral,balanced,codeScale;
    private final Directional16 dir;
    private final ExecutorService pool;
    private final ExecutorService colourPool;
    private final boolean pipeline;
    private Future<?> colourJob;
    private long colourWork,colourWait,colourJobs;
    private final Scratch[] workers;
    private final IntConsumer progress;
    private int reportedProgress,preparedProgress;
    private final int[] stamps;
    private final float[][] rgb;
    private final float[] mean,yValues,vertical,relative,smooth;
    private final short[][] codeRatios;
    private final short[] output;
    private final NoiseCurve curve;
    private final long[] timings=new long[5];
    private long medianCount;
    private volatile long peakWorkingBytes;
    private int cachedFirst=-1,cachedLast,relativeFirst,luminanceFirst;
    private boolean closed;

    /** Maximum per-buffer size, retained for callers allocating equal-sized stores. */
    public static int storeBytes(int w,int h) {return storeBytes(w,h,3);}
    public static int storeBytes(int w,int h,int plane) {
        if(w<16||h<16||w%8!=0||h%8!=0||plane<0||plane>3)throw new IllegalArgumentException("Invalid lumdir geometry/plane");
        return Math.toIntExact((long)w*Math.min(h,RING_ROWS)*(plane==3?4:2));
    }
    public Lumdir16(ByteBuffer raw,int stride,int w,int h,int black,int white,float[] neutral,ByteBuffer[] stores) {
        this(raw,stride,w,h,black,white,neutral,stores,OUTPUT_ROWS,null);
    }
    public Lumdir16(ByteBuffer raw,int stride,int w,int h,int black,int white,float[] neutral,ByteBuffer[] stores,IntConsumer progress) {
        this(raw,stride,w,h,black,white,neutral,stores,OUTPUT_ROWS,progress);
    }
    Lumdir16(ByteBuffer raw,int stride,int w,int h,int black,int white,float[] neutral,ByteBuffer[] stores,int bandRows) {
        this(raw,stride,w,h,black,white,neutral,stores,bandRows,null);
    }
    Lumdir16(ByteBuffer raw,int stride,int w,int h,int black,int white,float[] neutral,ByteBuffer[] stores,int bandRows,IntConsumer progress) {
        if(bandRows<8||bandRows%8!=0||bandRows>256||stores==null||stores.length!=4)
            throw new IllegalArgumentException("Four stores and an 8..256 row aligned band required");
        for(int c=0;c<4;c++)if(stores[c]==null||stores[c].isReadOnly()||stores[c].remaining()<storeBytes(w,h,c))
            throw new IllegalArgumentException("Lumdir ring store too small/read-only");
        this.width=w;this.height=h;this.stride=stride;this.bandRows=bandRows;this.ringRows=Math.min(h,RING_ROWS);
        this.raw=raw.duplicate().slice();this.neutral=neutral.clone();this.progress=progress;
        for(int c=0;c<4;c++)ringStores[c]=stores[c].duplicate().slice().order(ByteOrder.nativeOrder());
        for(int c=0;c<3;c++)ratios[c]=ringStores[c].asShortBuffer();
        luminance=ringStores[3].asFloatBuffer();
        stamps=new int[ringRows];Arrays.fill(stamps,-1);
        threads=Math.max(1,Math.min(8,Math.min(Runtime.getRuntime().availableProcessors(),Integer.getInteger("s24cam.lumdir.computeThreads",8))));
        pool=Executors.newFixedThreadPool(threads,r->{Thread t=new Thread(r,"Lumdir16");t.setDaemon(true);return t;});
        long started=System.nanoTime();
        try {dir=new Directional16(raw,stride,w,h,black,white,neutral,COLOUR_ROWS+16,true);}
        catch(RuntimeException|Error e){pool.shutdownNow();throw e;}
        nativeChroma=dir.nativeKernels()&&Arrays.stream(ringStores).allMatch(ByteBuffer::isDirect);
        pipeline=nativeChroma&&!Boolean.getBoolean("s24cam.lumdir.serial");
        colourPool=pipeline?Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Lumdir colour producer");t.setDaemon(true);return t;}):null;
        timings[0]=System.nanoTime()-started;
        try {
            curve=NoiseCurve.from(dir.noiseParameters(),neutral);balanced=dir.balancedLookup();
            codeScale=new float[3];for(int c=0;c<3;c++)codeScale[c]=RATIO_UNIT*neutral[c];
            int colourN=w*Math.min(h,COLOUR_ROWS+16),coreN=w*Math.min(h,bandRows);
            // Colour and phase stages are ordered: reuse the large primitive arenas.
            int yN=w*Math.min(h,bandRows+96),relN=w*Math.min(h,bandRows+88);
            rgb=nativeChroma?new float[][]{new float[relN],new float[0],new float[yN]}
                    :new float[][]{new float[Math.max(colourN,relN)],new float[colourN],new float[Math.max(colourN,yN)]};
            mean=new float[nativeChroma?coreN:Math.max(colourN,coreN)];
            yValues=rgb[2];vertical=rgb[0];relative=new float[relN];smooth=mean;
            output=new short[coreN*3];codeRatios=new short[3][coreN];
            workers=new Scratch[threads];for(int t=0;t<threads;t++)workers[t]=new Scratch();
            peakWorkingBytes=workingBytes();
        }catch(RuntimeException|Error e){if(colourPool!=null)colourPool.shutdownNow();dir.close();pool.shutdownNow();throw e;}
    }
    public NoiseCurve noiseCurve(){return curve;}
    public int[] linearizationTable(){return curve.linearizationTable();}
    @Override public int width(){return width;}
    @Override public int height(){return height;}
    @Override public int channels(){return 3;}
    public static int colour(int x,int y){return 1+((x>>2)&1)-((y>>2)&1);}
    private static int reflect(int x,int n){if(x>=0&&x<n)return x;int p=Math.floorMod(x,2*n);return p<n?p:2*n-1-p;}
    private void colourRange(int first,int last) {
        for(int y=first/COLOUR_ROWS*COLOUR_ROWS;y<last;y+=COLOUR_ROWS) {
            int end=Math.min(height,y+COLOUR_ROWS);boolean present=true;
            for(int row=y;row<end;row++)if(stamps[row%ringRows]!=row){present=false;break;}
            if(!present)chroma(y,end);
        }
        if(progress!=null&&preparedProgress>reportedProgress){reportedProgress=preparedProgress;progress.accept(reportedProgress);}
    }
    /** Only one future colour band. Current Y is snapshotted; ratio rows remain
     * reserved until codes load them. A guard prevents ring-slot collisions.
     * Native workspaces stay on the producer and never overlap another colour job. */
    private void prefetchColour(int current,int last) {
        if(!pipeline||last==height)return;
        int from=Math.max(0,last-52),to=Math.min(height,Math.min(height,last+bandRows)+43);
        for(int first=from/COLOUR_ROWS*COLOUR_ROWS;first<to;first+=COLOUR_ROWS) {
            int end=Math.min(height,first+COLOUR_ROWS);boolean present=true;
            for(int y=first;y<end;y++)if(stamps[y%ringRows]!=y){present=false;break;}
            if(!present) {
                for(int y=first;y<end;y++)if(Math.floorMod(y-current,ringRows)<last-current)return;
                final int a=first,b=end;colourJobs++;
                colourJob=colourPool.submit(()->{long t=System.nanoTime();try{chroma(a,b);}finally{colourWork+=System.nanoTime()-t;}});
                return;
            }
        }
    }
    private void awaitColour() {
        Future<?> job=colourJob;if(job==null)return;colourJob=null;
        long t=System.nanoTime();boolean interrupted=false;Throwable failure=null;
        for(;;)try{job.get();break;}catch(InterruptedException e){interrupted=true;failure=e;}
        catch(ExecutionException e){failure=e.getCause();break;}
        colourWait+=System.nanoTime()-t;
        if(interrupted)Thread.currentThread().interrupt();
        if(failure!=null)throw new IllegalStateException("Colour producer failed",failure);
    }
    private void chroma(int first,int last) {
        int lo=Math.max(0,first-8),hi=Math.min(height,last+8);
        if(nativeChroma) {
            long t=System.nanoTime();dir.nativeRgbBand(lo,hi);timings[0]+=System.nanoTime()-t;
            t=System.nanoTime();dir.nativeChromaBand(first,last,ringRows,ringStores);
            timings[1]+=System.nanoTime()-t;
            colourDone(first,last);return;
        }
        long t=System.nanoTime();dir.rgbBand(lo,hi,rgb);timings[0]+=System.nanoTime()-t;
        t=System.nanoTime();
        parallel((hi-lo+3)/4,(worker,part)->{
            int a=part*4*width,b=Math.min(hi-lo,part*4+4)*width;
            for(int i=a;i<b;i++)mean[i]=Math.max((rgb[0][i]+rgb[1][i]+rgb[2][i])/3f,1e-4f);
        });
        parallel(last-first,(worker,row)->{
            Scratch s=workers[worker];int gy=first+row;
            int a=(reflect(gy-4,height)-lo)*width,b=(reflect(gy-3,height)-lo)*width,c=(reflect(gy-2,height)-lo)*width;
            int d=(reflect(gy-1,height)-lo)*width,e=(gy-lo)*width,f=(reflect(gy+1,height)-lo)*width;
            int g=(reflect(gy+2,height)-lo)*width,h=(reflect(gy+3,height)-lo)*width,j=(reflect(gy+4,height)-lo)*width;
            for(int ch=0;ch<4;ch++) {
                float[] src=ch==3?mean:rgb[ch],dst=s.blurred[ch];
                for(int x=0;x<width;x++)dst[x]=(float)(G[4]*src[e+x]+G[3]*(src[d+x]+(double)src[f+x])
                        +G[2]*(src[c+x]+(double)src[g+x])+G[1]*(src[b+x]+(double)src[h+x])+G[0]*(src[a+x]+(double)src[j+x]));
            }
            for(int ch=0;ch<4;ch++) {
                float[] src=s.blurred[ch],dst=s.filtered[ch];
                for(int x=0;x<4;x++)dst[x]=horizontal(src,x);
                for(int x=4;x<width-4;x++)dst[x]=(float)(G[4]*src[x]+G[3]*(src[x-1]+(double)src[x+1])
                        +G[2]*(src[x-2]+(double)src[x+2])+G[1]*(src[x-3]+(double)src[x+3])+G[0]*(src[x-4]+(double)src[x+4]));
                for(int x=width-4;x<width;x++)dst[x]=horizontal(src,x);
            }
            s.raw.position(gy*stride);s.raw.get(s.rawBytes,0,width*5/4);
            int phase=(gy&7)*8192;
            for(int x=0,at=0;x<width;x+=4,at+=5) {
                int low=s.rawBytes[at+4]&255;
                for(int k=0;k<4;k++) {
                    int px=x+k;float den=Math.max(s.filtered[3][px],1e-4f);
                    for(int ch=0;ch<3;ch++) {
                        int q=(int)(s.filtered[ch][px]/den*RATIO_SCALE+.5f);
                        s.ratioRows[ch][px]=(short)Math.max(819,Math.min(65535,q));
                    }
                    int v=((s.rawBytes[at+k]&255)<<2)|((low>>(k*2))&3);
                    float own=(s.ratioRows[colour(px,gy)][px]&65535)*RATIO_UNIT;
                    s.yRow[px]=balanced[phase+(px&7)*1024+v]/own;
                }
            }
            int at=(gy%ringRows)*width;
            for(int ch=0;ch<3;ch++){s.ratios[ch].position(at);s.ratios[ch].put(s.ratioRows[ch],0,width);}
            s.y.position(at);s.y.put(s.yRow,0,width);
        });
        timings[1]+=System.nanoTime()-t;
        colourDone(first,last);
    }
    private void colourDone(int first,int last) {
        for(int y=first;y<last;y++)stamps[y%ringRows]=y;
        preparedProgress=Math.max(preparedProgress,last*100/height);
        peakWorkingBytes=Math.max(peakWorkingBytes,workingBytes());
    }
    private float horizontal(float[] row,int x) {
        return (float)(G[4]*row[x]+G[3]*(row[reflect(x-1,width)]+(double)row[reflect(x+1,width)])
                +G[2]*(row[reflect(x-2,width)]+(double)row[reflect(x+2,width)])
                +G[1]*(row[reflect(x-3,width)]+(double)row[reflect(x+3,width)])
                +G[0]*(row[reflect(x-4,width)]+(double)row[reflect(x+4,width)]));
    }
    private void relativeBand(int first,int last) {
        relativeFirst=Math.max(0,first-48);int end=Math.min(height,last+40);
        luminanceFirst=Math.max(0,relativeFirst-4);int yEnd=Math.min(height,end+3);
        awaitColour();colourRange(luminanceFirst,yEnd);
        long t=System.nanoTime();
        parallel(yEnd-luminanceFirst,(worker,r)->{
            FloatBuffer view=workers[worker].y;view.position(((luminanceFirst+r)%ringRows)*width);
            view.get(yValues,r*width,width);
        });
        prefetchColour(first,last);
        parallel((width+255)/256,(worker,p)->{
            int from=p*256,to=Math.min(width,from+256);
            for(int x=from;x<to;x++) {
                double sum=0;for(int k=-4;k<=3;k++)sum+=yValues[(reflect(relativeFirst+k,height)-luminanceFirst)*width+x];
                for(int y=relativeFirst;y<end;y++) {
                    vertical[(y-relativeFirst)*width+x]=(float)(sum/8);
                    if(y+1<end)sum+=yValues[(reflect(y+4,height)-luminanceFirst)*width+x]
                            -(double)yValues[(reflect(y-4,height)-luminanceFirst)*width+x];
                }
            }
        });
        parallel(end-relativeFirst,(worker,r)->{
            int at=r*width,src=(relativeFirst+r-luminanceFirst)*width;double sum=0;
            for(int k=-4;k<=3;k++)sum+=vertical[at+reflect(k,width)];
            for(int x=0;x<width;x++) {
                relative[at+x]=yValues[src+x]/Math.max((float)(sum/8),1e-3f);
                if(x+1<width)sum+=vertical[at+reflect(x+4,width)]-(double)vertical[at+reflect(x-4,width)];
            }
        });
        timings[2]+=System.nanoTime()-t;
    }
    static int quantize(float v){float q=v*4096f;if(q<=-4096)return 0;if(q>=12287)return Histogram.SIZE-1;return (int)Math.floor(q+.5f)+4096;}
    static float unquantize(int v){return (v-4096)*(1f/4096);}
    private void medianBand(int first,int last) {
        int cw=width/8,ch=height/8,cellFirst=first/8,cellRows=(last-first)/8,stripes=(cw+PHASE_COLUMNS-1)/PHASE_COLUMNS;
        parallel(64*stripes,(worker,task)->{
            Scratch s=workers[worker];int phase=task%64,py=phase%8,px=phase/8,from=(task/64)*PHASE_COLUMNS;
            int cols=Math.min(PHASE_COLUMNS,cw-from),pitch=cols+11;
            for(int x=0;x<pitch;x++)s.phaseX[x]=reflect(from+x-6,cw)*8+px;
            if(LumdirNative.available()) {
                for(int y=0;y<cellRows+11;y++) {
                    int gy=reflect(cellFirst+y-6,ch)*8+py,at=(gy-relativeFirst)*width;
                    for(int x=0;x<pitch;x++)s.phaseValues[y*pitch+x]=relative[at+s.phaseX[x]];
                }
                s.fallbacks+=LumdirNative.medianFloats(s.phaseValues,pitch,cols,cellRows,s.phaseMedians);
                for(int y=0;y<cellRows;y++)for(int x=0;x<cols;x++)
                    smooth[(y*8+py)*width+(from+x)*8+px]=s.phaseMedians[y*cols+x];
                return;
            }
            short[] values=s.phase;Histogram hist=s.hist;
            for(int y=0;y<cellRows+11;y++) {
                int gy=reflect(cellFirst+y-6,ch)*8+py,at=(gy-relativeFirst)*width;
                for(int x=0;x<pitch;x++)values[y*pitch+x]=(short)quantize(relative[at+s.phaseX[x]]);
            }
            hist.clear();for(int y=0;y<12;y++)for(int x=0;x<12;x++)hist.add(values[y*pitch+x],1);
            int x=0;long fallback=0;
            for(int y=0;y<cellRows;y++) {
                int direction=(y&1)==0?1:-1;
                for(int step=0;step<cols;step++) {
                    int q=hist.median();float v;
                    if(q==0||q==Histogram.SIZE-1) {
                        int n=0;for(int a=0;a<12;a++) {
                            int gy=reflect(cellFirst+y+a-6,ch)*8+py,at=(gy-relativeFirst)*width;
                            for(int b=0;b<12;b++)s.exact[n++]=relative[at+reflect(from+x+b-6,cw)*8+px];
                        }
                        v=exactMedian(s.exact);fallback++;
                    }else v=unquantize(q);
                    smooth[(y*8+py)*width+(from+x)*8+px]=v;
                    if(step+1<cols) {
                        int old=x+(direction==1?0:11),add=x+(direction==1?12:-1);
                        for(int k=0;k<12;k++)hist.replace(values[(y+k)*pitch+old],values[(y+k)*pitch+add]);
                        x+=direction;
                    }
                }
                if(y+1<cellRows)for(int k=0;k<12;k++)hist.replace(values[y*pitch+x+k],values[(y+12)*pitch+x+k]);
            }
            s.fallbacks+=fallback;
        });
        medianCount+=(long)(last-first)*width;
    }
    /** Upper median; only used when the compact histogram median is a tail bin. */
    static float exactMedian(float[] a) {
        int left=0,right=143;
        while(left<right) {
            float pivot=a[(left+right)>>>1];int low=left,scan=left,high=right;
            while(scan<=high) {
                if(a[scan]<pivot){float t=a[low];a[low++]=a[scan];a[scan++]=t;}
                else if(a[scan]>pivot){float t=a[high];a[high--]=a[scan];a[scan]=t;}
                else scan++;
            }
            if(72<low)right=low-1;else if(72>high)left=high+1;else return a[72];
        }
        return a[72];
    }
    private synchronized void band(int y) {
        if(closed)throw new IllegalStateException("Lumdir16 is closed");
        if(y>=cachedFirst&&y<cachedLast)return;
        int first=y/bandRows*bandRows,last=Math.min(height,first+bandRows);
        relativeBand(first,last);
        long t=System.nanoTime();medianBand(first,last);timings[3]+=System.nanoTime()-t;
        t=System.nanoTime();
        parallel(last-first,(worker,r)->{
            Scratch s=workers[worker];int at=((first+r)%ringRows)*width;
            for(int c=0;c<3;c++){s.ratios[c].position(at);s.ratios[c].get(codeRatios[c],r*width,width);}
        });
        int cells=(width/8+63)/64;
        parallel((last-first)/8*cells,(worker,part)->{
            int cy=part/cells,from=(part%cells)*512,to=Math.min(width,from+512);
            for(int cx=from;cx<to;cx+=8) {
                float avg=0;for(int a=0;a<8;a++)for(int b=0;b<8;b++)avg+=smooth[(cy*8+a)*width+cx+b];avg/=64;
                for(int a=0;a<8;a++)for(int b=0;b<8;b++) {
                    int x=cx+b,i=(cy*8+a)*width+x,yi=(first+cy*8+a-luminanceFirst)*width+x;
                    float flat=yValues[yi]/(smooth[i]/avg);
                    output[i*3]=(short)curve.encodeFast(flat*(codeRatios[0][i]&65535)*codeScale[0]);
                    output[i*3+1]=(short)curve.encodeFast(flat*(codeRatios[1][i]&65535)*codeScale[1]);
                    output[i*3+2]=(short)curve.encodeFast(flat*(codeRatios[2][i]&65535)*codeScale[2]);
                }
            }
        });
        timings[4]+=System.nanoTime()-t;cachedFirst=first;cachedLast=last;
    }
    @Override public synchronized void row(int y,int[] out) {
        if(y<0||y>=height||out.length<width*3)throw new IllegalArgumentException("Invalid RGB row");
        band(y);int at=(y-cachedFirst)*width*3;for(int i=0;i<width*3;i++)out[i]=output[at+i]&65535;
    }
    @Override public synchronized void row(int y,short[] out) {
        if(y<0||y>=height||out.length<width*3)throw new IllegalArgumentException("Invalid RGB row");
        band(y);System.arraycopy(output,(y-cachedFirst)*width*3,out,0,width*3);
    }
    public synchronized void linearRow(int y,float[] out) {
        if(y<0||y>=height||out.length<width*3)throw new IllegalArgumentException("Invalid RGB row");
        band(y);int cy=(y-cachedFirst)/8;
        for(int cx=0;cx<width;cx+=8) {
            float avg=0;for(int a=0;a<8;a++)for(int b=0;b<8;b++)avg+=smooth[(cy*8+a)*width+cx+b];avg/=64;
            for(int b=0;b<8;b++) {
                int x=cx+b,i=(y-cachedFirst)*width+x;float flat=yValues[(y-luminanceFirst)*width+x]/(smooth[i]/avg);
                for(int c=0;c<3;c++)out[x*3+c]=Math.max(flat*(codeRatios[c][i]&65535)*codeScale[c],0);
            }
        }
    }
    @Override public synchronized int sample(int x,int y){if(x<0||x>=width||y<0||y>=height)throw new IllegalArgumentException("Invalid sample");band(y);return output[((y-cachedFirst)*width+x)*3+1]&65535;}
    /** Stage elapsed work; producer stages overlap relative/median/codes and are not additive wall time. */
    public synchronized double[] stageSeconds(){awaitColour();double[] s=new double[5];for(int i=0;i<5;i++)s[i]=timings[i]/1e9;return s;}
    public boolean pipelined(){return pipeline;}
    public int computeThreads(){return threads;}
    public int directionalThreads(){return dir.workerThreads();}
    public boolean nativeDag(){return dir.nativeDag();}
    public synchronized double[] pipelineStats(){awaitColour();return new double[]{colourWork/1e9,colourWait/1e9,colourJobs};}
    public double[] directionalSeconds(){return dir.stageSeconds();}
    public boolean nativeMedians(){return LumdirNative.available();}
    public boolean nativeDirectional(){return dir.nativeKernels();}
    public boolean nativeChroma(){return nativeChroma;}
    public long nativeWorkingBytes(){return dir.nativeBytes();}
    public synchronized long medianCount(){return medianCount;}
    public synchronized long medianFallbacks(){long n=0;for(Scratch s:workers)n+=s.fallbacks;return n;}
    public long peakWorkingBytes(){return peakWorkingBytes;}
    public long storeBytes(){long n=0;for(int c=0;c<4;c++)n+=storeBytes(width,height,c);return n;}
    private long workingBytes() {
        long n=dir.peakArrayBytes()+4L*(rgb[0].length+(long)rgb[1].length+rgb[2].length+mean.length+relative.length)
                +2L*(output.length+3L*codeRatios[0].length)+balanced.length*4L;
        for(Scratch s:workers)if(s!=null)n+=s.bytes();return n;
    }
    private interface Job{void run(int worker,int part);}
    private void parallel(int count,Job task) {
        AtomicInteger next=new AtomicInteger();Future<?>[] jobs=new Future<?>[threads];
        for(int t=0;t<threads;t++){final int worker=t;jobs[t]=pool.submit(()->{for(int p;(p=next.getAndIncrement())<count;)task.run(worker,p);});}
        Throwable failure=null;boolean interrupted=false;
        for(Future<?> f:jobs)for(;;){try{f.get();break;}catch(InterruptedException e){interrupted=true;if(failure==null)failure=e;}
            catch(ExecutionException e){if(failure==null)failure=e.getCause();break;}}
        if(interrupted)Thread.currentThread().interrupt();if(failure!=null)throw new IllegalStateException(failure);
    }
    @Override public synchronized void close(){if(closed)return;closed=true;
        try{awaitColour();}finally{if(colourPool!=null)colourPool.shutdown();dir.close();pool.shutdown();}}
    private final class Scratch {
        final ByteBuffer raw=Lumdir16.this.raw.duplicate();
        final ShortBuffer[] ratios=new ShortBuffer[3];final FloatBuffer y=luminance.duplicate();
        final float[][] blurred=new float[4][nativeChroma?0:width],filtered=new float[4][nativeChroma?0:width];
        final short[][] ratioRows=new short[3][nativeChroma?0:width];final float[] yRow=new float[nativeChroma?0:width],exact=new float[144];
        final byte[] rawBytes=new byte[nativeChroma?0:width*5/4];
        final int[] phaseX=new int[Math.min(width/8,PHASE_COLUMNS)+11];
        final short[] phase=new short[LumdirNative.available()?0:(Math.min(height,bandRows)/8+11)*(Math.min(width/8,PHASE_COLUMNS)+11)];
        final float[] phaseValues=new float[LumdirNative.available()?(Math.min(height,bandRows)/8+11)*(Math.min(width/8,PHASE_COLUMNS)+11):0];
        final float[] phaseMedians=new float[LumdirNative.available()?(Math.min(height,bandRows)/8)*Math.min(width/8,PHASE_COLUMNS):0];
        final Histogram hist=new Histogram();long fallbacks;
        Scratch(){for(int c=0;c<3;c++)ratios[c]=Lumdir16.this.ratios[c].duplicate();}
        long bytes(){return 4L*(4L*(blurred[0].length+filtered[0].length)+yRow.length+exact.length)
                +4L*(phaseValues.length+phaseMedians.length+phaseX.length)+2L*(3L*ratioRows[0].length+phase.length)+rawBytes.length+hist.bytes();}
    }
    /** 32 KiB counts plus 2 KiB occupancy; no TreeMap or per-pixel allocation. */
    static final class Histogram {
        static final int SIZE=1<<14;
        final short[] counts=new short[SIZE];final long[] occupied=new long[SIZE/64],groups=new long[SIZE/4096];
        int current,below;
        long bytes(){return 2L*SIZE+8L*(occupied.length+groups.length);}
        void clear(){Arrays.fill(counts,(short)0);Arrays.fill(occupied,0);Arrays.fill(groups,0);current=8192;below=0;}
        void add(int key,int change){if(key<current)below+=change;int n=counts[key]+=change,w=key>>>6;long bit=1L<<(key&63);
            if(n==0){occupied[w]&=~bit;if(occupied[w]==0)groups[w>>>6]&=~(1L<<(w&63));}
            else if(change>0&&n==1){long old=occupied[w];occupied[w]=old|bit;if(old==0)groups[w>>>6]|=1L<<(w&63);}}
        void replace(int old,int value){if(old==value)return;add(old,-1);add(value,1);}
        int next(int key,boolean up){
            int w=key>>>6,g=w>>>6;long bits;
            if(up){bits=(key&63)==63?0:occupied[w]&(-1L<<((key&63)+1));if(bits!=0)return w*64+Long.numberOfTrailingZeros(bits);
                long words=(w&63)==63?0:groups[g]&(-1L<<((w&63)+1));while(words==0&&++g<groups.length)words=groups[g];
                if(words!=0){w=g*64+Long.numberOfTrailingZeros(words);return w*64+Long.numberOfTrailingZeros(occupied[w]);}}
            else{bits=(key&63)==0?0:occupied[w]&(-1L>>>(64-(key&63)));if(bits!=0)return w*64+63-Long.numberOfLeadingZeros(bits);
                long words=(w&63)==0?0:groups[g]&(-1L>>>(64-(w&63)));while(words==0&&--g>=0)words=groups[g];
                if(words!=0){w=g*64+63-Long.numberOfLeadingZeros(words);return w*64+63-Long.numberOfLeadingZeros(occupied[w]);}}
            throw new IllegalStateException("Empty histogram");
        }
        int median(){while(below>72){current=next(current,false);below-=counts[current];}
            while(below+counts[current]<=72){below+=counts[current];current=next(current,true);}return current;}
    }
}
