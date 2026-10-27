#include <jni.h>
#include <jxl/color_encoding.h>
#include <jxl/encode.h>
#include <jxl/decode.h>
#include "jxl_raw_settings.h"

#include <cmath>
#include <cstring>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <stdexcept>
#include <vector>

namespace {
void Throw(JNIEnv* env, const char* type, const char* message) {
  if (!env->ExceptionCheck()) {
    jclass cls = env->FindClass(type);
    if (cls != nullptr) {
      env->ThrowNew(cls, message);
      env->DeleteLocalRef(cls);
    }
  }
}

void Check(JxlEncoderStatus status, const char* operation) {
  if (status != JXL_ENC_SUCCESS) throw std::runtime_error(operation);
}

uint64_t ReadBE(const uint8_t* data, size_t size) {
  uint64_t value = 0;
  for (size_t i = 0; i < size; ++i) value = (value << 8) | data[i];
  return value;
}

// libjxl automatically wraps level-10 streams (>12-bit lossless) in BMFF even
// with UseContainer(false). Our single-frame, no-box configuration emits one
// jxlc box. Return its payload directly, without an extra output-buffer copy.
void CodestreamRange(const std::vector<uint8_t>& output, size_t used,
                     size_t* offset, size_t* size) {
  *offset = 0;
  *size = used;
  if (used >= 2 && output[0] == 0xff && output[1] == 0x0a) return;
  size_t pos = 0;
  bool found = false;
  while (pos < used) {
    if (used - pos < 8) throw std::runtime_error("Truncated JXL box header");
    uint64_t box_size = ReadBE(output.data() + pos, 4);
    size_t header = 8;
    if (box_size == 1) {
      if (used - pos < 16) throw std::runtime_error("Truncated extended JXL box header");
      box_size = ReadBE(output.data() + pos + 8, 8);
      header = 16;
    } else if (box_size == 0) {
      box_size = used - pos;
    }
    if (box_size < header || box_size > used - pos) {
      throw std::runtime_error("Invalid JXL box size");
    }
    if (std::memcmp(output.data() + pos + 4, "jxlc", 4) == 0) {
      if (found) throw std::runtime_error("Multiple JXL codestream boxes");
      found = true;
      *offset = pos + header;
      *size = static_cast<size_t>(box_size) - header;
    } else if (std::memcmp(output.data() + pos + 4, "jxlp", 4) == 0) {
      throw std::runtime_error("Unexpected partial JXL codestream box");
    }
    pos += static_cast<size_t>(box_size);
  }
  if (!found || *size < 2 || output[*offset] != 0xff || output[*offset + 1] != 0x0a) {
    throw std::runtime_error("Missing bare JXL codestream");
  }
}

// Elements may be pinned or copied by the VM. Do not hold a GC-critical section
// across libjxl's allocations/encoding; release as soon as AddImageFrame consumes it.
class ShortElements {
 public:
  ShortElements(JNIEnv* env, jshortArray array)
      : env_(env), array_(array), data_(env->GetShortArrayElements(array, nullptr)) {}
  ~ShortElements() {
    if (data_ != nullptr) env_->ReleaseShortArrayElements(array_, data_, JNI_ABORT);
  }
  const uint16_t* data() const { return reinterpret_cast<const uint16_t*>(data_); }

 private:
  JNIEnv* env_;
  jshortArray array_;
  jshort* data_;
};
}  // namespace

extern "C" JNIEXPORT jshortArray JNICALL
Java_dev_s24cam_dng_JxlNative_decodeNative(JNIEnv* env, jclass, jbyteArray input,
                                         jint width, jint height, jint channels) {
  if (input == nullptr || width <= 0 || height <= 0 || (channels != 1 && channels != 3)) {
    Throw(env, "java/lang/IllegalArgumentException", "Invalid JPEG XL decode input");
    return nullptr;
  }
  const uint64_t count = static_cast<uint64_t>(width) * height * channels;
  if (count > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
    Throw(env, "java/lang/IllegalArgumentException", "JPEG XL tile too large");
    return nullptr;
  }
  try {
    std::vector<uint8_t> bytes(env->GetArrayLength(input));
    env->GetByteArrayRegion(input, 0, static_cast<jsize>(bytes.size()),
                           reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) return nullptr;
    if (bytes.size() < 2 || bytes[0] != 0xff || bytes[1] != 0x0a)
      throw std::runtime_error("Expected bare JPEG XL codestream");
    std::unique_ptr<JxlDecoder, decltype(&JxlDecoderDestroy)> dec(JxlDecoderCreate(nullptr), JxlDecoderDestroy);
    if (!dec) throw std::bad_alloc();
    auto check = [](JxlDecoderStatus status) {
      if (status != JXL_DEC_SUCCESS) throw std::runtime_error("JPEG XL decoder setup failed");
    };
    // No parallel runner: each call executes only on the calling tile worker.
    check(JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO | JXL_DEC_FRAME | JXL_DEC_FULL_IMAGE));
    check(JxlDecoderSetKeepOrientation(dec.get(), JXL_TRUE));
    check(JxlDecoderSetInput(dec.get(), bytes.data(), bytes.size()));
    JxlDecoderCloseInput(dec.get());
    const JxlPixelFormat format = {static_cast<uint32_t>(channels), JXL_TYPE_UINT16, JXL_NATIVE_ENDIAN, 0};
    std::vector<uint16_t> samples;
    bool info_seen = false, complete = false;
    int frames = 0;
    for (;;) {
      const auto status = JxlDecoderProcessInput(dec.get());
      if (status == JXL_DEC_BASIC_INFO) {
        JxlBasicInfo info;
        check(JxlDecoderGetBasicInfo(dec.get(), &info));
        if (info.xsize != static_cast<uint32_t>(width) || info.ysize != static_cast<uint32_t>(height) ||
            info.num_color_channels != static_cast<uint32_t>(channels) || info.num_extra_channels != 0 ||
            info.bits_per_sample != 16 || info.exponent_bits_per_sample != 0 ||
            info.have_animation || info.have_preview || info.orientation != JXL_ORIENT_IDENTITY ||
            !info.uses_original_profile)
          throw std::runtime_error("JPEG XL must be a single untransformed 16-bit integer tile of the expected size");
        info_seen = true;
      } else if (status == JXL_DEC_FRAME) {
        JxlFrameHeader frame;
        check(JxlDecoderGetFrameHeader(dec.get(), &frame));
        if (++frames != 1 || !frame.is_last) throw std::runtime_error("Unexpected JPEG XL frames");
      } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
        if (!info_seen || frames != 1 || !samples.empty()) throw std::runtime_error("Unexpected JPEG XL output");
        size_t size = 0;
        check(JxlDecoderImageOutBufferSize(dec.get(), &format, &size));
        if (size != count * sizeof(uint16_t)) throw std::runtime_error("JPEG XL output size mismatch");
        samples.resize(static_cast<size_t>(count));
        check(JxlDecoderSetImageOutBuffer(dec.get(), &format, samples.data(), size));
        JxlBitDepth depth = {};
        depth.type = JXL_BIT_DEPTH_FROM_CODESTREAM;
        check(JxlDecoderSetImageOutBitDepth(dec.get(), &depth));
      } else if (status == JXL_DEC_FULL_IMAGE) {
        if (complete) throw std::runtime_error("Multiple JPEG XL images");
        complete = true;
      } else if (status == JXL_DEC_SUCCESS) {
        if (!complete || samples.size() != count || JxlDecoderReleaseInput(dec.get()) != 0)
          throw std::runtime_error("Incomplete JPEG XL tile or trailing bytes");
        jshortArray result = env->NewShortArray(static_cast<jsize>(count));
        if (result) env->SetShortArrayRegion(result, 0, static_cast<jsize>(count),
                                             reinterpret_cast<const jshort*>(samples.data()));
        return result;
      } else {
        throw std::runtime_error("Corrupt or truncated JPEG XL tile");
      }
    }
  } catch (const std::bad_alloc&) {
    Throw(env, "java/lang/OutOfMemoryError", "JPEG XL tile allocation failed");
  } catch (const std::exception& e) {
    Throw(env, "java/lang/IllegalStateException", e.what());
  } catch (...) {
    Throw(env, "java/lang/IllegalStateException", "Unexpected JPEG XL decoder failure");
  }
  return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_s24cam_dng_JxlNative_encodeNative(JNIEnv* env, jclass, jshortArray samples,
                                        jint width, jint height, jint channels,
                                        jint bits, jfloat distance, jint effort,
                                        jint decoding_speed, jboolean linear_raw) {
  if (samples == nullptr) {
    Throw(env, "java/lang/NullPointerException", "samples");
    return nullptr;
  }
  if (linear_raw && (bits != 16 || distance != 0 ||
      effort != s24cam::kLinearRawEffort || decoding_speed != s24cam::kLinearRawDecodingSpeed)) {
    Throw(env, "java/lang/IllegalArgumentException", "Invalid LinearRaw JPEG XL profile");
    return nullptr;
  }
  if (width <= 0 || height <= 0 || (channels != 1 && channels != 3) ||
      bits < 1 || bits > 16 || !std::isfinite(distance) || distance < 0 ||
      distance > 25 || effort < 1 || effort > 10 || decoding_speed < 0 ||
      decoding_speed > 4) {
    Throw(env, "java/lang/IllegalArgumentException", "Invalid JPEG XL tile or settings");
    return nullptr;
  }
  const uint64_t count = static_cast<uint64_t>(width) * height * channels;
  if (count != static_cast<uint64_t>(env->GetArrayLength(samples))) {
    Throw(env, "java/lang/IllegalArgumentException", "Sample count must equal width*height*channels");
    return nullptr;
  }
  try {
    std::unique_ptr<JxlEncoder, decltype(&JxlEncoderDestroy)> enc(JxlEncoderCreate(nullptr),
                                                               JxlEncoderDestroy);
    if (!enc) throw std::bad_alloc();
    // No parallel runner: libjxl's default executes on this calling thread.
    Check(JxlEncoderUseContainer(enc.get(), JXL_FALSE), "JxlEncoderUseContainer");
    JxlBasicInfo info;
    JxlEncoderInitBasicInfo(&info);
    info.xsize = static_cast<uint32_t>(width);
    info.ysize = static_cast<uint32_t>(height);
    info.bits_per_sample = static_cast<uint32_t>(bits);
    info.exponent_bits_per_sample = 0;
    info.num_color_channels = static_cast<uint32_t>(channels);
    info.num_extra_channels = 0;
    info.alpha_bits = 0;
    info.uses_original_profile = distance == 0 ? JXL_TRUE : JXL_FALSE;
    Check(JxlEncoderSetBasicInfo(enc.get(), &info), "JxlEncoderSetBasicInfo");
    JxlColorEncoding color;
    JxlColorEncodingSetToLinearSRGB(&color, channels == 1 ? JXL_TRUE : JXL_FALSE);
    Check(JxlEncoderSetColorEncoding(enc.get(), &color), "JxlEncoderSetColorEncoding");
    JxlEncoderFrameSettings* frame = JxlEncoderFrameSettingsCreate(enc.get(), nullptr);
    if (frame == nullptr) throw std::bad_alloc();
    Check(JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_EFFORT, effort),
          "JxlEncoder effort");
    Check(JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_DECODING_SPEED,
                                          decoding_speed), "JxlEncoder decoding speed");
    Check(JxlEncoderSetFrameDistance(frame, distance), "JxlEncoderSetFrameDistance");
    if (distance == 0) {
      Check(JxlEncoderSetFrameLossless(frame, JXL_TRUE), "JxlEncoderSetFrameLossless");
      Check(JxlEncoderFrameSettingsSetOption(frame, JXL_ENC_FRAME_SETTING_MODULAR, 1),
            "JxlEncoder modular");
      if (linear_raw) {
        Check(s24cam::SetLinearRawOptions(frame), "JxlEncoder LinearRaw options");
      }
    }
    // Crucial for DNG: UINT16 samples are camera codes, not scaled to 0..65535.
    JxlBitDepth depth = {};
    depth.type = JXL_BIT_DEPTH_FROM_CODESTREAM;
    Check(JxlEncoderSetFrameBitDepth(frame, &depth), "JxlEncoderSetFrameBitDepth");
    const JxlPixelFormat format = {static_cast<uint32_t>(channels),
                                   bits <= 8 ? JXL_TYPE_UINT8 : JXL_TYPE_UINT16,
                                   JXL_NATIVE_ENDIAN, 0};
    {
      ShortElements input(env, samples);
      if (input.data() == nullptr) return nullptr;  // VM has raised OutOfMemoryError.
      const uint32_t limit = uint32_t{1} << bits;
      for (uint64_t i = 0; i < count; ++i) {
        if (input.data()[i] >= limit) {
          throw std::invalid_argument("Sample does not fit bits_per_sample");
        }
      }
      if (bits <= 8) {
        // libjxl requires UINT8 storage for <=8-bit unscaled integer input.
        std::vector<uint8_t> bytes(input.data(), input.data() + count);
        Check(JxlEncoderAddImageFrame(frame, &format, bytes.data(), bytes.size()),
              "JxlEncoderAddImageFrame");
      } else {
        Check(JxlEncoderAddImageFrame(frame, &format, input.data(),
                                      static_cast<size_t>(count) * sizeof(uint16_t)),
              "JxlEncoderAddImageFrame");
      }
    }
    JxlEncoderCloseInput(enc.get());
    std::vector<uint8_t> output(16384);
    size_t used = 0;
    for (;;) {
      uint8_t* next = output.data() + used;
      size_t available = output.size() - used;
      const JxlEncoderStatus status = JxlEncoderProcessOutput(enc.get(), &next, &available);
      used = output.size() - available;
      if (status == JXL_ENC_SUCCESS) break;
      if (status != JXL_ENC_NEED_MORE_OUTPUT) {
        throw std::runtime_error("JxlEncoderProcessOutput");
      }
      constexpr size_t max_size = static_cast<size_t>(std::numeric_limits<jsize>::max());
      if (output.size() > max_size / 2) throw std::bad_alloc();
      output.resize(output.size() * 2);
    }
    size_t offset, size;
    CodestreamRange(output, used, &offset, &size);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (result != nullptr) {
      env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                              reinterpret_cast<const jbyte*>(output.data() + offset));
    }
    return result;
  } catch (const std::invalid_argument& e) {
    Throw(env, "java/lang/IllegalArgumentException", e.what());
  } catch (const std::bad_alloc&) {
    Throw(env, "java/lang/OutOfMemoryError", "JPEG XL tile allocation failed");
  } catch (const std::exception& e) {
    Throw(env, "java/lang/IllegalStateException", e.what());
  } catch (...) {
    Throw(env, "java/lang/IllegalStateException", "Unexpected JPEG XL encoder failure");
  }
  return nullptr;
}
