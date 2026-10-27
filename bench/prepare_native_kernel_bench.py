"""Generate a standalone before/after kernel benchmark from HEAD and the worktree.

No JNI calls, thread scheduling or image codec work is timed. Compile the emitted
build/native-kernel-bench.cc with the same strict FP flags as libs24lumdir.
Each kernel's timer excludes time spent in nested kernels. The deterministic
fixture checks all reconstructed RGB floats and all four chroma rings bitwise.
HEAD must still be the unmodified v0.5.0 baseline (do not commit before running).
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
FILE = 'app/src/main/cpp/lumdir_directional.cc'
TIMER = r'''
double work[6]{};
struct Timer;
Timer* active=nullptr;
struct Timer {
  int index; Timer* parent; double child=0;
  std::chrono::steady_clock::time_point start=std::chrono::steady_clock::now();
  explicit Timer(int index):index(index),parent(active){active=this;}
  ~Timer(){double s=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
    work[index]+=s-child;if(parent)parent->child+=s;active=parent;}
};
'''


def kernel(text, name):
    text = text[:text.index('Context* context')]
    text = text.replace('namespace {', f'namespace {name} {{\n' + TIMER, 1)
    for i, method in enumerate(('momentRow', 'coefficientRow', 'bilateralRow', 'fit', 'bilateral', 'chromaRows')):
        a = text.index('  ' + ('int' if method == 'bilateralRow' else 'void') + ' ' + method + '(')
        b = text.index('{', a) + 1
        text = text[:b] + f' Timer timer({i});' + text[b:]
    return text + '\n}\n'


MAIN = r'''
constexpr int W=2048,H=768,BAND=208,THREADS=8,RINGS=576;
template<class C> void init(C& c,const uint8_t* input) {
  c.input=input;c.ringRows=RINGS;
  c.neutral[0]=.5f;c.neutral[1]=1;c.neutral[2]=.55f;
  for(int channel=0;channel<3;channel++){c.noise[channel][0]=.001f;c.noise[channel][1]=.0001f;}
  for(int i=0;i<4097;i++)c.EXP[i]=(float)std::exp(-i/256.0);
  double sum=0;for(int k=-1;k<=1;k++)sum+=std::exp(-.5*k*k/(.7*.7));
  for(int k=0;k<3;k++)c.GAUSS_07[k]=(float)(std::exp(-.5*(k-1)*(k-1)/(.7*.7))/sum);
  sum=0;for(int k=-4;k<=4;k++)sum+=std::exp(-.5*k*k);
  for(int k=0;k<5;k++)c.G[k]=std::exp(-.5*(4-k)*(4-k))/sum;
  for(int phase=0;phase<64;phase++)for(int v=0;v<1024;v++)
    c.balancedValues[phase*1024+v]=((v-64)/959.f)/c.neutral[1+((phase&7)>>2)-((phase>>3)>>2)];
}
template<class C> void band(C& c,int first,int last) {
  c.begin(first,last);
  for(int stage=0;stage<8;stage++)for(int task=0;task<32;task++)c.stage(stage,task,32);
  for(int task=0;task<16;task++)c.stage(8,task,16);
  c.stage(9,0,1);
  for(int task=0;task<16;task++)c.stage(10,task,16);
  c.stage(11,0,1);
  for(int task=0;task<16;task++)c.stage(12,task,16);
  c.stage(13,0,1);
  c.chromaRows(0,first==0?0:first+4,last==H?H:last-4);
}
int main() {
  std::vector<uint8_t> raw(W*5/4*H);
  uint32_t random=721;
  for(int i=0;i<W*H;i+=4){uint8_t* p=raw.data()+i/4*5;p[4]=0;
    for(int j=0;j<4;j++){random=random*1664525u+1013904223u;int v=64+random%960;
      p[j]=v>>2;p[4]|=(v&3)<<(j*2);}}
  reference::Context before(W*5/4,W,H,BAND,THREADS);
  optimized::Context after(W*5/4,W,H,BAND,THREADS);
  init(before,raw.data());init(after,raw.data());
  std::vector<uint8_t> a[4],b[4];
  for(int c=0;c<4;c++){a[c].resize(W*RINGS*(c==3?4:2));b[c]=a[c];
    if(c<3){before.ratio[c]=a[c].data();after.ratio[c]=b[c].data();}
    else{before.luminance=a[c].data();after.luminance=b[c].data();}}
  double totals[2]{};
  for(int repeat=0;repeat<4;repeat++)for(int first=0;first<H;first+=192){int last=std::min(H,first+BAND);
    auto t=std::chrono::steady_clock::now();band(before,first,last);
    totals[0]+=std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count();
    t=std::chrono::steady_clock::now();band(after,first,last);
    totals[1]+=std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count();
    for(int c=0;c<3;c++)if(std::memcmp(before.rgb[c],after.rgb[c],W*(last-first)*sizeof(float))){
      std::printf("RGB MISMATCH repeat %d first %d channel %d\n",repeat,first,c);return 1;}
    for(int c=0;c<4;c++)if(a[c]!=b[c]){std::printf("RING MISMATCH %d\n",c);return 1;}
  }
  const char* names[]={"momentRow","coefficientRow","bilateralRow","fit","bilateral","chromaRows"};
  for(int i=0;i<6;i++)std::printf("%-16s before %.6f after %.6f ratio %.3f\n",names[i],reference::work[i],optimized::work[i],optimized::work[i]/reference::work[i]);
  std::printf("ALL kernels + guide before %.6f after %.6f ratio %.3f; bitwise identical\n",totals[0],totals[1],totals[1]/totals[0]);
}
'''

if __name__ == '__main__':
    before = subprocess.check_output(['git', 'show', 'HEAD:' + FILE], cwd=ROOT, text=True)
    after = (ROOT / FILE).read_text()
    target = ROOT / 'build/native-kernel-bench.cc'
    target.parent.mkdir(exist_ok=True)
    target.write_text('#include <chrono>\n#include <cstdio>\n' + kernel(before, 'reference') + kernel(after, 'optimized') + MAIN)
    print(target)
