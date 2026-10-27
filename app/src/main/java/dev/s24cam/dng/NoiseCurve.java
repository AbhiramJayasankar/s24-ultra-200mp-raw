package dev.s24cam.dng;

/** The noise-adaptive square-root curve in bench/linear_dng.py. 1.0 is sensor white. */
public final class NoiseCurve {
    public static final double STEP=.5;
    public static final int MAX_CODE=4095;
    public final double a,b,c,k;
    private final double root;
    private final int[] table;
    private final short[] fast=new short[65537];
    public NoiseCurve(double a,double b) {
        if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<0)
            throw new IllegalArgumentException("Invalid noise variance");
        this.a=Math.max(a,1e-6); this.b=Math.max(b,1e-8); c=this.b/this.a; root=Math.sqrt(c);
        k=Math.min(2/(STEP*Math.sqrt(this.a)),MAX_CODE/(Math.sqrt(1+c)-root));
        int top=Math.min(MAX_CODE,(int)Math.ceil(k*(Math.sqrt(1+c)-root)));
        table=new int[top+1];
        for(int i=0;i<=top;i++) {
            double v=i/k+root;
            table[i]=(int)Math.max(0,Math.min(65535,Math.rint((v*v-c)*65535)));
        }
        table[top]=65535;
        for(int i=0;i<fast.length;i++)fast[i]=(short)encode(i/65536f);
    }
    static NoiseCurve from(float[][] noise,float[] neutral) {
        double a=Double.POSITIVE_INFINITY,b=a;
        for(int c=0;c<3;c++) {
            a=Math.min(a,(double)neutral[c]*noise[c][0]);
            b=Math.min(b,(double)neutral[c]*neutral[c]*noise[c][1]);
        }
        return new NoiseCurve(a,b);
    }
    public int encode(float value) {
        return Math.min(table.length-1,(int)Math.rint(k*(Math.sqrt(Math.max(0,Math.min(1,value))+c)-root)));
    }
    /** Linear grid error <= 1/131072; even the minimum-noise curve changes <=1 code. */
    int encodeFast(float value) {
        if(!(value>0))return 0;
        if(value>=1)return fast[65536]&65535;
        return fast[(int)(value*65536+.5f)]&65535;
    }
    public int[] linearizationTable() { return table.clone(); }
}
