// Mirrors Directional16's RGB arithmetic and stripe-local rings. Keep changes
// paired with the Java fallback and the native/Java bit-identity tests.
// No fast math or fused multiply-add: float intermediate rounding is intentional.
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace {
template <class A, class B>
inline auto jmax(A a, B b) -> typename std::common_type<A, B>::type {
  return a > b ? a : b;
}
template <class A, class B>
inline auto jmin(A a, B b) -> typename std::common_type<A, B>::type {
  return a < b ? a : b;
}
inline int reflect(int x, int n) {
  if (x >= 0 && x < n) return x;
  int p = x % (2 * n);
  if (p < 0) p += 2 * n;
  return p < n ? p : 2 * n - 1 - p;
}
inline int mirror(int x, int n) {
  if (x >= 0 && x < n) return x;
  int p = x % (2 * n - 2);
  if (p < 0) p += 2 * n - 2;
  return p < n ? p : 2 * n - 2 - p;
}
inline int colour(int x, int y) { return 1 + ((x >> 2) & 1) - ((y >> 2) & 1); }
inline int bayerColour(int x, int y) { return 1 + (x & 1) - (y & 1); }
inline float clamp(float v, float a, float b) { return jmax(a, jmin(b, v)); }
template <class T>
size_t bytes(const std::vector<T>& a) {
  return sizeof(T) * a.capacity();
}

// Each lane is one pixel. Tap additions stay in the scalar Java order;
// restrict describes the separate input/output arenas, not FP reassociation.
inline float rangeWeight(const float* table, float diff, float inverse, float spatial) {
  float t = jmin((spatial + diff * diff * inverse) * 256, 4095.9998f);
  int idx = (int)t;
  return table[idx] + (table[idx+1]-table[idx])*(t-idx);
}
void filterLine(int n, const float* __restrict table, const float* __restrict inverse,
                const float* r0, const float* r1, const float* r2, const float* r3,
                const float* r4, const float* r5, const float* r6,
                const float* p0, const float* p1, const float* p2, const float* p3,
                const float* p4, const float* p5, const float* p6, float* __restrict dst) {
#if defined(__clang__)
#pragma clang loop vectorize(enable)
#endif
  for (int x=0; x<n; x++) {
    float ref=r3[x], inv=inverse[x], sum=0, total=0, w;
    w=rangeWeight(table,r0[x]-ref,inv,2.f); sum+=w*p0[x]; total+=w;
    w=rangeWeight(table,r1[x]-ref,inv,0.8888888888888888f); sum+=w*p1[x]; total+=w;
    w=rangeWeight(table,r2[x]-ref,inv,0.2222222222222222f); sum+=w*p2[x]; total+=w;
    sum+=p3[x]; total+=1;
    w=rangeWeight(table,r4[x]-ref,inv,0.2222222222222222f); sum+=w*p4[x]; total+=w;
    w=rangeWeight(table,r5[x]-ref,inv,0.8888888888888888f); sum+=w*p5[x]; total+=w;
    w=rangeWeight(table,r6[x]-ref,inv,2.f); sum+=w*p6[x]; total+=w;
    dst[x]=sum/total;
  }
}
void quantizeRatioLine(int n, const float* __restrict src, const float* __restrict den,
                       uint8_t* __restrict dst) {
  constexpr float scale=65535.f/4;
#if defined(__clang__)
#pragma clang loop vectorize(enable)
#endif
  for (int x=0; x<n; x++) {
    float q=src[x]/jmax(den[x],1e-4f)*scale+.5f;
    // Clamping before truncation is identical to Java's saturating cast and
    // integer clamp, including NaN -> 819. It also admits packed conversion.
    uint16_t code=(uint16_t)jmin(jmax(q,819.f),65535.f);
    std::memcpy(dst+2*x,&code,sizeof(code));
  }
}

struct Scratch {
  const float* input = nullptr;
  float* output = nullptr;
  int left, right, size, extLeft, extRight, extSize, blurLeft, blurRight;
  std::vector<float> momentRing, coefficientRing, rowCoefficients, noiseRows, varianceRow, blurRow,
      referenceRow, reference, variance, filtered;
  std::vector<double> moments, coefficients;
  std::vector<int> counts, momentOld, momentAdd, coefficientOld, coefficientAdd, filterX, referenceLeft,
      referenceRight, referenceCenter;
  Scratch(int width, int left, int right) : left(left), right(right), size(right - left) {
    extLeft = jmax(0, left - 6);
    extRight = jmin(width, right + 6);
    extSize = extRight - extLeft;
    momentRing.resize(13 * extSize * 8);
    moments.resize(extSize * 12);
    coefficientRing.resize(13 * size * 6);
    coefficients.resize(size * 6);
    rowCoefficients.resize(extSize * 6);
    counts.resize(extSize);
    filterX.resize(size * 6);
    for (int x = left, q = 0; x < right; x++, q += 6) {
      filterX[q] = mirror(x - 3, width);
      filterX[q + 1] = mirror(x - 2, width);
      filterX[q + 2] = mirror(x - 1, width);
      filterX[q + 3] = mirror(x + 1, width);
      filterX[q + 4] = mirror(x + 2, width);
      filterX[q + 5] = mirror(x + 3, width);
    }
    blurLeft = jmax(0, left - 4);
    blurRight = jmin(width, right + 4);
    referenceLeft.resize(size + 6);
    referenceRight.resize(size + 6);
    referenceCenter.resize(size + 6);
    for (int x = left - 3, q = 0; x < right + 3; x++, q++) {
      int px = mirror(x, width);
      referenceLeft[q] = (px == 0 ? 0 : px - 1) - blurLeft;
      referenceRight[q] = (px == width - 1 ? width - 1 : px + 1) - blurLeft;
      referenceCenter[q] = px - blurLeft;
    }
    momentOld.resize(extSize);
    momentAdd.resize(extSize);
    coefficientOld.resize(size);
    coefficientAdd.resize(size);
    for (int x = extLeft; x < extRight; x++) {
      momentOld[x - extLeft] = reflect(x - 6, width);
      momentAdd[x - extLeft] = reflect(x + 7, width);
    }
    for (int x = left; x < right; x++) {
      coefficientOld[x - left] = (reflect(x - 6, width) - extLeft);
      coefficientAdd[x - left] = (reflect(x + 7, width) - extLeft);
    }
    noiseRows.resize(5 * (size + 4));
    varianceRow.resize(size + 4);
    blurRow.resize(size + 8);
    referenceRow.resize(size + 6);
    reference.resize(7 * size);
    variance.resize(7 * size);
    filtered.resize(7 * size);
    for (int x = extLeft; x < extRight; x++)
      for (int j = -6; j <= 6; j++)
        if ((reflect(x + j, width) & 4) == 0) counts[x - extLeft]++;
  }
  size_t memory() const {
    return bytes(momentRing) + bytes(coefficientRing) + bytes(rowCoefficients) + bytes(noiseRows) +
           bytes(varianceRow) + bytes(blurRow) + bytes(referenceRow) + bytes(reference) + bytes(variance) +
           bytes(filtered) + bytes(moments) + bytes(coefficients) + bytes(counts) + bytes(momentOld) +
           bytes(momentAdd) + bytes(coefficientOld) + bytes(coefficientAdd) + bytes(filterX) +
           bytes(referenceLeft) + bytes(referenceRight) + bytes(referenceCenter);
  }
};

struct ChromaScratch {
  std::vector<float> blur, filtered;
  explicit ChromaScratch(int w) : blur(w * 4), filtered(w * 4) {}
};
struct Context {
  const uint8_t* input = nullptr;
  int inputFirst = 0, stride, width, height, bandRows, threads, rowParts = 2;
  int start = 0, rows = 0, lo = 0, hi = 0, rgbStart = 0, rgbEnd = 0, cw, cf = 0, ch = 0, ringRows = 0;
  std::vector<uint8_t> inputCopy;
  std::vector<float> raw, guide, nativeFit, rgbStorage[2], balancedValues;
  float* rgb[3]{};
  float* mean = nullptr;
  // Coarse Menon precedes the update fit, so its eight planes borrow nativeFit.
  float *m, *gh, *gv, *dh, *dv, *r, *g, *b;
  int coarseCapacity;
  float neutral[3], noise[3][2], EXP[4097], GAUSS_07[3];
  double G[5], RECIPROCAL[170]{};
  std::vector<Scratch> scratch;
  std::vector<ChromaScratch> chromaScratch;
  uint8_t* ratio[3]{};
  uint8_t* luminance = nullptr;
  Context(int stride, int w, int h, int band, int threads)
      : stride(stride), width(w), height(h), bandRows(band), threads(threads), cw(w / 4) {
    int n = w * jmin(h, band + 64), cn = cw * jmin(h / 4, (band + 64 + 3) / 4 + 16);
    raw.resize(n);
    guide.resize(n);
    nativeFit.resize(n);
    for (auto& p : rgbStorage) p.resize(w * jmin(h, band));
    // Output fitting needs raw+guide, but not nativeFit. Once fitting ends,
    // mean can overwrite raw. No extra RGB0 or mean band is necessary.
    rgb[0] = nativeFit.data();
    rgb[1] = rgbStorage[0].data();
    rgb[2] = rgbStorage[1].data();
    mean = raw.data();
    coarseCapacity = cn;
    if (size_t(cn) * 8 > nativeFit.size()) throw std::invalid_argument("Coarse arena exceeds band workspace");
    balancedValues.resize(64 * 1024);
    for (int i = 1; i < 170; i++) RECIPROCAL[i] = 1.0 / i;
    scratch.reserve(threads * rowParts);
    chromaScratch.reserve(8);
    for (int k = 0; k < threads * rowParts; k++)
      scratch.emplace_back(w, w * (k % threads) / threads, w * ((k % threads) + 1) / threads);
    // Lumdir's row scheduler can have more workers than tiny Directional fixtures.
    for (int k = 0; k < 8; k++) chromaScratch.emplace_back(w);
  }
  int sample(int x, int y) const {
    auto* p = input + (y - inputFirst) * stride + (x >> 2) * 5;
    return (int(p[x & 3]) << 2) | ((p[4] >> ((x & 3) * 2)) & 3);
  }
  float balanced(int x, int y) const { return balancedValues[((y & 7) * 8 + (x & 7)) * 1024 + sample(x, y)]; }
  float at(const float* a, int x, int y) const { return a[mirror(y, ch) * cw + mirror(x, cw)]; }
  float constant(const float* a, int x, int y) const {
    return x < 0 || x >= cw || y < 0 || y >= ch ? 0 : a[y * cw + x];
  }
  float difference(const float* a, const float* other, int x, int y, bool horizontal) const {
    int dx = horizontal ? 1 : 0, dy = horizontal ? 0 : 1;
    return .5f * (at(a, x - dx, y - dy) + at(a, x + dx, y + dy)) -
           .5f * (at(other, x - dx, y - dy) + at(other, x + dx, y + dy));
  }
  float energy(const float* a, int x, int y, bool transpose) const {
    if (transpose)
      return constant(a, x + 2, y) + constant(a, x + 2, y - 2) + constant(a, x + 1, y - 1) +
             3 * constant(a, x, y) + 3 * constant(a, x, y - 2) + constant(a, x - 1, y - 1) +
             constant(a, x - 2, y) + constant(a, x - 2, y - 2);
    return constant(a, x, y + 2) + constant(a, x - 2, y + 2) + constant(a, x - 1, y + 1) +
           3 * constant(a, x, y) + 3 * constant(a, x - 2, y) + constant(a, x - 1, y - 1) +
           constant(a, x, y - 2) + constant(a, x - 2, y - 2);
  }
  float interpolate(const float* a, int top, int bottom, int l, int r, double ax, double ay) const {
    double t = a[top + l] * (1.0 - ax) + a[top + r] * (float)ax;
    double b = a[bottom + l] * (1.0 - ax) + a[bottom + r] * (float)ax;
    return (float)(t * (1.0 - ay) + b * ay);
  }
  void begin(int first, int last) {
    rgbStart = first;
    rgbEnd = last;
    start = jmax(0, first - 32);
    rows = jmin(height, last + 32) - start;
    lo = 0;
    hi = rows;
    cf = jmax(0, start / 4 - 8);
    ch = jmin(height / 4, (start + rows + 3) / 4 + 8) - cf;
    rgb[0] = nativeFit.data();
    m = nativeFit.data();
    gh = m + coarseCapacity;
    gv = gh + coarseCapacity;
    dh = gv + coarseCapacity;
    dv = dh + coarseCapacity;
    r = dv + coarseCapacity;
    g = r + coarseCapacity;
    b = g + coarseCapacity;
  }
  void initial(int stage, int task, int tasks) {
    if (stage == 0) {
      for (int y = rows * task / tasks; y < rows * (task + 1) / tasks; y++)
        for (int x = 0; x < width; x++) raw[y * width + x] = balanced(x, start + y);
      return;
    }
    if (stage == 7) {
      const double interpolation[] = {.625, .875, .125, .375};
      for (int y = rows * task / tasks; y < rows * (task + 1) / tasks; y++) {
        int gy = start + y, cy = (gy - 2) >> 2;
        int top = (jmax(0, jmin(height / 4 - 1, cy)) - cf) * cw,
            bottom = (jmax(0, jmin(height / 4 - 1, cy + 1)) - cf) * cw;
        double ay = interpolation[gy & 3];
        for (int x = 0; x < width; x++) {
          int i = y * width + x, c = colour(x, gy), l = jmax(0, jmin(cw - 1, (x - 2) >> 2)),
              rr = jmax(0, jmin(cw - 1, ((x - 2) >> 2) + 1));
          double ax = interpolation[x & 3];
          float green = interpolate(g, top, bottom, l, rr, ax, ay);
          float p = c == 1 ? green : interpolate(c == 0 ? r : b, top, bottom, l, rr, ax, ay);
          guide[i] = raw[i] * clamp(green / jmax(p, .01f), .25f, 4.f);
        }
      }
      return;
    }
    int n = cw * ch;
    for (int i = n * task / tasks; i < n * (task + 1) / tasks; i++) {
      int x = i % cw, y = i / cw, c = bayerColour(x, y + cf);
      switch (stage) {
        case 1: {
          float sum = 0;
          for (int dy = 0; dy < 4; dy++) {
            int gy = (y + cf) * 4 + dy, base = (gy - start) * width + x * 4;
            float row = 0;
            for (int j = 0; j < 4; j++)
              row += gy >= start && gy < start + rows ? raw[base + j] : balanced(x * 4 + j, gy);
            sum += row;
          }
          m[i] = jmax(sum / 16, 0);
          break;
        }
        case 2:
          gh[i] = c == 1 ? m[i]
                         : .5f * (at(m, x - 1, y) + at(m, x + 1, y)) + .5f * m[i] -
                               .25f * (at(m, x - 2, y) + at(m, x + 2, y));
          gv[i] = c == 1 ? m[i]
                         : .5f * (at(m, x, y - 1) + at(m, x, y + 1)) + .5f * m[i] -
                               .25f * (at(m, x, y - 2) + at(m, x, y + 2));
          break;
        case 3:
          dh[i] = std::abs((m[i] - gh[i]) - (at(m, x + 2, y) - at(gh, x + 2, y)));
          dv[i] = std::abs((m[i] - gv[i]) - (at(m, x, y + 2) - at(gv, x, y + 2)));
          break;
        case 4: {
          float horizontal = energy(dh, x, y, false), vertical = energy(dv, x, y, true);
          g[i] = vertical >= horizontal ? gh[i] : gv[i];
          r[i] = c == 0 ? m[i] : 0;
          b[i] = c == 2 ? m[i] : 0;
          gv[i] = vertical >= horizontal ? 1 : 0;
          break;
        }
        case 5:
          if (c == 1) {
            bool redRow = ((y + cf) & 1) == 1;
            r[i] = g[i] + difference(m, g, x, y, redRow);
            b[i] = g[i] + difference(m, g, x, y, !redRow);
          }
          break;
        case 6:
          if (c == 2) r[i] = b[i] + difference(r, b, x, y, gv[i] == 1);
          if (c == 0) b[i] = r[i] + difference(b, r, x, y, gv[i] == 1);
          break;
      }
    }
  }
  void momentRow(Scratch& s, int y, int oldY, int slot, bool initial) {
    // Each native row contains green and just one other colour. Eight ring
    // channels suffice; the vertical sums still retain all three colours.
    double gi = 0, gp = 0, gii = 0, gip = 0, ci = 0, cp = 0, cii = 0, cip = 0;
    int base = y * width, phase = (start + y) & 4;
    int c = phase == 0 ? 8 : 0, oldC = ((start + oldY) & 4) == 0 ? 8 : 0;
    for (int dx = -6; dx <= 6; dx++) {
      int x = reflect(s.extLeft + dx, width);
      float i = s.input[base + x], p = raw[base + x];
      if ((x & 4) == phase) {
        gi += i;
        gp += p;
        gii += (float)(i * i);
        gip += (float)(i * p);
      } else {
        ci += i;
        cp += p;
        cii += (float)(i * i);
        cip += (float)(i * p);
      }
    }
    // Keep horizontal sliding reductions in their original order. Borrow
    // bilateral scratch for the eight row moments, then update the vertical
    // rings across pixels. Each stripe is >=16 wide, so 7*size >=4*extSize.
    float* green = s.reference.data();
    float* other = s.variance.data();
    int n = s.extSize;
    for (int x = s.extLeft, e = 0; x < s.extRight; x++, e++) {
      green[e] = (float)gi;
      green[n + e] = (float)gp;
      green[2*n + e] = (float)gii;
      green[3*n + e] = (float)gip;
      other[e] = (float)ci;
      other[n + e] = (float)cp;
      other[2*n + e] = (float)cii;
      other[3*n + e] = (float)cip;
      int old = s.momentOld[e], add = s.momentAdd[e];
      float i = s.input[base + old], p = raw[base + old];
      if ((old & 4) == phase) {
        gi -= i;
        gp -= p;
        gii -= (float)(i * i);
        gip -= (float)(i * p);
      } else {
        ci -= i;
        cp -= p;
        cii -= (float)(i * i);
        cip -= (float)(i * p);
      }
      i = s.input[base + add];
      p = raw[base + add];
      if ((add & 4) == phase) {
        gi += i;
        gp += p;
        gii += (float)(i * i);
        gip += (float)(i * p);
      } else {
        ci += i;
        cp += p;
        cii += (float)(i * i);
        cip += (float)(i * p);
      }
    }
    for (int channel = 0; channel < 8; channel++) {
      const float* src = channel < 4 ? green + channel*n : other + (channel-4)*n;
      float* ring = s.momentRing.data() + (slot*8 + channel)*n;
      double* dst = s.moments.data() + (channel < 4 ? 4+channel : c+channel-4)*n;
      double* oldDst = s.moments.data() + (channel < 4 ? 4+channel : oldC+channel-4)*n;
      for (int e = 0; e < n; e++) {
        if (!initial) oldDst[e] -= ring[e];
        ring[e] = src[e];
        dst[e] += src[e];
      }
    }
  }
  void coefficientRow(Scratch& s, int y, int aLo, int aHi) {
    // Native R/B counts factor into horizontal and vertical block counts;
    // the green count is their complement. No count ring or float division.
    int ny = 0;
    for (int j = -6; j <= 6; j++)
      if ((reflect(y + j, rows) & 4) == 0) ny++;
    int n = s.extSize;
    for (int channel = 0; channel < 3; channel++) {
      const double* m0 = s.moments.data() + channel*4*n;
      float* aRow = s.rowCoefficients.data() + channel*2*n;
      float* bRow = aRow + n;
#if defined(__clang__)
#pragma clang loop vectorize(enable)
#endif
      for (int e = 0; e < n; e++) {
        int nx = s.counts[e];
        int count = channel == 0 ? nx*(13-ny) : channel == 1 ? nx*ny+(13-nx)*(13-ny) : (13-nx)*ny;
        double inv = RECIPROCAL[count];
        double mi = m0[e]*inv, mp = m0[n+e]*inv;
        double var = jmax(m0[2*n+e]*inv-mi*mi, 0);
        double cov = m0[3*n+e]*inv-mi*mp;
        float a = (float)(cov/(var+0.0001));
        aRow[e] = a;
        bRow[e] = (float)(mp-a*mi);
      }
    }
    double a0 = 0, b0 = 0, a1 = 0, b1 = 0, a2 = 0, b2 = 0;
    for (int dx = -6; dx <= 6; dx++) {
      int z = (reflect(s.left + dx, width) - s.extLeft);
      a0 += s.rowCoefficients[0*n + z];
      b0 += s.rowCoefficients[1*n + z];
      a1 += s.rowCoefficients[2*n + z];
      b1 += s.rowCoefficients[3*n + z];
      a2 += s.rowCoefficients[4*n + z];
      b2 += s.rowCoefficients[5*n + z];
    }
    int ringBase = y % 13 * s.size * 6;
    for (int x = s.left, e = 0; x < s.right; x++, e++) {
      s.coefficientRing[ringBase + 0*s.size + e] = (float)a0;
      s.coefficientRing[ringBase + 1*s.size + e] = (float)b0;
      s.coefficientRing[ringBase + 2*s.size + e] = (float)a1;
      s.coefficientRing[ringBase + 3*s.size + e] = (float)b1;
      s.coefficientRing[ringBase + 4*s.size + e] = (float)a2;
      s.coefficientRing[ringBase + 5*s.size + e] = (float)b2;
      if (x + 1 < s.right) {
        int old = s.coefficientOld[e], add = s.coefficientAdd[e];
        a0 += s.rowCoefficients[0*n + add] - s.rowCoefficients[0*n + old];
        b0 += s.rowCoefficients[1*n + add] - s.rowCoefficients[1*n + old];
        a1 += s.rowCoefficients[2*n + add] - s.rowCoefficients[2*n + old];
        b1 += s.rowCoefficients[3*n + add] - s.rowCoefficients[3*n + old];
        a2 += s.rowCoefficients[4*n + add] - s.rowCoefficients[4*n + old];
        b2 += s.rowCoefficients[5*n + add] - s.rowCoefficients[5*n + old];
      }
    }
    if (y + 1 < aHi) momentRow(s, reflect(y + 7, rows), reflect(y - 6, rows), (y - aLo) % 13, false);
  }
  int bilateralRow(Scratch& s, int y, int nextNoise) {
    int noiseWidth = s.size + 4;
    for (; nextNoise <= jmin(rows - 1, y + 2); nextNoise++) {
      int base = nextNoise * width, slot = nextNoise % 5 * noiseWidth;
      for (int x = s.left - 2, q = 0; x < s.right + 2; x++, q++) {
        int px = reflect(x, width), c = colour(px, start + nextNoise);
        s.noiseRows[slot + q] = noise[c][0] * jmax(s.input[base + px], 0) + noise[c][1];
      }
    }
    int n0 = reflect(y - 2, rows) % 5 * noiseWidth, n1 = reflect(y - 1, rows) % 5 * noiseWidth,
        n2 = y % 5 * noiseWidth;
    int n3 = reflect(y + 1, rows) % 5 * noiseWidth, n4 = reflect(y + 2, rows) % 5 * noiseWidth;
    for (int q = 0; q < noiseWidth; q++)
      s.varianceRow[q] = (float)(((double)s.noiseRows[n0 + q] + s.noiseRows[n1 + q] + s.noiseRows[n2 + q] +
                                  s.noiseRows[n3 + q] + s.noiseRows[n4 + q]) /
                                 5);
    int slot = y % 7 * s.size;
    double sum = 0;
    for (int j = 0; j < 5; j++) sum += s.varianceRow[j];
    for (int q = 0; q < s.size; q++) {
      float variance = (float)(sum / 5);
      // Both bilateral passes use exactly the same variance and reference.
      s.variance[slot + q] = 1 / (3 * variance + 1e-8f);
      if (q + 1 < s.size) sum += (double)s.varianceRow[q + 5] - s.varianceRow[q];
    }
    float side = GAUSS_07[0], center = GAUSS_07[1];
    int top = reflect(y - 1, rows) * width, base = y * width, bottom = reflect(y + 1, rows) * width;
    int left = s.blurLeft, right = s.blurRight;
    for (int x = left; x < right; x++)
      s.blurRow[x - left] = side * (s.input[top + x] + s.input[bottom + x]) + center * s.input[base + x];
    for (int q = 0; q < int(s.referenceRow.size()); q++)
      s.referenceRow[q] = side * (s.blurRow[s.referenceLeft[q]] + s.blurRow[s.referenceRight[q]]) +
                          center * s.blurRow[s.referenceCenter[q]];
    int from=jmax(s.left,3), to=jmin(s.right,width-3);
    auto edge = [&](int x) {
      int q=x-s.left;
      float ref = s.referenceRow[q + 3], inverse = s.variance[slot + q];
      float total = 0, value = 0, w;
      w = rangeWeight(EXP, s.referenceRow[q + 0] - ref, inverse, 2.f);
      value += w * s.input[base + s.filterX[q * 6 + 0]];
      total += w;
      w = rangeWeight(EXP, s.referenceRow[q + 1] - ref, inverse, 0.8888888888888888f);
      value += w * s.input[base + s.filterX[q * 6 + 1]];
      total += w;
      w = rangeWeight(EXP, s.referenceRow[q + 2] - ref, inverse, 0.2222222222222222f);
      value += w * s.input[base + s.filterX[q * 6 + 2]];
      total += w;
      value += s.input[base + x];
      total += 1;
      w = rangeWeight(EXP, s.referenceRow[q + 4] - ref, inverse, 0.2222222222222222f);
      value += w * s.input[base + s.filterX[q * 6 + 3]];
      total += w;
      w = rangeWeight(EXP, s.referenceRow[q + 5] - ref, inverse, 0.8888888888888888f);
      value += w * s.input[base + s.filterX[q * 6 + 4]];
      total += w;
      w = rangeWeight(EXP, s.referenceRow[q + 6] - ref, inverse, 2.f);
      value += w * s.input[base + s.filterX[q * 6 + 5]];
      total += w;
      s.filtered[slot + q] = value / total;
      s.reference[slot + q] = ref;
    };
    for (int x=s.left; x<from; x++) edge(x);
    if (from<to) {
      int q=from-s.left;
      const float* r=s.referenceRow.data()+q;
      const float* p=s.input+base+from;
      filterLine(to-from, EXP, s.variance.data()+slot+q, r,r+1,r+2,r+3,r+4,r+5,r+6,
                 p-3,p-2,p-1,p,p+1,p+2,p+3,s.filtered.data()+slot+q);
    }
    for (int x=to; x<s.right; x++) edge(x);
    std::memcpy(s.reference.data()+slot,s.referenceRow.data()+3,s.size*sizeof(float));
    return nextNoise;
  }
  void fit(bool output, int k, int lo, int hi) {
    int globalALo = lo + (start == 0 ? 0 : 6), globalAHi = hi - (start + rows == height ? 0 : 6);
    int globalOutLo = globalALo + (start == 0 ? 0 : 6),
        globalOutHi = globalAHi - (start + rows == height ? 0 : 6);

    Scratch& s = scratch[k];
    int part = k / threads;
    int outLo = globalOutLo + (globalOutHi - globalOutLo) * part / rowParts;
    int outHi = globalOutLo + (globalOutHi - globalOutLo) * (part + 1) / rowParts;
    int aLo = jmax(globalALo, outLo - 6), aHi = jmin(globalAHi, outHi + 6);
    std::fill(s.moments.begin(), s.moments.end(), 0);
    std::fill(s.coefficients.begin(), s.coefficients.end(), 0);
    for (int j = 0; j < 13; j++) momentRow(s, reflect(aLo + j - 6, rows), 0, j, true);
    int next = aLo;
    for (; next < jmin(aHi, outLo + 7); next++) coefficientRow(s, next, aLo, aHi);
    for (int j = -6; j <= 6; j++) {
      int slot = reflect(outLo + j, rows) % 13 * s.size * 6;
      for (int q = 0; q < s.size * 6; q++) s.coefficients[q] += s.coefficientRing[slot + q];
    }
    for (int y = outLo; y < outHi; y++) {
      int base = y * width, gy = start + y;
      if (output) {
        if (gy >= rgbStart && gy < rgbEnd) for (int channel = 0; channel < 3; channel++) {
          const double* a = s.coefficients.data()+channel*2*s.size;
          const double* b = a+s.size;
          float* dst = rgb[channel]+(gy-rgbStart)*width+s.left;
          const float* src = s.input+base+s.left;
          float neutralValue = neutral[channel];
          for (int e = 0; e < s.size; e++) {
            float value = (float)((a[e]*src[e]+b[e])*(1.0/169.0));
            dst[e] = jmax(value*neutralValue,0)/neutralValue;
          }
        }
      } else {
        for (int x = s.left, e = 0; x < s.right; x++, e++) {
          int i = base+x, c = colour(x,gy), z = c*2*s.size+e;
          float v = (float)((s.coefficients[z]*s.input[i]+s.coefficients[z+s.size])*(1.0/169.0));
          float g = (float)((s.coefficients[2*s.size+e]*s.input[i]+s.coefficients[3*s.size+e])*(1.0/169.0));
          s.output[i] = raw[i]*clamp(g/jmax(v,0.01f),0.3f,3.f);
        }
      }
      if (y + 1 == outHi) break;
      int old = reflect(y - 6, rows) % 13 * s.size * 6;
      for (int q = 0; q < s.size * 6; q++) s.coefficients[q] -= s.coefficientRing[old + q];
      if (next < aHi) coefficientRow(s, next++, aLo, aHi);
      int add = reflect(y + 7, rows) % 13 * s.size * 6;
      for (int q = 0; q < s.size * 6; q++) s.coefficients[q] += s.coefficientRing[add + q];
    }
  }
  void bilateral(int k, int lo, int hi) {
    Scratch& s = scratch[k];
    int globalFrom = lo + (start == 0 ? 0 : 5), globalTo = hi - (start + rows == height ? 0 : 5),
        part = k / threads;
    int from = globalFrom + (globalTo - globalFrom) * part / rowParts;
    int to = globalFrom + (globalTo - globalFrom) * (part + 1) / rowParts;
    int hFrom = jmax(lo + (start == 0 ? 0 : 2), from - 3),
        hTo = jmin(hi - (start + rows == height ? 0 : 2), to + 3);
    int nextNoise = jmax(0, hFrom - 2), next = hFrom;
    for (; next <= from + 3; next++) nextNoise = bilateralRow(s, next, nextNoise);
    for (int y = from; y < to; y++) {
      int center = y % 7 * s.size;
      int r0 = mirror(y - 3, rows) % 7 * s.size;
      int r1 = mirror(y - 2, rows) % 7 * s.size;
      int r2 = mirror(y - 1, rows) % 7 * s.size;
      int r4 = mirror(y + 1, rows) % 7 * s.size;
      int r5 = mirror(y + 2, rows) % 7 * s.size;
      int r6 = mirror(y + 3, rows) % 7 * s.size;
      const float* r=s.reference.data();
      const float* p=s.filtered.data();
      filterLine(s.size,EXP,s.variance.data()+center,
                 r+r0,r+r1,r+r2,r+center,r+r4,r+r5,r+r6,
                 p+r0,p+r1,p+r2,p+center,p+r4,p+r5,p+r6,s.output+y*width+s.left);
      if (y + 1 < to && next < hTo) nextNoise = bilateralRow(s, next++, nextNoise);
    }
  }

  void stage(int stage, int task, int tasks) {
    if (stage < 8)
      initial(stage, task, tasks);
    else if (stage == 8 || stage == 12) {
      scratch[task].input = guide.data();
      scratch[task].output = nativeFit.data();
      fit(stage == 12, task, lo, hi);
    } else if (stage == 9 || stage == 11) {
      int radius = stage == 9 ? 12 : 5;
      if (start != 0) lo += radius;
      if (start + rows != height) hi -= radius;
      guide.swap(nativeFit);
      if (stage == 11) rgb[0] = nativeFit.data();
    } else if (stage == 10) {
      scratch[task].input = guide.data();
      scratch[task].output = nativeFit.data();
      bilateral(task, lo, hi);
    } else if (stage == 13) {
      int n = (rgbEnd - rgbStart) * width;
      for (int i = n * task / tasks; i < n * (task + 1) / tasks; i++)
        mean[i] = jmax((rgb[0][i] + rgb[1][i] + rgb[2][i]) / 3.f, 1e-4f);
    }
  }
  // Fixed arenas and per-task inputs allow neighbouring columns to progress
  // through update -> bilateral -> RGB without swapping shared context pointers.
  // Java only releases a task after both row parts of all adjacent columns have
  // completed the preceding stage (the widest read halo is 12; columns >=16).
  void stripe(int stage, int task) {
    Scratch& s = scratch[task];
    int updateLo = start == 0 ? 0 : 12, updateHi = rows - (start + rows == height ? 0 : 12);
    if (stage == 8) {
      s.input = guide.data();
      s.output = nativeFit.data();
      fit(false, task, 0, rows);
    } else if (stage == 10) {
      s.input = nativeFit.data();
      s.output = guide.data();
      bilateral(task, updateLo, updateHi);
    } else {
      int cleanLo = updateLo + (start == 0 ? 0 : 5), cleanHi = updateHi - (start + rows == height ? 0 : 5);
      s.input = guide.data();
      fit(true, task, cleanLo, cleanHi);
    }
  }
  void chromaRows(int worker, int first, int last) {
    auto& s = chromaScratch[worker];
    constexpr float unit = 4.f / 65535;
    for (int gy = first; gy < last; gy++) {
      int row[9];
      for (int k = -4; k <= 4; k++) row[k + 4] = (reflect(gy + k, height) - rgbStart) * width;
      for (int c = 0; c < 4; c++) {
        const auto& src = c == 3 ? mean : rgb[c];
        float* dst = s.blur.data() + c * width;
        for (int x = 0; x < width; x++)
          dst[x] = (float)(G[4] * src[row[4] + x] + G[3] * (src[row[3] + x] + (double)src[row[5] + x]) +
                           G[2] * (src[row[2] + x] + (double)src[row[6] + x]) +
                           G[1] * (src[row[1] + x] + (double)src[row[7] + x]) +
                           G[0] * (src[row[0] + x] + (double)src[row[8] + x]));
      }
      for (int c = 0; c < 4; c++) {
        const float* src = s.blur.data() + c * width;
        float* dst = s.filtered.data() + c * width;
        // Separate the four reflected edge columns so the interior
        // double-precision Gaussian vectorizes without edge predicates.
        for (int x = 0; x < 4; x++) dst[x] = horizontal(src, x);
        for (int x = 4; x < width - 4; x++)
          dst[x] =
              (float)(G[4] * src[x] + G[3] * (src[x - 1] + (double)src[x + 1]) +
                      G[2] * (src[x - 2] + (double)src[x + 2]) + G[1] * (src[x - 3] + (double)src[x + 3]) +
                      G[0] * (src[x - 4] + (double)src[x + 4]));
        for (int x = width - 4; x < width; x++) dst[x] = horizontal(src, x);
      }
      int at = (gy % ringRows) * width;
      for (int c = 0; c < 3; c++) {
        quantizeRatioLine(width,s.filtered.data()+c*width,s.filtered.data()+3*width,ratio[c]+2*at);
      }
      for (int x=0; x<width; x++) {
        uint16_t ownCode;
        std::memcpy(&ownCode,ratio[colour(x,gy)]+2*(at+x),sizeof(ownCode));
        float own = ownCode * unit, value = balanced(x, gy) / own;
        std::memcpy(luminance + 4 * (at + x), &value, sizeof(value));
      }
    }
  }
  float horizontal(const float* src, int x) const {
    return (float)(G[4] * src[x] + G[3] * (src[reflect(x - 1, width)] + (double)src[reflect(x + 1, width)]) +
                   G[2] * (src[reflect(x - 2, width)] + (double)src[reflect(x + 2, width)]) +
                   G[1] * (src[reflect(x - 3, width)] + (double)src[reflect(x + 3, width)]) +
                   G[0] * (src[reflect(x - 4, width)] + (double)src[reflect(x + 4, width)]));
  }
  size_t memory() const {
    size_t n = sizeof(*this) + bytes(inputCopy) + bytes(raw) + bytes(guide) + bytes(nativeFit) +
               bytes(balancedValues);
    for (auto& a : rgbStorage) n += bytes(a);
    for (auto& s : scratch) n += s.memory();
    for (auto& s : chromaScratch) n += bytes(s.blur) + bytes(s.filtered);
    return n;
  }
};
Context* context(jlong p) { return reinterpret_cast<Context*>(static_cast<intptr_t>(p)); }
void fail(JNIEnv* e, const char* type, const char* message) {
  if (!e->ExceptionCheck()) e->ThrowNew(e->FindClass(type), message);
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_dev_s24cam_dng_LumdirNative_directionalCreate(
    JNIEnv* e, jclass, jobject input, jint stride, jint width, jint height, jint band, jint threads,
    jfloatArray balanced, jfloatArray neutral, jfloatArray noise, jfloatArray exp, jfloatArray gauss,
    jdoubleArray chroma) {
  try {
    auto c = std::make_unique<Context>(stride, width, height, band, threads);
    if (input) {
      c->input = (const uint8_t*)e->GetDirectBufferAddress(input);
      if (!c->input) throw std::invalid_argument("Direct RAW buffer required");
    } else
      c->inputCopy.resize(size_t(jmin(height, band + 128)) * stride);
    e->GetFloatArrayRegion(balanced, 0, 64 * 1024, c->balancedValues.data());
    e->GetFloatArrayRegion(neutral, 0, 3, c->neutral);
    e->GetFloatArrayRegion(noise, 0, 6, &c->noise[0][0]);
    e->GetFloatArrayRegion(exp, 0, 4097, c->EXP);
    e->GetFloatArrayRegion(gauss, 0, 3, c->GAUSS_07);
    e->GetDoubleArrayRegion(chroma, 0, 5, c->G);
    if (e->ExceptionCheck()) return 0;
    return reinterpret_cast<intptr_t>(c.release());
  } catch (const std::bad_alloc&) {
    fail(e, "java/lang/OutOfMemoryError", "Native directional workspace");
  } catch (const std::exception& ex) {
    fail(e, "java/lang/IllegalArgumentException", ex.what());
  }
  return 0;
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_directionalInput(JNIEnv* e, jclass,
                                                                                    jlong p, jbyteArray input,
                                                                                    jint first, jint rows) {
  auto& c = *context(p);
  e->GetByteArrayRegion(input, 0, (rows - 1) * c.stride + c.width * 5 / 4,
                        reinterpret_cast<jbyte*>(c.inputCopy.data()));
  c.input = c.inputCopy.data();
  c.inputFirst = first;
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_directionalBegin(JNIEnv*, jclass, jlong p,
                                                                                    jint first, jint last) {
  context(p)->begin(first, last);
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_directionalStage(JNIEnv*, jclass, jlong p,
                                                                                    jint stage, jint task,
                                                                                    jint tasks) {
  context(p)->stage(stage, task, tasks);
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_directionalCopyRgb(JNIEnv* e, jclass,
                                                                                      jlong p, jfloatArray r,
                                                                                      jfloatArray g,
                                                                                      jfloatArray b) {
  auto& c = *context(p);
  int n = (c.rgbEnd - c.rgbStart) * c.width;
  e->SetFloatArrayRegion(r, 0, n, c.rgb[0]);
  e->SetFloatArrayRegion(g, 0, n, c.rgb[1]);
  e->SetFloatArrayRegion(b, 0, n, c.rgb[2]);
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_chromaBegin(JNIEnv* e, jclass, jlong p,
                                                                               jint first, jint last,
                                                                               jint ringRows, jobject r,
                                                                               jobject g, jobject b,
                                                                               jobject y) {
  auto& c = *context(p);
  c.ringRows = ringRows;
  c.ratio[0] = (uint8_t*)e->GetDirectBufferAddress(r);
  c.ratio[1] = (uint8_t*)e->GetDirectBufferAddress(g);
  c.ratio[2] = (uint8_t*)e->GetDirectBufferAddress(b);
  c.luminance = (uint8_t*)e->GetDirectBufferAddress(y);
  if (!c.ratio[0] || !c.ratio[1] || !c.ratio[2] || !c.luminance)
    fail(e, "java/lang/IllegalArgumentException", "Direct ring buffers required");
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_chromaRows(JNIEnv*, jclass, jlong p,
                                                                              jint worker, jint first,
                                                                              jint last) {
  context(p)->chromaRows(worker, first, last);
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_s24cam_dng_LumdirNative_directionalBytes(JNIEnv*, jclass,
                                                                                     jlong p) {
  return context(p)->memory();
}
extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_directionalDestroy(JNIEnv*, jclass,
                                                                                      jlong p) {
  delete context(p);
}

extern "C" JNIEXPORT void JNICALL Java_dev_s24cam_dng_LumdirNative_directionalStripe(JNIEnv*, jclass, jlong p,
                                                                                     jint stage, jint task) {
  context(p)->stripe(stage, task);
}
