package com.github.k1rakishou.chan.features.reply.converter

import android.content.Context
import android.os.Build
import coil.size.Scale
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.github.k1rakishou.chan.core.image.ImageLoaderDeprecated
import com.github.k1rakishou.chan.core.manager.ReplyManager
import com.github.k1rakishou.chan.core.site.loader.ClientException
import com.github.k1rakishou.chan.features.reply.data.SyntheticFileId
import com.github.k1rakishou.chan.features.reply.data.SyntheticReplyAttachableState
import com.github.k1rakishou.chan.ui.helper.picker.ImagePickHelper
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.StringUtils
import com.github.k1rakishou.core_logger.Logger
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class MediaConversionHelper(
  private val appContext: Context,
  private val appConstantsLazy: Lazy<AppConstants>,
  private val replyManagerLazy: Lazy<ReplyManager>,
  private val imageLoaderDeprecatedLazy: Lazy<ImageLoaderDeprecated>,
  private val imagePickHelperLazy: Lazy<ImagePickHelper>
) {
  private val appConstants: AppConstants
    get() = appConstantsLazy.get()
  private val replyManager: ReplyManager
    get() = replyManagerLazy.get()
  private val imageLoaderDeprecated: ImageLoaderDeprecated
    get() = imageLoaderDeprecatedLazy.get()
  private val imagePickHelper: ImagePickHelper
    get() = imagePickHelperLazy.get()

  // Source file UUID -> ffmpeg session id of the currently running conversion
  private val activeConversions = ConcurrentHashMap<UUID, Long>()

  fun isConversionSupported(): Boolean {
    // ffmpeg-kit native libraries are built against API 24
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
  }

  fun isFileConvertable(fileName: String): Boolean {
    val extension = StringUtils.extractFileNameExtension(fileName)?.lowercase()
      ?: return false

    return extension in CONVERTABLE_EXTENSIONS
  }

  fun isConversionRunning(fileUuid: UUID): Boolean {
    return activeConversions.containsKey(fileUuid)
  }

  fun cancelConversion(fileUuid: UUID) {
    val sessionId = activeConversions[fileUuid]
    if (sessionId != null) {
      Logger.debug(TAG) { "cancelConversion($fileUuid) cancelling ffmpeg session $sessionId" }
      FFmpegKit.cancel(sessionId)
    }
  }

  suspend fun convertMediaFile(
    fileUuid: UUID,
    preset: MediaConversionPreset
  ): ModularResult<UUID> {
    return withContext(Dispatchers.IO) {
      ModularResult.Try { convertMediaFileInternal(fileUuid, preset) }
    }
  }

  private suspend fun convertMediaFileInternal(
    fileUuid: UUID,
    preset: MediaConversionPreset
  ): UUID {
    if (!isConversionSupported()) {
      throw ClientException("Media conversion requires Android 7.0+")
    }

    if (activeConversions.containsKey(fileUuid)) {
      throw ClientException("This file is already being converted")
    }

    val sourceReplyFile = replyManager.getReplyFileByFileUuid(fileUuid).unwrap()
      ?: throw ClientException("Reply file with UUID '$fileUuid' does not exist")

    val sourceMeta = sourceReplyFile.getReplyFileMeta().unwrap()
    val sourceFile = sourceReplyFile.fileOnDisk

    if (!sourceFile.exists() || sourceFile.length() <= 0L) {
      throw ClientException("Source file does not exist or is empty")
    }

    val outputFileName = buildOutputFileName(sourceMeta.fileName, preset)

    val outputReplyFile = replyManager.createNewEmptyAttachFile(
      uniqueFileName = replyManager.generateUniqueFileName(appConstants),
      originalFileName = outputFileName,
      addedOn = System.currentTimeMillis()
    ) ?: throw ClientException("Failed to create attach file for the conversion output")

    val outputMeta = outputReplyFile.getReplyFileMeta().unwrap()
    val syntheticFileId = SyntheticFileId.FilePath(outputReplyFile.fileOnDisk.absolutePath)

    try {
      imagePickHelper.emitSyntheticReplyAttachable(
        id = syntheticFileId,
        state = SyntheticReplyAttachableState.Converting(progressPercent = -1)
      )

      val durationMs = probeDurationMs(sourceFile)

      runFFmpegConversion(
        fileUuid = fileUuid,
        sourceFile = sourceFile,
        outputFile = outputReplyFile.fileOnDisk,
        preset = preset,
        durationMs = durationMs,
        syntheticFileId = syntheticFileId
      )

      if (!outputReplyFile.fileOnDisk.exists() || outputReplyFile.fileOnDisk.length() <= 0L) {
        throw ClientException("Conversion produced an empty file")
      }

      imagePickHelper.emitSyntheticReplyAttachable(
        id = syntheticFileId,
        state = SyntheticReplyAttachableState.Decoding
      )

      if (!replyManager.addNewReplyFileIntoStorage(outputReplyFile, false)) {
        throw ClientException("Failed to add the converted file into reply storage")
      }

      val previewSuccess = imageLoaderDeprecated.calculateFilePreviewAndStoreOnDisk(
        context = appContext,
        fileUuid = outputMeta.fileUuid,
        scale = Scale.FIT
      )

      if (!previewSuccess) {
        Logger.error(TAG) {
          "convertMediaFileInternal($fileUuid, $preset) failed to calculate file preview"
        }
      }

      imagePickHelper.notifyNewFilesPicked(listOf(outputMeta.fileUuid))

      Logger.debug(TAG) {
        "convertMediaFileInternal($fileUuid, $preset) success, " +
          "output file: '${outputFileName}' (${outputReplyFile.fileOnDisk.length()} bytes)"
      }

      return outputMeta.fileUuid
    } catch (error: Throwable) {
      replyManager.deleteFile(fileUuid = outputMeta.fileUuid, notifyListeners = false).ignore()
      outputReplyFile.deleteFromDisk()
      throw error
    } finally {
      imagePickHelper.emitSyntheticReplyAttachable(
        id = syntheticFileId,
        state = SyntheticReplyAttachableState.Done
      )
    }
  }

  private suspend fun runFFmpegConversion(
    fileUuid: UUID,
    sourceFile: File,
    outputFile: File,
    preset: MediaConversionPreset,
    durationMs: Long?,
    syntheticFileId: SyntheticFileId
  ) {
    val ffmpegArgs = mutableListOf<String>().apply {
      add("-y")
      add("-i")
      add(sourceFile.absolutePath)
      addAll(preset.ffmpegArgs)
      // Attach file names on disk have no extension so the muxer must be set explicitly
      add("-f")
      add(preset.containerFormat)
      add(outputFile.absolutePath)
    }

    Logger.debug(TAG) { "runFFmpegConversion($fileUuid) ffmpeg ${ffmpegArgs.joinToString(" ")}" }

    val session = suspendCancellableCoroutine<FFmpegSession> { continuation ->
      var lastEmittedPercent = -1

      val session = FFmpegKit.executeWithArgumentsAsync(
        ffmpegArgs.toTypedArray(),
        { completedSession ->
          activeConversions.remove(fileUuid)

          if (!continuation.isCompleted) {
            continuation.resume(completedSession)
          }
        },
        null,
        { statistics ->
          if (durationMs != null && durationMs > 0) {
            val timeMs = statistics.time
            val percent = ((timeMs / durationMs.toDouble()) * 100.0).toInt().coerceIn(0, 100)

            if (percent != lastEmittedPercent) {
              lastEmittedPercent = percent

              imagePickHelper.emitSyntheticReplyAttachable(
                id = syntheticFileId,
                state = SyntheticReplyAttachableState.Converting(progressPercent = percent)
              )
            }
          }
        }
      )

      activeConversions[fileUuid] = session.sessionId

      continuation.invokeOnCancellation {
        activeConversions.remove(fileUuid)
        FFmpegKit.cancel(session.sessionId)
      }
    }

    val returnCode = session.returnCode

    when {
      ReturnCode.isSuccess(returnCode) -> {
        // no-op
      }
      ReturnCode.isCancel(returnCode) -> {
        throw ClientException("Conversion was cancelled")
      }
      else -> {
        val failReason = session.allLogsAsString
          ?.lines()
          ?.takeLast(5)
          ?.joinToString("\n")
          ?: "Unknown error"

        Logger.error(TAG) { "runFFmpegConversion($fileUuid) ffmpeg failed, logs:\n${failReason}" }
        throw ClientException("ffmpeg failed with code ${returnCode?.value}:\n${failReason}")
      }
    }
  }

  private fun probeDurationMs(sourceFile: File): Long? {
    return try {
      val mediaInformation = FFprobeKit.getMediaInformation(sourceFile.absolutePath)
        ?.mediaInformation

      val durationSeconds = mediaInformation?.duration?.toDoubleOrNull()
      if (durationSeconds != null && durationSeconds > 0.0) {
        (durationSeconds * 1000.0).toLong()
      } else {
        null
      }
    } catch (error: Throwable) {
      Logger.error(TAG) { "probeDurationMs() failed to probe '${sourceFile.absolutePath}': ${error}" }
      null
    }
  }

  private fun buildOutputFileName(sourceFileName: String, preset: MediaConversionPreset): String {
    val baseName = StringUtils.removeExtensionFromFileName(sourceFileName)
    val sourceExtension = StringUtils.extractFileNameExtension(sourceFileName)?.lowercase()

    return if (sourceExtension == preset.outputExtension) {
      "${baseName}_reencoded.${preset.outputExtension}"
    } else {
      "${baseName}.${preset.outputExtension}"
    }
  }

  companion object {
    private const val TAG = "MediaConversionHelper"

    private val CONVERTABLE_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "gif")
  }
}

enum class MediaConversionPreset(
  val outputExtension: String,
  val containerFormat: String,
  val ffmpegArgs: List<String>
) {
  ToWebmVp9(
    outputExtension = "webm",
    containerFormat = "webm",
    ffmpegArgs = listOf(
      "-c:v", "libvpx-vp9",
      "-b:v", "0",
      "-crf", "34",
      "-row-mt", "1",
      "-cpu-used", "4",
      "-pix_fmt", "yuv420p",
      "-c:a", "libopus",
      "-b:a", "96k",
      "-sn", "-dn",
      "-map_metadata", "-1"
    )
  ),
  ToWebmVp8(
    outputExtension = "webm",
    containerFormat = "webm",
    ffmpegArgs = listOf(
      "-c:v", "libvpx",
      "-crf", "12",
      "-b:v", "2M",
      "-deadline", "good",
      "-cpu-used", "4",
      "-pix_fmt", "yuv420p",
      "-c:a", "libvorbis",
      "-q:a", "4",
      "-sn", "-dn",
      "-map_metadata", "-1"
    )
  ),
  ToMp4H264(
    outputExtension = "mp4",
    containerFormat = "mp4",
    ffmpegArgs = listOf(
      "-c:v", "libx264",
      "-crf", "23",
      "-preset", "veryfast",
      "-pix_fmt", "yuv420p",
      "-movflags", "+faststart",
      "-c:a", "aac",
      "-b:a", "128k",
      "-sn", "-dn",
      "-map_metadata", "-1"
    )
  )
}
