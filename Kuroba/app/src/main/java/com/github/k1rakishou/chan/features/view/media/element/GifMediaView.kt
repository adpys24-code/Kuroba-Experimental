package com.github.k1rakishou.chan.features.view.media.element

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.cache.CacheFileType
import com.github.k1rakishou.chan.core.cache.downloader.CancelableDownload
import com.github.k1rakishou.chan.features.view.media.MediaLocation
import com.github.k1rakishou.chan.features.view.media.ViewableMedia
import com.github.k1rakishou.chan.features.view.media.helper.CloseMediaActionHelper
import com.github.k1rakishou.chan.features.view.media.helper.FullMediaAppearAnimationHelper
import com.github.k1rakishou.chan.features.view.media.strip.MediaViewerActionStrip
import com.github.k1rakishou.chan.features.view.media.strip.MediaViewerBottomActionStrip
import com.github.k1rakishou.chan.ui.view.CircularChunkedLoadingBar
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.setVisibilityFast
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.awaitCatching
import com.github.k1rakishou.common.errorMessageOrClassName
import com.github.k1rakishou.common.isCancellationException
import com.github.k1rakishou.common.isExceptionImportant
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.file.FileDescriptorMode
import com.github.k1rakishou.v2.KurobaSettings
import com.google.android.exoplayer2.upstream.DataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView
import java.io.File
import java.io.IOException

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class GifMediaView(
  context: Context,
  initialMediaViewState: GifMediaViewState,
  mediaViewContract: MediaViewContract,
  kurobaSettings: KurobaSettings,
  private val onThumbnailFullyLoadedFunc: () -> Unit,
  private val isSystemUiHidden: () -> Boolean,
  cachedHttpDataSourceFactory: DataSource.Factory,
  fileDataSourceFactory: DataSource.Factory,
  contentDataSourceFactory: DataSource.Factory,
  override val viewableMedia: ViewableMedia.Gif,
  override val pagerPosition: Int,
  override val totalPageItemsCount: Int
) : MediaView<ViewableMedia.Gif, GifMediaView.GifMediaViewState>(
  context = context,
  attributeSet = null,
  mediaViewContract = mediaViewContract,
  kurobaSettings = kurobaSettings,
  mediaViewState = initialMediaViewState,
  cachedHttpDataSourceFactory = cachedHttpDataSourceFactory,
  fileDataSourceFactory = fileDataSourceFactory,
  contentDataSourceFactory = contentDataSourceFactory,
) {

  private val movableContainer: FrameLayout
  private val thumbnailMediaView: ThumbnailMediaView
  private val actualGifView: GifImageView
  private val loadingBar: CircularChunkedLoadingBar
  private val actionStrip: MediaViewerActionStrip

  private val closeMediaActionHelper: CloseMediaActionHelper
  private val gestureDetector: GestureDetector
  private val scaleGestureDetector: ScaleGestureDetector?

  private var gifZoom = 1f
  private var gifOffsetX = 0f
  private var gifOffsetY = 0f
  private var lastPanX: Float? = null
  private var lastPanY: Float? = null
  private var pinchFocusX = 0f
  private var pinchFocusY = 0f
  private var zoomAtPinchStart = 1f
  private var spanAtPinchStart = 1f
  private var smoothedSpan = 1f
  private var smoothedPanX = 0f
  private var smoothedPanY = 0f
  private val zoomSpeedMultiplier: Float by lazy {
    kurobaSettings.mpv.videoZoomSpeed.readBlocking().toFloat()
  }
  private val panSpeedMultiplier: Float by lazy {
    kurobaSettings.mpv.videoPanSpeed.readBlocking().toFloat()
  }

  private var fullGifDeferred = CompletableDeferred<MediaPreloadResult>()
  private var preloadCancelableDownload: CancelableDownload? = null

  override val hasContent: Boolean
    get() = actualGifView.drawable != null
  override val mediaViewerActionStrip: MediaViewerActionStrip?
    get() = actionStrip

  init {
    AppModuleAndroidUtils.extractActivityComponent(context)
      .inject(this)

    inflate(context, R.layout.media_view_gif, this)
    setWillNotDraw(false)

    movableContainer = findViewById(R.id.movable_container)
    thumbnailMediaView = findViewById(R.id.thumbnail_media_view)
    actualGifView = findViewById(R.id.actual_gif_view)
    loadingBar = findViewById(R.id.loading_bar)

    if (AppModuleAndroidUtils.isTablet) {
      actionStrip = findViewById<MediaViewerBottomActionStrip?>(R.id.left_action_strip)
    } else {
      actionStrip = findViewById<MediaViewerBottomActionStrip?>(R.id.bottom_action_strip)
    }

    closeMediaActionHelper = CloseMediaActionHelper(
      context = context,
      themeEngine = themeEngine,
      requestDisallowInterceptTouchEvent = { this.parent.requestDisallowInterceptTouchEvent(true) },
      onAlphaAnimationProgress = { alpha -> mediaViewContract.changeMediaViewerBackgroundAlpha(alpha) },
      movableContainerFunc = { movableContainer },
      invalidateFunc = { invalidate() },
      closeMediaViewer = { mediaViewContract.closeMediaViewer() },
      topPaddingFunc = { toolbarHeight() },
      bottomPaddingFunc = { globalWindowInsetsManager.bottom() },
      topGestureInfo = createGestureAction(isTopGesture = true),
      bottomGestureInfo = createGestureAction(isTopGesture = false)
    )

    gestureDetector = GestureDetector(
      context,
      GestureDetectorListener(
        thumbnailMediaView = thumbnailMediaView,
        actualGifView = actualGifView,
        mediaViewContract = mediaViewContract,
        tryPreloadingFunc = {
          val canForcePreload = canPreload(forced = true)

          if (viewableMedia.mediaLocation is MediaLocation.Remote && canForcePreload) {
            scope.launch {
              preloadCancelableDownload = startFullMediaPreloading(
                forced = true,
                loadingBar = loadingBar,
                mediaLocationRemote = viewableMedia.mediaLocation,
                fullMediaDeferred = fullGifDeferred,
                onEndFunc = { preloadCancelableDownload = null }
              )
            }

            return@GestureDetectorListener true
          } else if (!canForcePreload) {
            mediaViewContract.onTapped()
            return@GestureDetectorListener true
          }

          return@GestureDetectorListener false
        },
        onMediaLongClick = { mediaViewContract.onMediaLongClick(this, viewableMedia) },
        isZoomed = { gifZoom > 1f },
        onDoubleTapWhenZoomed = {
          gifZoom = 1f; gifOffsetX = 0f; gifOffsetY = 0f
          lastPanX = null; lastPanY = null
          applyGifZoom()
        },
        pauseUnpauseGifFunc = {
          val isNowPlaying = (actualGifView.drawable as? GifDrawable)?.isPlaying?.not()
            ?: return@GestureDetectorListener

          onPauseUnpauseButtonToggled(isNowPlaying = isNowPlaying)
        }
      )
    )

    scaleGestureDetector = if (kurobaSettings.mpv.videoZoom.readBlocking()) {
      ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
          zoomAtPinchStart = gifZoom
          smoothedSpan = detector.currentSpan.coerceAtLeast(1f)
          spanAtPinchStart = smoothedSpan
          pinchFocusX = detector.focusX - actualGifView.width / 2f
          pinchFocusY = detector.focusY - actualGifView.height / 2f
          return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
          val zoomEmaWeight = (1f - 0.18f / zoomSpeedMultiplier).coerceAtMost(0.97f)
          smoothedSpan = smoothedSpan * zoomEmaWeight + detector.currentSpan * (1f - zoomEmaWeight)
          val spanRatio = (smoothedSpan / spanAtPinchStart).coerceAtLeast(0.01f)
          val zoomExp = 2.2 + (zoomSpeedMultiplier - 1.0) * 17.8 / 9.0
          val newZoom = (zoomAtPinchStart * Math.pow(spanRatio.toDouble(), zoomExp).toFloat()).coerceIn(1f, 8f)
          if (kotlin.math.abs(newZoom - gifZoom) < gifZoom * 0.005f) return true
          val effectiveScale = newZoom / gifZoom
          gifOffsetX = (gifOffsetX - pinchFocusX) * effectiveScale + pinchFocusX
          gifOffsetY = (gifOffsetY - pinchFocusY) * effectiveScale + pinchFocusY
          gifZoom = newZoom
          if (gifZoom <= 1f) {
            gifOffsetX = 0f; gifOffsetY = 0f
          } else {
            val maxOffsetX = actualGifView.width * (gifZoom - 1f) / 2f
            val maxOffsetY = actualGifView.height * (gifZoom - 1f) / 2f
            gifOffsetX = gifOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
            gifOffsetY = gifOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
          }
          applyGifZoom()
          return true
        }
      })
    } else {
      null
    }

    thumbnailMediaView.setOnTouchListener { v, event ->
      if (thumbnailMediaView.visibility != VISIBLE) {
        return@setOnTouchListener false
      }

      // Always return true for thumbnails because otherwise gestures won't work with thumbnails
      gestureDetector.onTouchEvent(event)
      return@setOnTouchListener true
    }

    actualGifView.setOnTouchListener { v, event ->
      if (actualGifView.visibility != VISIBLE) {
        return@setOnTouchListener false
      }

      if (scaleGestureDetector != null) {
        scaleGestureDetector.onTouchEvent(event)

        if (gifZoom > 1f) {
          parent.requestDisallowInterceptTouchEvent(true)
        }

        if (gifZoom > 1f && !scaleGestureDetector.isInProgress
          && event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_MOVE) {
          val curX = event.x
          val curY = event.y
          if (lastPanX == null) { smoothedPanX = curX; smoothedPanY = curY }
          val effectiveMultiplier = panSpeedMultiplier * gifZoom
          val panEmaWeight = (1f - 0.15f / effectiveMultiplier).coerceAtMost(0.99f)
          smoothedPanX = smoothedPanX * panEmaWeight + curX * (1f - panEmaWeight)
          smoothedPanY = smoothedPanY * panEmaWeight + curY * (1f - panEmaWeight)
          val dx = smoothedPanX - (lastPanX ?: smoothedPanX)
          val dy = smoothedPanY - (lastPanY ?: smoothedPanY)
          lastPanX = smoothedPanX
          lastPanY = smoothedPanY
          val maxOffsetX = actualGifView.width * (gifZoom - 1f) / 2f
          val maxOffsetY = actualGifView.height * (gifZoom - 1f) / 2f
          gifOffsetX = (gifOffsetX + dx * gifZoom * 5.0f * panSpeedMultiplier).coerceIn(-maxOffsetX, maxOffsetX)
          gifOffsetY = (gifOffsetY + dy * gifZoom * 5.0f * panSpeedMultiplier).coerceIn(-maxOffsetY, maxOffsetY)
          applyGifZoom()
        } else if (event.actionMasked == MotionEvent.ACTION_UP
          || event.actionMasked == MotionEvent.ACTION_CANCEL
          || event.pointerCount != 1) {
          lastPanX = null
          lastPanY = null
        }
      }

      if (scaleGestureDetector == null || !scaleGestureDetector.isInProgress) {
        gestureDetector.onTouchEvent(event)
      }

      return@setOnTouchListener true
    }
  }

  private fun applyGifZoom() {
    actualGifView.scaleX = gifZoom
    actualGifView.scaleY = gifZoom
    actualGifView.translationX = gifOffsetX
    actualGifView.translationY = gifOffsetY
  }

  override fun onInsetsChanged() {

  }

  override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
    if (gifZoom > 1f) return false
    if (ev != null && closeMediaActionHelper.onInterceptTouchEvent(ev)) {
      return true
    }

    return super.onInterceptTouchEvent(ev)
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (gifZoom > 1f) return false
    if (closeMediaActionHelper.onTouchEvent(event)) {
      return true
    }

    return super.onTouchEvent(event)
  }

  override fun draw(canvas: Canvas) {
    super.draw(canvas)
    closeMediaActionHelper.onDraw(canvas)
  }

  override fun updateTransparency(backgroundColor: Int?) {
    if (backgroundColor == null) {
      actualGifView.drawable?.colorFilter = null
    } else {
      actualGifView.drawable?.setColorFilter(
        backgroundColor,
        PorterDuff.Mode.DST_OVER
      )
    }
  }

  override fun preload() {
    thumbnailMediaView.bind(
      ThumbnailMediaView.ThumbnailMediaViewParameters(
        isOriginalMediaPlayable = true,
        viewableMedia = viewableMedia,
      ),
      onThumbnailFullyLoaded = {
        onThumbnailFullyLoadedFunc()
      }
    )

    if (viewableMedia.mediaLocation is MediaLocation.Remote && canPreload(forced = false)) {
      scope.launch {
        preloadCancelableDownload = startFullMediaPreloading(
          forced = false,
          loadingBar = loadingBar,
          mediaLocationRemote = viewableMedia.mediaLocation,
          fullMediaDeferred = fullGifDeferred,
          onEndFunc = { preloadCancelableDownload = null }
        )
      }
    } else if (viewableMedia.mediaLocation is MediaLocation.Local) {
      val mediaPreloadResult = MediaPreloadResult(
        filePath = FilePath.JavaPath(viewableMedia.mediaLocation.path),
        isForced = false
      )

      fullGifDeferred.complete(mediaPreloadResult)
    }
  }

  override fun bind() {

  }

  override fun show(isLifecycleChange: Boolean) {
    super.updateComponentsWithViewableMedia(pagerPosition, totalPageItemsCount, viewableMedia)
    onSystemUiVisibilityChanged(isSystemUiHidden())
    thumbnailMediaView.show()

    scope.launch {
      val fullGifDeferredResult = fullGifDeferred.awaitCatching()
      super.updateComponentsWithViewableMedia(pagerPosition, totalPageItemsCount, viewableMedia)

      when (fullGifDeferredResult) {
        is ModularResult.Error -> {
          val error = fullGifDeferredResult.error
          Logger.e(TAG, "onFullGifLoadingError()", error)

          if (error.isExceptionImportant() && shown) {
            snackbarManager.errorToast(
              message = AppModuleAndroidUtils.getString(R.string.image_failed_gif_error, error.errorMessageOrClassName())
            )
          }

          actualGifView.setVisibilityFast(INVISIBLE)
        }
        is ModularResult.Value -> {
          val mediaPreloadResult = fullGifDeferredResult.value

          if (!hasContent) {
            val filePath = mediaPreloadResult.filePath
            if (!setBigGifFromFile(filePath)) {
              return@launch
            }
          }

          withContext(Dispatchers.Default) {
            val fileSize = mediaPreloadResult.filePath.fileSize(fileManager)
            if (fileSize != null) {
              viewableMedia.viewableMediaMeta.mediaOnDiskSize = fileSize
            }
          }

          audioPlayerView?.loadAndPlaySoundPostAudioIfPossible(
            isLifecycleChange = isLifecycleChange,
            isForceLoad = fullGifDeferredResult.value.isForced,
            viewableMedia = viewableMedia
          )
        }
      }

      loadingBar.setVisibilityFast(GONE)

      onUpdateTransparency()

      val gifImageViewDrawable = actualGifView.drawable as? GifDrawable
      if (gifImageViewDrawable != null) {
        if (!isLifecycleChange && kurobaSettings.application.videoAlwaysResetToStart.read()) {
          mediaViewState.resetPosition()
        }

        val playing = mediaViewState.playing ?: true
        if (playing) {
          gifImageViewDrawable.start()
        } else {
          gifImageViewDrawable.pause()
        }

        gifImageViewDrawable.seekToFrame(mediaViewState.prevFrameIndex)
      }
    }
  }

  override fun hide(isLifecycleChange: Boolean, isPausing: Boolean, isBecomingInactive: Boolean) {
    thumbnailMediaView.hide()

    val gifImageViewDrawable = actualGifView.drawable as? GifDrawable
    if (gifImageViewDrawable != null) {
      mediaViewState.prevFrameIndex = gifImageViewDrawable.currentFrameIndex
      mediaViewState.playing = gifImageViewDrawable.isPlaying

      if (gifImageViewDrawable.isPlaying) {
        gifImageViewDrawable.pause()
      }
    }
  }

  override fun unbind() {
    closeMediaActionHelper.onDestroy()

    if (fullGifDeferred.isActive) {
      fullGifDeferred.cancel()
    }

    preloadCancelableDownload?.cancel()
    preloadCancelableDownload = null

    gifZoom = 1f; gifOffsetX = 0f; gifOffsetY = 0f
    actualGifView.scaleX = 1f; actualGifView.scaleY = 1f
    actualGifView.translationX = 0f; actualGifView.translationY = 0f

    thumbnailMediaView.unbind()
    actualGifView.setImageDrawable(null)
  }

  override suspend fun reloadMedia() {
    if (preloadCancelableDownload != null) {
      return
    }

    val mediaLocation = viewableMedia.mediaLocation
    if (mediaLocation !is MediaLocation.Remote) {
      return
    }

    cacheHandler.get().deleteCacheFileByUrlSuspend(
      cacheFileType = CacheFileType.PostMediaFull,
      url = mediaLocation.url.toString()
    )

    fullGifDeferred.cancel()
    fullGifDeferred = CompletableDeferred<MediaPreloadResult>()

    audioPlayerView?.pauseUnpause(isNowPaused = true)

    thumbnailMediaView.setVisibilityFast(VISIBLE)
    actualGifView.setVisibilityFast(INVISIBLE)
    actualGifView.setImageDrawable(null)

    preloadCancelableDownload = startFullMediaPreloading(
      forced = true,
      loadingBar = loadingBar,
      mediaLocationRemote = mediaLocation,
      fullMediaDeferred = fullGifDeferred,
      onEndFunc = { preloadCancelableDownload = null }
    )

    show(isLifecycleChange = false)
  }

  override fun onAudioPlayerPlaybackChanged(isNowPaused: Boolean) {
    pauseUnpauseGif(isNowPaused)
  }

  @Suppress("IfThenToSafeAccess")
  override fun onRewindPlayback() {
    val gifImageViewDrawable = actualGifView.drawable as? GifDrawable
    if (gifImageViewDrawable != null) {
      gifImageViewDrawable.seekTo(0)
    }
  }

  private fun onPauseUnpauseButtonToggled(isNowPlaying: Boolean) {
    pauseUnpauseGif(isNowPaused = !isNowPlaying)
    audioPlayerView?.pauseUnpause(isNowPaused = !isNowPlaying)
  }

  private fun pauseUnpauseGif(isNowPaused: Boolean) {
    val gifImageViewDrawable = actualGifView.drawable as? GifDrawable
    if (gifImageViewDrawable != null) {
      if (isNowPaused) {
        gifImageViewDrawable.pause()
      } else {
        gifImageViewDrawable.start()
      }
    }
  }

  private suspend fun setBigGifFromFile(filePath: FilePath): Boolean {
    return coroutineScope {
      val drawable = try {
        createGifDrawableSafe(filePath)
      } catch (e: Throwable) {
        if (e.isCancellationException()) {
          return@coroutineScope false
        }

        Logger.e(TAG, "Error while trying to set a gif file", e)

        if (shown) {
          snackbarManager.errorToast(message = "Failed to draw Gif. Error: ${e.message}")
        }

        return@coroutineScope false
      }

      if (drawable.numberOfFrames == 1) {
        val imageMedia = ViewableMedia.Image(
          mediaLocation = viewableMedia.mediaLocation,
          previewLocation = viewableMedia.previewLocation,
          spoilerLocation = viewableMedia.spoilerLocation,
          viewableMediaMeta = viewableMedia.viewableMediaMeta,
        )

        mediaViewContract.reloadAs(pagerPosition, imageMedia)
        return@coroutineScope false
      }

      drawable.pause()

      actualGifView.setImageDrawable(drawable)
      actualGifView.setOnClickListener(null)

      val animationAwaitable = CompletableDeferred<Unit>()

      val animatorSet = FullMediaAppearAnimationHelper.fullMediaAppearAnimation(
        prevActiveView = thumbnailMediaView,
        activeView = actualGifView,
        isSpoiler = viewableMedia.viewableMediaMeta.isSpoiler,
        onAnimationEnd = { animationAwaitable.complete(Unit) }
      )

      this@coroutineScope.coroutineContext[Job.Key]?.invokeOnCompletion {
        if (animatorSet == null) {
          return@invokeOnCompletion
        }

        animatorSet.end()
      }

      animationAwaitable.await()
      return@coroutineScope true
    }
  }

  private suspend fun createGifDrawableSafe(filePath: FilePath): GifDrawable {
    return withContext(Dispatchers.IO) {
      val gifDrawable = when (filePath) {
        is FilePath.JavaPath -> {
          GifDrawable(File(filePath.path))
        }
        is FilePath.UriPath -> {
          fileManager.fromUri(filePath.uri)
            ?.let { file ->
              return@let fileManager.withFileDescriptor(file, FileDescriptorMode.Read) { fd ->
                return@withFileDescriptor GifDrawable(fd)
              }
            }
            ?: throw IOException("Failed to open get input stream for file ${filePath.uri}")
        }
      }

      if (gifDrawable.allocationByteCount > MAX_GIF_SIZE) {
        throw GifIsTooBigException(gifDrawable.allocationByteCount)
      }

      return@withContext gifDrawable
    }
  }

  private fun canPreload(forced: Boolean): Boolean {
    if (forced) {
      return !fullGifDeferred.isCompleted
        && (preloadCancelableDownload == null || preloadCancelableDownload?.isRunning() == false)
    }

    return canAutoLoad(cacheFileType = CacheFileType.PostMediaFull)
      && !fullGifDeferred.isCompleted
      && (preloadCancelableDownload == null || preloadCancelableDownload?.isRunning() == false)
  }

  class GifMediaViewState(
    var prevFrameIndex: Int = 0,
    var playing: Boolean? = null,
    audioPlayerViewState: AudioPlayerView.AudioPlayerViewState = AudioPlayerView.AudioPlayerViewState()
  ) : MediaViewState(audioPlayerViewState) {

    override fun resetPosition() {
      super.resetPosition()

      prevFrameIndex = 0
      playing = null
      audioPlayerViewState!!.resetPosition()
    }

    override fun clone(): MediaViewState {
      return GifMediaViewState(
        prevFrameIndex = prevFrameIndex,
        playing = playing,
        audioPlayerViewState = audioPlayerViewState!!.clone() as AudioPlayerView.AudioPlayerViewState
      )
    }

    override fun updateFrom(other: MediaViewState?) {
      if (other !is GifMediaViewState) {
        return
      }

      prevFrameIndex = other.prevFrameIndex
      playing = other.playing
      audioPlayerViewState!!.updateFrom(other.audioPlayerViewState)
    }
  }

  class GestureDetectorListener(
    private val thumbnailMediaView: ThumbnailMediaView,
    private val actualGifView: GifImageView,
    private val mediaViewContract: MediaViewContract,
    private val tryPreloadingFunc: () -> Boolean,
    private val onMediaLongClick: () -> Unit,
    private val isZoomed: () -> Boolean,
    private val onDoubleTapWhenZoomed: () -> Unit,
    private val pauseUnpauseGifFunc: () -> Unit
  ) : GestureDetector.SimpleOnGestureListener() {

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
      if (actualGifView.visibility == VISIBLE) {
        mediaViewContract.onTapped()
        return true
      } else if (thumbnailMediaView.visibility == VISIBLE) {
        return tryPreloadingFunc()
      }

      return super.onSingleTapConfirmed(e)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
      if (e == null || actualGifView.visibility != VISIBLE) {
        return false
      }

      if (isZoomed()) {
        onDoubleTapWhenZoomed()
        return true
      }

      pauseUnpauseGifFunc()

      return super.onDoubleTap(e)
    }

    override fun onLongPress(e: MotionEvent) {
      onMediaLongClick()
    }
  }

  private class GifIsTooBigException(sizeInBytes: Long)
    : Exception("Gif is too big! (${sizeInBytes / (1024 * 1024)} MB)")

  companion object {
    private const val TAG = "GifMediaView"
    // 99 MB. Max - 1 just in case. The max allowed by Android Canvas is 100 MB.
    private const val MAX_GIF_SIZE = 99 * 1024 * 1024
  }
}