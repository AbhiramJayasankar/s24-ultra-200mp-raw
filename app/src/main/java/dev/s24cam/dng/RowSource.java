package dev.s24cam.dng;

/** A raw image the DNG writers pull one row (or one Bayer sample) at a time. */
public interface RowSource {
    /** Samples per pixel; Bayer sources have one, LinearRaw RGB sources three. */
    default int channels() { return 1; }

    int width();

    int height();

    /** One raw sample; used sparsely for the thumbnail. */
    int sample(int x, int y);

    /** Bulk RGB path. Sources can override to avoid widening/narrowing every sample. */
    default void row(int y,short[] out) {
        int[] values=new int[width()*channels()];row(y,values);
        for(int i=0;i<values.length;i++)out[i]=(short)values[i];
    }

    /** Fills {@code out[0..width*channels)} with row {@code y}. */
    void row(int y, int[] out);
}
