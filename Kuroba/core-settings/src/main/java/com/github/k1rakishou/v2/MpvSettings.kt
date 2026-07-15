package com.github.k1rakishou.v2

import com.github.k1rakishou.v2.database.KurobaSettingsDatabase

class MpvSettings(
  database: KurobaSettingsDatabase,
  override val initialSettingsState: KurobaInitialSettingsState
) : BaseSettings(database) {
  override val backupable: Boolean = true
  val hardwareDecoding by lazy { createBooleanSetting(KurobaSettingKey.Mpv.HardwareDecoding, true) }
  val videoFastCode by lazy { createBooleanSetting(KurobaSettingKey.Mpv.VideoFastCode, false) }
  val gpuNextVO by lazy { createBooleanSetting(KurobaSettingKey.Mpv.GpuNextVO, false) }
  val lastMpvLibsUpdateCheckTime by lazy { createLongSetting(KurobaSettingKey.Mpv.LastMpvLibsUpdateCheckTime, 0L) }
  val controlBarButtonHeight by lazy { createRangeSetting(KurobaSettingKey.Mpv.ControlBarButtonHeight, default = 38, min = 20, max = 120) }
  val controlBarSeekbarHeight by lazy { createRangeSetting(KurobaSettingKey.Mpv.ControlBarSeekbarHeight, default = 36, min = 16, max = 100) }
  val controlBarTextSize by lazy { createRangeSetting(KurobaSettingKey.Mpv.ControlBarTextSize, default = 14, min = 8, max = 32) }
  val videoZoom by lazy { createBooleanSetting(KurobaSettingKey.Mpv.VideoZoom, false) }
  val videoZoomSpeed by lazy { createRangeSetting(KurobaSettingKey.Mpv.VideoZoomSpeed, default = 1, min = 1, max = 10) }
  val videoPanSpeed by lazy { createRangeSetting(KurobaSettingKey.Mpv.VideoPanSpeed, default = 1, min = 1, max = 10) }
  val longPressWhenZoomed by lazy { createBooleanSetting(KurobaSettingKey.Mpv.LongPressWhenZoomed, true) }
}