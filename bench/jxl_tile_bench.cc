#include <jxl/color_encoding.h>
#include <jxl/decode.h>
#include <jxl/encode.h>
#include "../app/src/main/cpp/jxl_raw_settings.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

using Clock = std::chrono::steady_clock;
struct Settings {
  std::string name;
  int effort, predictor, rct;
  float learning;
  int prev, group, global, local, palette, speed;
  int patches = -1;
};
void Check(bool ok, const char* what) { if (!ok) throw std::runtime_error(what); }
void Option(JxlEncoderFrameSettings* f, JxlEncoderFrameSettingId id, int value) {
  if (value != -1) Check(JxlEncoderFrameSettingsSetOption(f, id, value) == JXL_ENC_SUCCESS, "option");
}
std::vector<uint8_t> Encode(const std::vector<uint16_t>& raw, uint32_t size, const Settings& s) {
  std::unique_ptr<JxlEncoder, decltype(&JxlEncoderDestroy)> enc(JxlEncoderCreate(nullptr), JxlEncoderDestroy);
  Check(bool(enc), "encoder allocation");
  Check(JxlEncoderUseContainer(enc.get(), JXL_FALSE) == JXL_ENC_SUCCESS, "container");
  JxlBasicInfo info;
  JxlEncoderInitBasicInfo(&info);
  info.xsize = info.ysize = size;
  info.bits_per_sample = 16;
  info.num_color_channels = 3;
  info.uses_original_profile = JXL_TRUE;
  Check(JxlEncoderSetBasicInfo(enc.get(), &info) == JXL_ENC_SUCCESS, "basic info");
  JxlColorEncoding color;
  JxlColorEncodingSetToLinearSRGB(&color, JXL_FALSE);
  Check(JxlEncoderSetColorEncoding(enc.get(), &color) == JXL_ENC_SUCCESS, "color");
  auto* frame = JxlEncoderFrameSettingsCreate(enc.get(), nullptr);
  Check(frame != nullptr, "frame allocation");
  Option(frame, JXL_ENC_FRAME_SETTING_EFFORT, s.effort);
  Option(frame, JXL_ENC_FRAME_SETTING_DECODING_SPEED, s.speed);
  Check(JxlEncoderSetFrameDistance(frame, 0) == JXL_ENC_SUCCESS, "distance");
  Check(JxlEncoderSetFrameLossless(frame, JXL_TRUE) == JXL_ENC_SUCCESS, "lossless");
  Option(frame, JXL_ENC_FRAME_SETTING_MODULAR, 1);
  Option(frame, JXL_ENC_FRAME_SETTING_RESPONSIVE, 0);
  Option(frame, JXL_ENC_FRAME_SETTING_LOSSY_PALETTE, 0);
  Option(frame, JXL_ENC_FRAME_SETTING_MODULAR_PREDICTOR, s.predictor);
  Option(frame, JXL_ENC_FRAME_SETTING_MODULAR_COLOR_SPACE, s.rct);
  if (s.learning != -1) Check(JxlEncoderFrameSettingsSetFloatOption(frame,
      JXL_ENC_FRAME_SETTING_MODULAR_MA_TREE_LEARNING_PERCENT, s.learning) == JXL_ENC_SUCCESS, "learning");
  Option(frame, JXL_ENC_FRAME_SETTING_MODULAR_NB_PREV_CHANNELS, s.prev);
  Option(frame, JXL_ENC_FRAME_SETTING_MODULAR_GROUP_SIZE, s.group);
  if (s.global != -1) Check(JxlEncoderFrameSettingsSetFloatOption(frame,
      JXL_ENC_FRAME_SETTING_CHANNEL_COLORS_GLOBAL_PERCENT, float(s.global)) == JXL_ENC_SUCCESS, "global palette");
  if (s.local != -1) Check(JxlEncoderFrameSettingsSetFloatOption(frame,
      JXL_ENC_FRAME_SETTING_CHANNEL_COLORS_GROUP_PERCENT, float(s.local)) == JXL_ENC_SUCCESS, "local palette");
  Option(frame, JXL_ENC_FRAME_SETTING_PALETTE_COLORS, s.palette);
  Option(frame, JXL_ENC_FRAME_SETTING_PATCHES, s.patches);
  if (s.name == "app") Check(s24cam::SetLinearRawOptions(frame) == JXL_ENC_SUCCESS, "app profile");
  JxlBitDepth depth = {};
  depth.type = JXL_BIT_DEPTH_FROM_CODESTREAM;
  Check(JxlEncoderSetFrameBitDepth(frame, &depth) == JXL_ENC_SUCCESS, "input bit depth");
  const JxlPixelFormat format = {3, JXL_TYPE_UINT16, JXL_NATIVE_ENDIAN, 0};
  Check(JxlEncoderAddImageFrame(frame, &format, raw.data(), raw.size() * 2) == JXL_ENC_SUCCESS, "add frame");
  JxlEncoderCloseInput(enc.get());
  std::vector<uint8_t> output(16384);
  size_t used = 0;
  for (;;) {
    uint8_t* next = output.data() + used;
    size_t available = output.size() - used;
    auto status = JxlEncoderProcessOutput(enc.get(), &next, &available);
    used = output.size() - available;
    if (status == JXL_ENC_SUCCESS) break;
    Check(status == JXL_ENC_NEED_MORE_OUTPUT, "encode output");
    output.resize(output.size() * 2);
  }
  output.resize(used);
  return output;
}
uint64_t BE(const uint8_t* p, size_t n) {
  uint64_t v = 0; for (size_t i = 0; i < n; ++i) v = (v << 8) | p[i]; return v;
}
std::pair<size_t, size_t> Codestream(const std::vector<uint8_t>& v) {
  if (v.size() >= 2 && v[0] == 255 && v[1] == 10) return {0, v.size()};
  for (size_t p = 0; p + 8 <= v.size();) {
    uint64_t n = BE(v.data() + p, 4); size_t header = 8;
    if (n == 1) { Check(p + 16 <= v.size(), "box header"); n = BE(v.data()+p+8, 8); header = 16; }
    if (n == 0) n = v.size() - p;
    Check(n >= header && n <= v.size() - p, "box size");
    if (std::memcmp(v.data()+p+4, "jxlc", 4) == 0) return {p+header, size_t(n)-header};
    p += size_t(n);
  }
  throw std::runtime_error("no codestream");
}
std::vector<uint16_t> Decode(const uint8_t* data, size_t bytes, uint32_t width,
                             uint32_t height = 0, uint32_t channels = 3) {
  if (height == 0) height = width;
  std::unique_ptr<JxlDecoder, decltype(&JxlDecoderDestroy)> dec(JxlDecoderCreate(nullptr), JxlDecoderDestroy);
  Check(bool(dec), "decoder allocation");
  Check(JxlDecoderSubscribeEvents(dec.get(), JXL_DEC_BASIC_INFO | JXL_DEC_FULL_IMAGE) == JXL_DEC_SUCCESS, "events");
  Check(JxlDecoderSetInput(dec.get(), data, bytes) == JXL_DEC_SUCCESS, "decode input");
  JxlDecoderCloseInput(dec.get());
  std::vector<uint16_t> out(size_t(width) * height * channels);
  const JxlPixelFormat format = {channels, JXL_TYPE_UINT16, JXL_NATIVE_ENDIAN, 0};
  bool basic = false, image = false;
  for (;;) {
    auto status = JxlDecoderProcessInput(dec.get());
    if (status == JXL_DEC_SUCCESS) break;
    if (status == JXL_DEC_BASIC_INFO) {
      JxlBasicInfo info;
      Check(JxlDecoderGetBasicInfo(dec.get(), &info) == JXL_DEC_SUCCESS, "decode info");
      Check(info.bits_per_sample == 16 && info.xsize == width && info.ysize == height &&
            info.num_color_channels == channels && info.uses_original_profile, "16-bit original profile required");
      basic = true;
    } else if (status == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
      Check(JxlDecoderSetImageOutBuffer(dec.get(), &format, out.data(), out.size()*2) == JXL_DEC_SUCCESS, "decode buffer");
    } else if (status == JXL_DEC_FULL_IMAGE) image = true;
    else throw std::runtime_error("decode failure");
  }
  Check(basic && image, "incomplete decode");
  return out;
}
uint64_t ReadLE(std::istream& f, size_t n) {
  uint64_t v = 0;
  for (size_t i = 0; i < n; ++i) { int b = f.get(); Check(b != EOF, "short input"); v |= uint64_t(b) << (8*i); }
  return v;
}
std::vector<Settings> LoadSettings(const char* path) {
  std::ifstream f(path); Check(bool(f), "settings file");
  std::vector<Settings> all; std::string line;
  while (std::getline(f, line)) {
    if (line.empty() || line[0] == '#' || line.rfind("name,", 0) == 0) continue;
    std::replace(line.begin(), line.end(), ',', ' '); std::istringstream row(line); Settings s;
    Check(bool(row >> s.name >> s.effort >> s.predictor >> s.rct >> s.learning >> s.prev >> s.group
                   >> s.global >> s.local >> s.palette >> s.speed), "settings CSV row");
    row >> s.patches;  // Optional for original sweep CSVs.
    all.push_back(s);
  }
  Check(!all.empty(), "no settings"); return all;
}
int main(int argc, char** argv) {
  try {
    if (argc == 7 && std::string(argv[1]) == "--check") {
      std::ifstream encoded_file(argv[2], std::ios::binary), raw_file(argv[3], std::ios::binary);
      Check(bool(encoded_file) && bool(raw_file), "check files");
      std::vector<uint8_t> encoded((std::istreambuf_iterator<char>(encoded_file)), {});
      uint32_t w = std::stoul(argv[4]), h = std::stoul(argv[5]), c = std::stoul(argv[6]);
      Check(w > 0 && h > 0 && (c == 1 || c == 3), "check dimensions");
      std::vector<uint16_t> raw(size_t(w)*h*c);
      raw_file.read(reinterpret_cast<char*>(raw.data()), raw.size()*2);
      Check(bool(raw_file) && raw_file.peek() == EOF, "check raw size");
      auto stream = Codestream(encoded);
      Check(stream.first == 0 && Decode(encoded.data(), encoded.size(), w, h, c) == raw,
            "bare codestream / sample check");
      std::cout << "PASS: bare 16-bit codestream; every sample identical\n";
      return 0;
    }
    if (argc < 3 || argc > 5) throw std::runtime_error("usage: jxl_tile_bench tiles.raw settings.csv [repetitions=1] [tile_limit=all]");
    auto settings = LoadSettings(argv[2]);
    int repeats = argc >= 4 ? std::stoi(argv[3]) : 1; Check(repeats > 0, "repeats");
    std::ifstream input(argv[1], std::ios::binary); Check(bool(input), "raw input");
    char magic[8]; input.read(magic, 8); Check(std::memcmp(magic, "S24JXLT1", 8) == 0, "raw magic");
    uint32_t size = uint32_t(ReadLE(input, 4)), count = uint32_t(ReadLE(input, 4));
    uint32_t width = uint32_t(ReadLE(input, 4)), height = uint32_t(ReadLE(input, 4));
    uint64_t source_payload = ReadLE(input, 8), source_bytes = ReadLE(input, 8), full_count = ReadLE(input, 8);
    Check((size == 256 || size == 512) && count > 0 && source_bytes >= source_payload, "raw header");
    if (argc == 5) count = std::min(count, uint32_t(std::stoul(argv[4])));
    Check(count > 0, "tile limit");
    const auto data_start = input.tellg();
    std::vector<uint16_t> raw(size_t(size)*size*3);
    std::cerr << "libjxl " << JxlEncoderVersion() << "; single-threaded; 16-bit; " << count << " tiles; "
              << width << 'x' << height << "; " << repeats << " repetitions; all samples verified\n";
    std::cout << "name,tile,tiles,repetitions,bytes,estimated_dng_MB,encode_ms_MP,decode_ms_MP,effort,predictor,rct,learning,prev,group,global,local,palette,speed,patches\n";
    for (const auto& s : settings) {
      // Warm up codec dispatch/allocator with the real first tile. No parallel runner.
      input.clear(); input.seekg(data_start); input.read(reinterpret_cast<char*>(raw.data()), raw.size()*2);
      Check(bool(input), "warmup read");
      auto warm = Encode(raw, size, s); auto range = Codestream(warm);
      Check(Decode(warm.data()+range.first, range.second, size) == raw, "warmup mismatch");
      uint64_t bytes = 0; double enc_ms = 0, dec_ms = 0;
      for (int r = 0; r < repeats; ++r) {
        input.clear(); input.seekg(data_start);
        for (uint32_t i = 0; i < count; ++i) {
          input.read(reinterpret_cast<char*>(raw.data()), raw.size()*2); Check(bool(input), "tile read");
          const auto t0 = Clock::now(); auto encoded = Encode(raw, size, s); const auto t1 = Clock::now();
          auto stream = Codestream(encoded);
          const auto t2 = Clock::now(); auto decoded = Decode(encoded.data()+stream.first, stream.second, size); const auto t3 = Clock::now();
          Check(decoded == raw, "SAMPLE MISMATCH");
          bytes += stream.second;
          enc_ms += std::chrono::duration<double, std::milli>(t1-t0).count();
          dec_ms += std::chrono::duration<double, std::milli>(t3-t2).count();
        }
      }
      const double mp = double(count)*size*size*repeats/1e6;
      const double estimate = (double(bytes)/repeats/count*full_count + source_bytes-source_payload)/1e6;
      std::cout << std::fixed << std::setprecision(4) << s.name << ',' << size << ',' << count << ',' << repeats << ','
                << bytes/repeats << ',' << estimate << ',' << enc_ms/mp << ',' << dec_ms/mp << ',' << s.effort << ','
                << s.predictor << ',' << s.rct << ',' << s.learning << ',' << s.prev << ',' << s.group << ','
                << s.global << ',' << s.local << ',' << s.palette << ',' << s.speed << ',' << s.patches << std::endl;
    }
  } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
