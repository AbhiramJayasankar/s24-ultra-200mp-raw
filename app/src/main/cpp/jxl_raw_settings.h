#ifndef S24CAM_JXL_RAW_SETTINGS_H_
#define S24CAM_JXL_RAW_SETTINGS_H_

#include <jxl/encode.h>

namespace s24cam {
// Measured on decoded keeper.dng codes. Keep the codestream at 16 bits; these
// options change reversible transforms/context modelling, never the samples.
// See bench/notes/jxl-tuning.md for timing, decode cost and scene limitations.
constexpr int kLinearRawEffort = 7;
constexpr int kLinearRawDecodingSpeed = 0;
inline JxlEncoderStatus SetLinearRawOptions(JxlEncoderFrameSettings* frame) {
  struct Option { JxlEncoderFrameSettingId id; int value; };
  const Option options[] = {
      {JXL_ENC_FRAME_SETTING_EFFORT, kLinearRawEffort},
      {JXL_ENC_FRAME_SETTING_DECODING_SPEED, kLinearRawDecodingSpeed},
      {JXL_ENC_FRAME_SETTING_RESPONSIVE, 0},
      {JXL_ENC_FRAME_SETTING_LOSSY_PALETTE, 0},
      {JXL_ENC_FRAME_SETTING_MODULAR_PREDICTOR, 5},
      {JXL_ENC_FRAME_SETTING_MODULAR_COLOR_SPACE, 40},
      {JXL_ENC_FRAME_SETTING_MODULAR_NB_PREV_CHANNELS, 2},
      {JXL_ENC_FRAME_SETTING_MODULAR_GROUP_SIZE, 1},
      {JXL_ENC_FRAME_SETTING_PALETTE_COLORS, 0},
      {JXL_ENC_FRAME_SETTING_PATCHES, 0},
  };
  for (const auto& option : options) {
    const auto status = JxlEncoderFrameSettingsSetOption(frame, option.id, option.value);
    if (status != JXL_ENC_SUCCESS) return status;
  }
  const JxlEncoderFrameSettingId zero_percent[] = {
      JXL_ENC_FRAME_SETTING_CHANNEL_COLORS_GLOBAL_PERCENT,
      JXL_ENC_FRAME_SETTING_CHANNEL_COLORS_GROUP_PERCENT};
  for (const auto id : zero_percent) {
    const auto status = JxlEncoderFrameSettingsSetFloatOption(frame, id, 0);
    if (status != JXL_ENC_SUCCESS) return status;
  }
  return JxlEncoderFrameSettingsSetFloatOption(
      frame, JXL_ENC_FRAME_SETTING_MODULAR_MA_TREE_LEARNING_PERCENT, 1.0f);
}
}  // namespace s24cam
#endif  // S24CAM_JXL_RAW_SETTINGS_H_
