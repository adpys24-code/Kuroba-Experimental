package com.github.k1rakishou.chan.features.settings.screen

import android.content.Context
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.helper.DialogFactory
import com.github.k1rakishou.chan.features.settings.SettingsScreen
import com.github.k1rakishou.chan.features.settings.setting.SettingUiElement
import com.github.k1rakishou.chan.ui.helper.AppResources
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.v2.KurobaSettings

class ExperimentalSettingsScreenBuilder(
  private val kurobaSettings: KurobaSettings,
  private val appResources: AppResources,
  private val appConstants: AppConstants
) : SettingsScreenBuilder {

  override suspend fun build(
    context: Context,
    settingActions: SettingActions,
    settingsScreen: SettingsScreen
  ) {
    with(settingsScreen) {
      buildMainSettingsGroup(context)
    }
  }

  private suspend fun SettingsScreen.buildMainSettingsGroup(
    context: Context,
  ) {
    addGroup(
      key = "main",
      title = appResources.string(R.string.experimental_settings_group)
    ) {
      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_allow_okhttp_ipv6) },
          description = { appResources.string(R.string.setting_allow_okhttp_http2_ipv6_description) },
          setting = kurobaSettings.application.okHttpAllowIpv6,
          requiresAppRestart = true
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_allow_okhttp_use_dns_over_https) },
          setting = kurobaSettings.application.okHttpUseDnsOverHttps,
          requiresAppRestart = true
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_auto_load_thread_images) },
          description = { appResources.string(R.string.setting_auto_load_thread_images_description) },
          setting = kurobaSettings.application.prefetchMedia,
          requiresAppRestart = true,
          badges = listOf(
            SettingUiElement.Badge.Dangerous(
              text = appResources.string(R.string.site_settings_experimental_warning),
              description = appResources.string(R.string.site_settings_experimental_warning_prefetch)
            )
          )
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_images_high_res) },
          description = { appResources.string(R.string.setting_images_high_res_description) },
          setting = kurobaSettings.application.highResCells,
          requiresAppRestart = true,
          badges = listOf(
            SettingUiElement.Badge.Dangerous(
              text = appResources.string(R.string.site_settings_experimental_warning),
              description = appResources.string(R.string.site_settings_experimental_warning_high_res_cells)
            )
          )
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_high_res_gif_cells) },
          description = { appResources.string(R.string.setting_high_res_gif_cells_description) },
          setting = kurobaSettings.application.highResGifCells,
          requiresAppRestart = true,
          badges = listOf(
            SettingUiElement.Badge.Dangerous(
              text = appResources.string(R.string.site_settings_experimental_warning),
              description = appResources.string(R.string.site_settings_experimental_warning_high_res_cells)
            )
          )
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_high_res_mp4_cells) },
          description = { appResources.string(R.string.setting_high_res_mp4_cells_description) },
          setting = kurobaSettings.application.highResMp4Cells,
          requiresAppRestart = true,
          badges = listOf(
            SettingUiElement.Badge.Dangerous(
              text = appResources.string(R.string.site_settings_experimental_warning),
              description = appResources.string(R.string.site_settings_experimental_warning_high_res_cells)
            )
          )
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_high_res_webm_cells) },
          description = { appResources.string(R.string.setting_high_res_webm_cells_description) },
          setting = kurobaSettings.application.highResWebmCells,
          requiresAppRestart = true,
          badges = listOf(
            SettingUiElement.Badge.Dangerous(
              text = appResources.string(R.string.site_settings_experimental_warning),
              description = appResources.string(R.string.site_settings_experimental_warning_high_res_cells)
            )
          )
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_high_res_video_cells_autoplay) },
          description = { appResources.string(R.string.setting_high_res_video_cells_autoplay_description) },
          setting = kurobaSettings.application.highResVideoCellsAutoplay,
          requiresAppRestart = false
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_use_legacy_board_dropdown) },
          description = { appResources.string(R.string.setting_use_legacy_board_dropdown_description) },
          setting = kurobaSettings.application.useLegacyBoardDropdown,
          requiresAppRestart = false
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_gallery_pinch_to_zoom) },
          description = { appResources.string(R.string.setting_gallery_pinch_to_zoom_description) },
          setting = kurobaSettings.application.galleryPinchToZoom,
          requiresAppRestart = false
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_album_video_zoom) },
          description = { appResources.string(R.string.setting_album_video_zoom_description) },
          setting = kurobaSettings.application.albumVideoZoom,
          requiresAppRestart = false
        )
      )

      addSetting(
        SettingUiElement.Range(
          title = { appResources.string(R.string.setting_gallery_max_autoplays) },
          description = { appResources.string(R.string.setting_gallery_max_autoplays_description) },
          setting = kurobaSettings.application.galleryMaxAutoplays,
          valueFormatter = { value -> value.toString() },
          requiresAppRestart = false,
          dependencies = listOf(kurobaSettings.application.highResVideoCellsAutoplay)
        )
      )

      addSetting(
        SettingUiElement.Bool(
          title = { appResources.string(R.string.setting_update_colors_for_text_selection_cursor) },
          description = { appResources.string(R.string.setting_update_colors_for_text_selection_cursor_description) },
          setting = kurobaSettings.application.colorizeTextSelectionCursors,
          requiresAppRestart = true
        )
      )

      addSetting(
        SettingUiElement.Input(
          title = { appResources.string(R.string.setting_custom_user_agent) },
          description = {
            val current = kurobaSettings.application.customUserAgent.read()
            buildString {
              if (current.isNotEmpty()) {
                append('\'')
                append(current)
                append('\'')
                appendLine()
                appendLine()
              }
              append(
                appResources.string(
                  R.string.setting_custom_user_agent_description,
                  appConstants.actualWebViewUserAgent(context)
                )
              )
            }
          },
          dialogInputType = DialogFactory.DialogInputType.String,
          setting = kurobaSettings.application.customUserAgent,
          requiresAppRestart = true,
          badges = listOf(
            SettingUiElement.Badge.Dangerous(
              text = appResources.string(R.string.site_settings_experimental_warning),
              description = appResources.string(R.string.site_settings_experimental_warning_custom_user_agent)
            )
          )
        )
      )
    }
  }
}