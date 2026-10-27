#include <jni.h>
#include <cstdint>
#include <cmath>
#include <utility>
#ifdef _MSC_VER
#include <intrin.h>
#endif

namespace {
inline int First(uint64_t v) {
#ifdef _MSC_VER
  unsigned long index;_BitScanForward64(&index,v);return static_cast<int>(index);
#else
  return __builtin_ctzll(v);
#endif
}
inline int Last(uint64_t v) {
#ifdef _MSC_VER
  unsigned long index;_BitScanReverse64(&index,v);return static_cast<int>(index);
#else
  return 63-__builtin_clzll(v);
#endif
}
struct Histogram {
  // A 144-sample window fits unsigned bytes: 16 KiB counts + 2 KiB bitmap.
  uint8_t count[16384]={};uint64_t bits[256]={};
  int current=8192,below=0;
  inline void Add(int key) {
    below+=key<current;
    if(count[key]++==0) bits[key>>6]|=uint64_t{1}<<(key&63);
  }
  inline void Remove(int key) {
    below-=key<current;
    if(--count[key]==0) bits[key>>6]&=~(uint64_t{1}<<(key&63));
  }
  inline void Replace(int old,int value) {if(old!=value){Remove(old);Add(value);}}
  inline int Next(bool up) {
    // At most 144 occupied bins: walking the adjacent bitmap words is
    // cheaper than maintaining a second occupancy hierarchy on every update.
    // Median only calls Next when another occupied bin is known to exist.
    int word=current>>6,bit=current&63;uint64_t found;
    if(up) {
      found=bit==63?0:bits[word]&(~uint64_t{0}<<(bit+1));if(found)return word*64+First(found);
      while(!found)found=bits[++word];
      return word*64+First(found);
    }
    found=bit==0?0:bits[word]&(~uint64_t{0}>>(64-bit));if(found)return word*64+Last(found);
    while(!found)found=bits[--word];
    return word*64+Last(found);
  }
  inline int Median() {
    while(below>72){current=Next(false);below-=count[current];}
    while(below+count[current]<=72){below+=count[current];current=Next(true);}
    return current;
  }
};
template<class Emit>
void Select(const jshort* values, int pitch, int columns, int rows, Emit emit) {
  Histogram hist;
  for(int y=0;y<12;y++)for(int x=0;x<12;x++)hist.Add(values[y*pitch+x]);
  int x=0;
  for(int y=0;y<rows;y++) {
    int direction=(y&1)?-1:1;
    for(int step=0;step<columns;step++) {
      emit(y,x,hist.Median());
      if(step+1<columns) {
        int old=x+(direction==1?0:11),add=x+(direction==1?12:-1);
        for(int k=0;k<12;k++)hist.Replace(values[(y+k)*pitch+old],values[(y+k)*pitch+add]);
        x+=direction;
      }
    }
    if(y+1<rows)for(int k=0;k<12;k++)hist.Replace(values[y*pitch+x+k],values[(y+12)*pitch+x+k]);
  }
}
int Quantize(float v) {
  float q=v*4096.f;
  if(q<=-4096)return 0;
  if(q>=12287)return 16383;
  // Java's NaN-to-int conversion is zero (after floor).
  return q==q ? (int)std::floor(q+.5f)+4096 : 4096;
}
float ExactMedian(float* a) {
  int left=0,right=143;
  while(left<right) {
    float pivot=a[(left+right)>>1];int low=left,scan=left,high=right;
    while(scan<=high) {
      if(a[scan]<pivot)std::swap(a[low++],a[scan++]);
      else if(a[scan]>pivot)std::swap(a[high--],a[scan]);
      else scan++;
    }
    if(72<low)right=low-1;else if(72>high)left=high+1;else return a[72];
  }
  return a[72];
}
void Invalid(JNIEnv* env,const char* text) {jclass cls=env->FindClass("java/lang/IllegalArgumentException");if(cls){env->ThrowNew(cls,text);env->DeleteLocalRef(cls);}}
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_medianNative(
    JNIEnv* env,jclass,jshortArray input,jint pitch,jint columns,jint rows,jshortArray output) {
  if(!input||!output||columns<1||columns>256||rows<1||rows>32||pitch!=columns+11
      ||env->GetArrayLength(input)<(rows+11)*pitch||env->GetArrayLength(output)<rows*columns) {
    Invalid(env,"Invalid phase stripe");return;
  }
  jshort* values=env->GetShortArrayElements(input,nullptr);if(!values)return;
  for(int i=0;i<(rows+11)*pitch;i++)if(values[i]<0||values[i]>=16384) {
    env->ReleaseShortArrayElements(input,values,JNI_ABORT);Invalid(env,"Invalid phase bin");return;
  }
  jshort result[256*32];
  Select(values,pitch,columns,rows,[&](int y,int x,int q){result[y*columns+x]=static_cast<jshort>(q);});
  env->ReleaseShortArrayElements(input,values,JNI_ABORT);
  env->SetShortArrayRegion(output,0,rows*columns,result);
}

// Only a bounded phase stripe crosses JNI. No large array copies or critical
// pins. Tail selection still uses the original floats, never widened bins.
extern "C" JNIEXPORT jlong JNICALL Java_dev_s24cam_dng_LumdirNative_medianFloatsNative(
    JNIEnv* env,jclass,jfloatArray input,jint pitch,jint columns,jint rows,jfloatArray output) {
  if(!input||!output||columns<1||columns>256||rows<1||rows>32||pitch!=columns+11
      ||env->GetArrayLength(input)<(rows+11)*pitch||env->GetArrayLength(output)<rows*columns) {
    Invalid(env,"Invalid phase stripe");return 0;
  }
  jfloat values[43*267], result[32*256];jshort bins[43*267];
  int n=(rows+11)*pitch;
  env->GetFloatArrayRegion(input,0,n,values);if(env->ExceptionCheck())return 0;
  for(int i=0;i<n;i++)bins[i]=static_cast<jshort>(Quantize(values[i]));
  jlong tails=0;
  Select(bins,pitch,columns,rows,[&](int y,int x,int q){
    float v;
    if(q==0||q==16383) {
      float exact[144];
      for(int a=0;a<12;a++)for(int b=0;b<12;b++)exact[a*12+b]=values[(y+a)*pitch+x+b];
      v=ExactMedian(exact);tails++;
    }else v=(q-4096)*(1.f/4096);
    result[y*columns+x]=v;
  });
  env->SetFloatArrayRegion(output,0,rows*columns,result);
  return tails;
}
