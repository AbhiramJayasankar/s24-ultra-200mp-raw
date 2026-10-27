package dev.s24cam.dng;
/** Compresses one tile of interleaved 16-bit samples for a DNG. Implementations must be thread-safe. */
public interface TileEncoder {
    /** TIFF Compression tag value (7 = lossless JPEG, 52546 = JPEG XL). */
    int compression();
    /** samples: width*height*channels values in [0, 2^bits), row-major, channels interleaved. */
    byte[] encode(short[] samples, int width, int height, int channels, int bits);
}
