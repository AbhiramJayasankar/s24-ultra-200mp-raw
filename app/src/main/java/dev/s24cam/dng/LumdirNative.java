package dev.s24cam.dng;

import java.nio.ByteBuffer;

/** Optional kernels sharing Java's arithmetic and window geometry. Reconstruction
 * owns bounded native band arenas and borrows direct RAW/ring buffers; heap RAW
 * input is copied one band+halo at a time. Phase copies are <=46/33 KB.
 * There are no GC-critical pins or frame-sized native allocations. */
final class LumdirNative {
    private static boolean loaded;
    static {try {System.loadLibrary("s24lumdir");loaded=true;}catch(LinkageError|SecurityException e){loaded=false;}}
    static boolean available(){return loaded;}
    /** Disable the reconstruction/chroma kernels, retaining native medians. */
    static boolean kernelsAvailable(){return loaded&&!Boolean.getBoolean("s24cam.lumdir.java");}
    static native long directionalCreate(ByteBuffer input,int stride,int width,int height,int bandRows,int threads,
                                         float[] balanced,float[] neutral,float[] noise,float[] exp,float[] gauss,double[] chroma);
    static native void directionalInput(long context,byte[] input,int first,int rows);
    static native void directionalBegin(long context,int first,int last);
    static native void directionalStage(long context,int stage,int task,int tasks);
    static native void directionalStripe(long context,int stage,int task);
    static native void directionalCopyRgb(long context,float[] r,float[] g,float[] b);
    static native void chromaBegin(long context,int first,int last,int ringRows,ByteBuffer r,ByteBuffer g,ByteBuffer b,ByteBuffer y);
    static native void chromaRows(long context,int worker,int first,int last);
    static native long directionalBytes(long context);
    static native void directionalDestroy(long context);
    static void median(short[] input,int pitch,int columns,int rows,short[] output) {
        if(pitch!=columns+11||columns<1||columns>256||rows<1||rows>32
                ||input.length<(rows+11)*pitch||output.length<rows*columns)throw new IllegalArgumentException("Invalid phase stripe");
        medianNative(input,pitch,columns,rows,output);
    }
    private static native void medianNative(short[] input,int pitch,int columns,int rows,short[] output);
    static long medianFloats(float[] input,int pitch,int columns,int rows,float[] output) {
        if(pitch!=columns+11||columns<1||columns>256||rows<1||rows>32
                ||input.length<(rows+11)*pitch||output.length<rows*columns)throw new IllegalArgumentException("Invalid phase stripe");
        return medianFloatsNative(input,pitch,columns,rows,output);
    }
    private static native long medianFloatsNative(float[] input,int pitch,int columns,int rows,float[] output);
}
