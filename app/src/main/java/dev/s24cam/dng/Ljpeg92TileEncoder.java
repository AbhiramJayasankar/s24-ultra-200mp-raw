package dev.s24cam.dng;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** SOF3, predictor 1, interleaved components, with a tile-optimal DC Huffman table.
 * A zero-weight pseudo symbol reserves the all-ones code. Dynamic programming
 * minimizes weighted path length subject to JPEG's 16-bit code-length limit. */
public final class Ljpeg92TileEncoder implements TileEncoder {
    @Override public int compression() { return 7; }
    @Override public byte[] encode(short[] samples,int width,int height,int channels,int bits) {
        if(width<1||height<1||width>65535||height>65535||channels<1||channels>4||bits<2||bits>16
                ||samples.length!=Math.multiplyExact(Math.multiplyExact(width,height),channels))
            throw new IllegalArgumentException("Invalid lossless JPEG tile");
        int mask=(1<<bits)-1,mid=1<<(bits-1),stride=width*channels;
        long[] histogram=new long[17];
        for(int y=0;y<height;y++) for(int i=y*stride,end=(y+1)*stride;i<end;i++) {
            int pred=i>=y*stride+channels?samples[i-channels]&mask:y>0?samples[i-stride]&mask:mid;
            int d=difference((samples[i]&65535)-pred,bits);
            if((samples[i]&65535)>mask) throw new IllegalArgumentException("Sample exceeds precision");
            histogram[category(d)]++;
        }
        Huffman h=new Huffman(histogram);
        ByteArrayOutputStream out=new ByteArrayOutputStream(samples.length);
        word(out,0xffd8); word(out,0xffc4); word(out,19+h.symbols.length); out.write(0);
        for(int i=1;i<=16;i++) out.write(h.counts[i]);
        for(int symbol:h.symbols) out.write(symbol);
        word(out,0xffc3); word(out,8+3*channels); out.write(bits);
        word(out,height); word(out,width); out.write(channels);
        for(int c=1;c<=channels;c++) {out.write(c);out.write(0x11);out.write(0);}
        word(out,0xffda); word(out,6+2*channels); out.write(channels);
        for(int c=1;c<=channels;c++) {out.write(c);out.write(0);}
        out.write(1);out.write(0);out.write(0);
        Bits stream=new Bits(out);
        for(int y=0;y<height;y++) for(int i=y*stride,end=(y+1)*stride;i<end;i++) {
            int pred=i>=y*stride+channels?samples[i-channels]&mask:y>0?samples[i-stride]&mask:mid;
            int d=difference((samples[i]&65535)-pred,bits),cat=category(d);
            stream.put(h.codes[cat],h.lengths[cat]);
            if(cat!=0&&cat!=16) stream.put(d<0?d+(1<<cat)-1:d,cat);
        }
        stream.finish();word(out,0xffd9); return out.toByteArray();
    }
    private static int difference(int d,int bits) {int shift=32-bits;return (d<<shift)>>shift;}
    private static int category(int d) {return d==0?0:32-Integer.numberOfLeadingZeros(Math.abs(d));}
    private static void word(ByteArrayOutputStream out,int v) {out.write(v>>>8);out.write(v);}
    private static final class Bits {
        final ByteArrayOutputStream out;long value;int count;
        Bits(ByteArrayOutputStream out) {this.out=out;}
        void put(int v,int n) {
            value=(value<<n)|(v&((1L<<n)-1));count+=n;
            while(count>=8) {int b=(int)(value>>>(count-=8))&255;out.write(b);if(b==255)out.write(0);}
        }
        void finish() {if(count>0)put((1<<(8-count))-1,8-count);}
    }
    static final class Huffman {
        final int[] counts=new int[17],lengths=new int[17],codes=new int[17],symbols;
        final int[] ordered; final long[] prefix;
        final long[][][] memo;
        final int[][][] choices;
        final int n;
        Huffman(long[] histogram) {
            List<Integer> list=new ArrayList<>();
            for(int i=0;i<17;i++)if(histogram[i]>0)list.add(i);
            list.sort((a,b)->histogram[a]==histogram[b]?Integer.compare(a,b):Long.compare(histogram[b],histogram[a]));
            list.add(17); // pseudo symbol: zero cost, last in canonical order
            n=list.size();ordered=list.stream().mapToInt(Integer::intValue).toArray();
            prefix=new long[n+1];for(int i=0;i<n;i++)prefix[i+1]=prefix[i]+(ordered[i]==17?0:histogram[ordered[i]]);
            memo=new long[17][n+1][2*n+1]; choices=new int[17][n+1][2*n+1];
            for(long[][] a:memo)for(long[] b:a)Arrays.fill(b,-1);
            cost(1,0,2);
            int used=0,slots=2;
            for(int depth=1;used<n;depth++) {
                int leaves=choices[depth][used][slots];
                for(int j=0;j<leaves;j++) {int s=ordered[used++];if(s!=17){lengths[s]=depth;counts[depth]++;}}
                slots=2*(slots-leaves);
            }
            symbols=new int[n-1];int at=0,code=0;
            for(int depth=1;depth<=16;depth++) {
                // Frequency order within equal lengths keeps pseudo last.
                for(int s:ordered)if(s<17&&lengths[s]==depth) {
                    symbols[at++]=s;codes[s]=code++;
                    if(codes[s]==(1<<depth)-1)throw new IllegalStateException("All-ones JPEG code");
                }
                code<<=1;
            }
        }
        private long cost(int depth,int used,int slots) {
            final long inf=Long.MAX_VALUE/4;
            if(slots>n-used||depth>16)return inf;
            if(memo[depth][used][slots]>=0)return memo[depth][used][slots];
            long best=inf;int choice=0;
            for(int leaves=0;leaves<=slots;leaves++) {
                int rest=n-used-leaves,branches=slots-leaves;
                long tail;
                if(rest==0&&branches==0)tail=0;
                else if(depth==16||branches==0||2*branches>rest)continue;
                else tail=cost(depth+1,used+leaves,2*branches);
                long value=depth*(prefix[used+leaves]-prefix[used])+tail;
                if(value<best){best=value;choice=leaves;}
            }
            choices[depth][used][slots]=choice;return memo[depth][used][slots]=best;
        }
    }
}
