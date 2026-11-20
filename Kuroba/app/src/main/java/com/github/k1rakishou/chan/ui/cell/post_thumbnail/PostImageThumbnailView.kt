package com.github.k1rakishou.chan.ui.cell.post_thumbnail

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.text.TextUtils
import android.util.AttributeSet
import android.view.TextureView
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.addListener
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.cache.CacheFileType
import com.github.k1rakishou.chan.core.cache.CacheHandler
import com.github.k1rakishou.chan.core.concurrency.KurobaCoroutineScope
import com.github.k1rakishou.chan.core.image.ImageLoaderDeprecated
import com.github.k1rakishou.chan.core.manager.PrefetchState
import com.github.k1rakishou.chan.core.manager.PrefetchState.PrefetchCompleted
import com.github.k1rakishou.chan.core.manager.PrefetchState.PrefetchProgress
import com.github.k1rakishou.chan.core.manager.PrefetchState.PrefetchStarted
import com.github.k1rakishou.chan.core.manager.CurrentOpenedDescriptorStateManager
import com.github.k1rakishou.chan.core.manager.PrefetchStateManager
import com.github.k1rakishou.chan.core.manager.RevealedSpoilerImagesManager
import com.github.k1rakishou.chan.core.manager.ThirdEyeManager
import com.github.k1rakishou.chan.features.album.activeVideoPlayerCount
import com.github.k1rakishou.chan.features.view.media.MediaViewerControllerViewModel.Companion.canAutoLoad
import com.github.k1rakishou.chan.ui.cell.PostCellData
import com.github.k1rakishou.chan.ui.view.SegmentedCircleDrawable
import com.github.k1rakishou.chan.ui.view.ThumbnailView
import com.github.k1rakishou.chan.ui.view.ThumbnailView.ThumbnailViewOptions
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.dp
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.getDrawable
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.getString
import com.github.k1rakishou.chan.utils.MediaUtils
import com.github.k1rakishou.chan.utils.setOnThrottlingClickListener
import com.github.k1rakishou.chan.utils.setOnThrottlingLongClickListener
import com.github.k1rakishou.common.updatePaddings
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.core_themes.ThemeEngine
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.post.ChanPostImageType
import com.github.k1rakishou.v2.KurobaSettings
import com.github.k1rakishou.v2.parameters.PostThumbnailScaling
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.video.VideoSize
import com.github.k1rakishou.v2.parameters.BoardPostViewMode
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import javax.inject.Inject

class PostImageThumbnailView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle), PostImageThumbnailViewContract {

  @Inject
  lateinit var kurobaSettings: KurobaSettings
  @Inject
  lateinit var prefetchStateManagerLazy: Lazy<PrefetchStateManager>
  @Inject
  lateinit var thirdEyeManagerLazy: Lazy<ThirdEyeManager>
  @Inject
  lateinit var themeEngineLazy: Lazy<ThemeEngine>
  @Inject
  lateinit var cacheHandlerLazy: Lazy<CacheHandler>
  @Inject
  lateinit var revealedSpoilerImagesManagerLazy: Lazy<RevealedSpoilerImagesManager>
  @Inject
  lateinit var currentOpenedDescriptorStateManagerLazy: Lazy<CurrentOpenedDescriptorStateManager>

  private val videoScope = KurobaCoroutineScope()
  private var videoPlayer: SimpleExoPlayer? = null
  private var videoTextureView: TextureView? = null
  private var videoPlayerCounted = false

  private val scope = KurobaCoroutineScope()
  private val thirdEyeIcon by lazy { getDrawable(R.drawable.ic_baseline_eye_24).mutate() }

  private var postImage: ChanPostImage? = null
  private var canUseHighResCells: Boolean = false
  private var thumbnailViewOptions: ThumbnailViewOptions? = null

  private val thumbnail: ThumbnailView
  private val thumbnailOmittedFilesCountContainer: FrameLayout
  private val thumbnailOmittedFilesCount: TextView
  private var ratio = 0f
  private var prefetchingEnabled = false
  private var showPrefetchLoadingIndicator = false
  private var prefetching = false
  private val playIconBounds = Rect()
  private val thirdEyeIconBounds = Rect()
  private val circularProgressDrawableBounds = Rect()
  private var segmentedCircleDrawable: SegmentedCircleDrawable? = null
  private var hasThirdEyeImageMaybe: Boolean = false
  private var nsfwMode: Boolean = false
  private var alphaAnimator: ValueAnimator? = null

  private val prefetchStateManager: PrefetchStateManager
    get() = prefetchStateManagerLazy.get()
  private val thirdEyeManager: ThirdEyeManager
    get() = thirdEyeManagerLazy.get()
  private val themeEngine: ThemeEngine
    get() = themeEngineLazy.get()
  private val cacheHandler: CacheHandler
    get() = cacheHandlerLazy.get()
  private val revealedSpoilerImagesManager: RevealedSpoilerImagesManager
    get() = revealedSpoilerImagesManagerLazy.get()

  init {
    if (!isInEditMode) {
      AppModuleAndroidUtils.extractActivityComponent(getContext())
        .inject(this)
      setWillNotDraw(false)
    }

    inflate(context, R.layout.post_image_thumbnail_view, this)
    thumbnail = findViewById(R.id.thumbnail_view)
    thumbnailOmittedFilesCountContainer = findViewById(R.id.thumbnail_omitted_files_count_container)
    thumbnailOmittedFilesCount = findViewById(R.id.thumbnail_omitted_files_count)
  }

  fun imageUrl(): String? {
    return thumbnail.imageUrl
  }

  override fun bindPostImage(
    postImage: ChanPostImage,
    canUseHighResCells: Boolean,
    thumbnailViewOptions: ThumbnailViewOptions
  ) {
    if (this.postImage != null) {
      unbindPostImage()
    }

    this.nsfwMode = kurobaSettings.application.globalNsfwMode.readBlocking()
    this.prefetchingEnabled = kurobaSettings.application.prefetchMedia.readBlocking()

    listenForNsfwSettingUpdates()
    listenForThirdEyeUpdates(postImage)
    listenForRevealedSpoilers()

    if (prefetchingEnabled) {
      listenForPrefetchEvents(postImage)
    }

    bindPostImage(
      postImage = postImage,
      canUseHighResCells = canUseHighResCells,
      thumbnailViewOptions = thumbnailViewOptions,
      forcedAfterPrefetchFinished = false
    )
  }

  override fun unbindPostImage() {
    unbindVideoPlayer()

    postImage = null
    thumbnailViewOptions = null
    canUseHighResCells = false
    hasThirdEyeImageMaybe = false

    runOrStopGlowAnimation(stop = true)

    thumbnail.unbindImageUrl()
    scope.cancelChildren()
  }

  override fun getViewId(): Int {
    return this.id
  }

  override fun setViewId(id: Int) {
    this.id = id
  }

  override fun getThumbnailView(): ThumbnailView {
    return thumbnail
  }

  override fun equalUrls(chanPostImage: ChanPostImage): Boolean {
    return postImage?.equalUrl(chanPostImage) == true
  }

  override fun setImageClickable(clickable: Boolean) {
    isClickable = clickable
  }

  override fun setImageLongClickable(longClickable: Boolean) {
    isLongClickable = longClickable
  }

  override fun setImageClickListener(token: String, listener: OnClickListener?) {
    this.setOnThrottlingClickListener(token, listener)
  }

  override fun setImageLongClickListener(token: String, listener: OnLongClickListener?) {
    this.setOnThrottlingLongClickListener(token, listener)
  }

  override fun setImageOmittedFilesClickListener(token: String, listener: OnClickListener?) {
    thumbnailOmittedFilesCount.setOnThrottlingClickListener(token, listener)
  }

  fun onThumbnailViewClicked(listener: OnClickListener) {
    thumbnail.onThumbnailViewClicked(listener)
  }

  fun onThumbnailViewLongClicked(listener: OnLongClickListener): Boolean {
    return thumbnail.onThumbnailViewLongClicked(listener)
  }

  private fun listenForPrefetchEvents(postImage: ChanPostImage) {
    scope.launch {
      prefetchStateManager.prefetchEventFlow
        .filter { prefetchState -> prefetchState.postImage.equalUrl(postImage) }
        .collectLatest { prefetchState -> onPrefetchStateChanged(prefetchState) }
    }
  }

  private fun listenForNsfwSettingUpdates() {
    scope.launch {
      kurobaSettings.application.globalNsfwMode.listen()
        .collect { isNsfwModeEnabled ->
          if (nsfwMode != isNsfwModeEnabled) {
            nsfwMode = isNsfwModeEnabled
            invalidate()
          }
        }
    }
  }

  private fun listenForRevealedSpoilers() {
    scope.launch {
      val currentChanPostImage = postImage
      if (currentChanPostImage != null) {
        val isRevealed = revealedSpoilerImagesManager.isImageSpoilerImageRevealed(currentChanPostImage)
        if (isRevealed) {
          processSpoilerImageRevealEvent(
            revealedImagePostDescriptor = currentChanPostImage.ownerPostDescriptor,
            revealedImageFullUrl = currentChanPostImage.imageUrl
          )
        }
      }

      revealedSpoilerImagesManager.spoilerImageRevealEventsFlow
        .onEach { revealedSpoilerImage ->
          processSpoilerImageRevealEvent(
            revealedImagePostDescriptor = revealedSpoilerImage.postDescriptor,
            revealedImageFullUrl = revealedSpoilerImage.fullImageUrl
          )
        }
        .collect()
    }
  }

  private suspend fun processSpoilerImageRevealEvent(
    revealedImagePostDescriptor: PostDescriptor,
    revealedImageFullUrl: HttpUrl?
  ) {
    val currentChanPostImage = postImage
    if (currentChanPostImage == null) {
      return
    }

    val currentThumbnailViewOptions = thumbnailViewOptions
    if (currentThumbnailViewOptions == null) {
      return
    }

    if (currentThumbnailViewOptions.revealSpoilerImage) {
      return
    }

    val currentCanUseHighResCells = canUseHighResCells

    if (revealedImagePostDescriptor != currentChanPostImage.ownerPostDescriptor) {
      return
    }

    if (revealedImageFullUrl != currentChanPostImage.imageUrl) {
      return
    }

    val updatedThumbnailViewOptions = currentThumbnailViewOptions.copy(revealSpoilerImage = true)

    withContext(NonCancellable) {
      unbindPostImage()
      bindPostImage(
        postImage = currentChanPostImage,
        canUseHighResCells = currentCanUseHighResCells,
        thumbnailViewOptions = updatedThumbnailViewOptions
      )
    }
  }

  private fun listenForThirdEyeUpdates(postImage: ChanPostImage) {
    scope.launch {
      if (!thirdEyeManager.isEnabled()) {
        return@launch
      }

      hasThirdEyeImageMaybe = withContext(Dispatchers.Default) {
        thirdEyeManager.extractThirdEyeHashOrNull(postImage) != null
      }

      if (hasThirdEyeImageMaybe) {
        invalidate()
      }

      val thirdEyeImage = thirdEyeManager.imageForPost(postImage.ownerPostDescriptor)
      if (thirdEyeImage == null && hasThirdEyeImageMaybe) {
        // To avoid running the animation again after we rebind the post which happens after PostLoader
        // finishes it's job.
        runOrStopGlowAnimation(stop = false)
      } else {
        runOrStopGlowAnimation(stop = true)
      }

      thirdEyeManager.thirdEyeImageAddedFlow
        .onCompletion { runOrStopGlowAnimation(stop = true) }
        .collect { postDescriptor ->
          ensureActive()

          if (postImage.ownerPostDescriptor != postDescriptor) {
            return@collect
          }

          hasThirdEyeImageMaybe = withContext(Dispatchers.Default) {
            thirdEyeManager.extractThirdEyeHashOrNull(postImage) != null
          }

          if (hasThirdEyeImageMaybe) {
            invalidate()
          }

          runOrStopGlowAnimation(stop = true)
        }
    }
  }

  private fun runOrStopGlowAnimation(stop: Boolean = false) {
    alphaAnimator?.end()
    alphaAnimator = null

    if (stop) {
      return
    }

    fun onEndOrCancel() {
      thirdEyeIcon.alpha = 255
      invalidate()

      alphaAnimator = null
    }

    alphaAnimator = ValueAnimator.ofFloat(.2f, .8f).apply {
      duration = 500
      repeatMode = ValueAnimator.REVERSE
      repeatCount = ValueAnimator.INFINITE
      interpolator = glowInterpolator

      addUpdateListener { animator ->
        val alpha = (animator.animatedValue as Float * 255f).toInt()

        thirdEyeIcon.alpha = alpha
        invalidate()
      }

      addListener(
        onEnd = { onEndOrCancel() },
        onCancel = { onEndOrCancel() }
      )

      start()
    }
  }

  private fun bindPostImage(
    postImage: ChanPostImage,
    canUseHighResCells: Boolean,
    thumbnailViewOptions: ThumbnailViewOptions,
    forcedAfterPrefetchFinished: Boolean
  ) {
    if (postImage == this.postImage && !forcedAfterPrefetchFinished) {
      return
    }

    showPrefetchLoadingIndicator = kurobaSettings.application.prefetchMedia.readBlocking()

    if (showPrefetchLoadingIndicator) {
      segmentedCircleDrawable = SegmentedCircleDrawable().apply {
        setColor(themeEngine.chanTheme.accentColor)
        alpha = 192
        percentage(0f)
      }
    }

    this.postImage = postImage
    this.canUseHighResCells = canUseHighResCells
    this.thumbnailViewOptions = thumbnailViewOptions

    val (url, cacheFileType) = getUrl(
      postImage = postImage,
      canUseHighResCells = canUseHighResCells,
      thumbnailViewOptions = thumbnailViewOptions
    )

    if (url == null || cacheFileType == null || TextUtils.isEmpty(url)) {
      unbindPostImage()
      return
    }

    val isVideoHighRes = postImage.type == ChanPostImageType.MOVIE
      && cacheFileType == CacheFileType.PostMediaFull

    if (isVideoHighRes) {
      val videoUrl = postImage.imageUrl?.toString()
      val thumbnailUrl = postImage.getThumbnailUrl(kurobaSettings, false)?.toString()
      if (videoUrl != null && thumbnailUrl != null) {
        scope.launch(Dispatchers.IO) {
          val cacheFile = cacheHandler.getCacheFileOrNull(CacheFileType.PostMediaFull, videoUrl)
          if (cacheFile == null || !cacheFile.exists() || cacheFile.length() == 0L) {
            return@launch
          }
          val bitmap = MediaUtils.extractVideoFrameOrNull(cacheFile)
          ensureActive()
          withContext(Dispatchers.Main.immediate) {
            if (bitmap != null) {
              thumbnail.setImageBitmap(bitmap)
              thumbnail.invalidate()
            } else {
              // Fallback: use the actual thumbnail URL with thumbnail cache
              thumbnail.bindImageUrl(
                url = thumbnailUrl,
                cacheFileType = CacheFileType.PostMediaThumbnail,
                postDescriptor = postImage.ownerPostDescriptor,
                imageSize = ImageLoaderDeprecated.ImageSize.MeasurableImageSize.create(this@PostImageThumbnailView),
                thumbnailViewOptions = thumbnailViewOptions
              )
            }
          }
        }
        return
      }
    }

    thumbnail.bindImageUrl(
      url = url,
      cacheFileType = cacheFileType,
      postDescriptor = postImage.ownerPostDescriptor,
      imageSize = ImageLoaderDeprecated.ImageSize.MeasurableImageSize.create(this),
      thumbnailViewOptions = thumbnailViewOptions
    )
  }

  fun bindOmittedFilesInfo(postCellData: PostCellData) {
    val postCellThumbnailSizePercents = kurobaSettings.application.postCellThumbnailSizePercents
    val multiplier =
      postCellThumbnailSizePercents.readBlocking().toFloat() / postCellThumbnailSizePercents.max.toFloat()
    val totalPadding = ((OMITTED_FILES_INDICATOR_PADDING / 2f) + (OMITTED_FILES_INDICATOR_PADDING * multiplier)).toInt()

    thumbnailOmittedFilesCount.updatePaddings(
      left = totalPadding,
      right = totalPadding,
      top = totalPadding,
      bottom = totalPadding
    )

    val showOmittedFilesCountContainer = postCellData.postImages.size > 1
      && (postCellData.postMultipleImagesCompactMode || postCellData.boardPostViewMode != BoardPostViewMode.List)

    if (showOmittedFilesCountContainer) {
      val imagesCount = postCellData.postImages.size - 1
      thumbnailOmittedFilesCountContainer.visibility = VISIBLE
      thumbnailOmittedFilesCount.text = getString(R.string.thumbnail_omitted_files_indicator_text, imagesCount)
    } else {
      thumbnailOmittedFilesCountContainer.visibility = GONE
    }
  }

  private suspend fun onPrefetchStateChanged(prefetchState: PrefetchState) {
    if (!prefetchingEnabled) {
      return
    }

    val canShowProgress = showPrefetchLoadingIndicator && segmentedCircleDrawable != null
    if (canShowProgress && prefetchState is PrefetchStarted) {
      prefetching = true
      segmentedCircleDrawable!!.percentage(1f)
      invalidate()
      return
    }

    if (canShowProgress && prefetchState is PrefetchProgress) {
      if (!prefetching) {
        return
      }

      val progress = prefetchState.progress
      segmentedCircleDrawable!!.percentage(progress)
      invalidate()
      return
    }

    if (prefetchState is PrefetchCompleted) {
      if (canShowProgress) {
        prefetching = false
        segmentedCircleDrawable!!.percentage(0f)
        invalidate()
      }

      if (!prefetchState.success) {
        return
      }

      if (postImage != null && canUseHighResCells) {
        val thumbnailViewOptions = thumbnail.thumbnailViewOptions
        val canSwapThumbnailToFullImage = postImage?.imageSpoilered(kurobaSettings) == false
          || kurobaSettings.application.postThumbnailRemoveImageSpoilers.read()

        if (canSwapThumbnailToFullImage && thumbnailViewOptions != null) {
          bindPostImage(
            postImage = postImage!!,
            canUseHighResCells = canUseHighResCells,
            thumbnailViewOptions = thumbnailViewOptions,
            forcedAfterPrefetchFinished = true
          )
        }
      }
    }
  }

  private fun getUrl(
    postImage: ChanPostImage,
    canUseHighResCells: Boolean,
    thumbnailViewOptions: ThumbnailViewOptions
  ): Pair<String?, CacheFileType?> {
    val thumbnailUrl = postImage.getThumbnailUrl(
      kurobaSettings = kurobaSettings,
      isSpoilerRevealed = thumbnailViewOptions.revealSpoilerImage
    )
    if (thumbnailUrl == null) {
      Logger.e(TAG, "getUrl() postImage: $postImage, has no thumbnail url")
      return null to null
    }

    var url: String? = thumbnailUrl.toString()
    var cacheFileType = CacheFileType.PostMediaThumbnail

    val hasImageUrl = postImage.imageUrl != null

    fun prefetchingDisabledOrAlreadyPrefetched(): Boolean {
      return !kurobaSettings.application.prefetchMedia.readBlocking()
        || prefetchStateManager.isPrefetched(postImage)
    }

    val highRes = hasImageUrl
      && canUseHighResCells
      && postImage.type == ChanPostImageType.STATIC
      && prefetchingDisabledOrAlreadyPrefetched()
      && kurobaSettings.application.highResCells.readBlocking()
      && postImage.canBeUsedAsHighResolutionThumbnail(kurobaSettings)
      && canAutoLoad(kurobaSettings, cacheHandler, postImage)

    val gifHighRes = hasImageUrl
      && canUseHighResCells
      && postImage.type == ChanPostImageType.GIF
      && prefetchingDisabledOrAlreadyPrefetched()
      && kurobaSettings.application.highResGifCells.readBlocking()
      && postImage.canBeUsedAsHighResolutionThumbnail(kurobaSettings)
      && canAutoLoad(kurobaSettings, cacheHandler, postImage)

    val isMp4 = postImage.extension?.equals("mp4", ignoreCase = true) == true
      || postImage.extension?.equals("m4v", ignoreCase = true) == true
    val videoHighRes = hasImageUrl
      && canUseHighResCells
      && postImage.type == ChanPostImageType.MOVIE
      && ((isMp4 && kurobaSettings.application.highResMp4Cells.readBlocking())
          || (!isMp4 && kurobaSettings.application.highResWebmCells.readBlocking()))
      && cacheHandler.cacheFileExists(CacheFileType.PostMediaFull, postImage.imageUrl!!.toString())

    if (highRes || gifHighRes || videoHighRes) {
      url = postImage.imageUrl?.toString()
      cacheFileType = CacheFileType.PostMediaFull
    }

    return url to cacheFileType
  }

  fun setRatio(ratio: Float) {
    this.ratio = ratio
  }

  /**
   * Plays the post's video inline (muted, looped) instead of showing a static thumbnail.
   * Only videos that are already fully cached on disk are played. Must be called by every cell
   * type that wants it (both [PostImageThumbnailViewWrapper] and CardPostCell) since they bind
   * thumbnails through different code paths.
   * */
  fun bindVideoAutoplayIfNeeded(postCellData: PostCellData, chanPostImage: ChanPostImage) {
    unbindVideoPlayer()

    val isCatalogMode = postCellData.chanDescriptor is ChanDescriptor.ICatalogDescriptor
    val videoUrl = chanPostImage.imageUrl?.toString()

    if (
      !isCatalogMode
      || chanPostImage.type != ChanPostImageType.MOVIE
      || videoUrl == null
      || !kurobaSettings.application.catalogVideoAutoplay.readBlocking()
    ) {
      return
    }

    // galleryMaxAutoplays goes up to 24, which is fine for the album grid: it is a transient,
    // fullscreen view. The catalog is different - it stays on screen (and in SPLIT mode stays
    // visible while a thread is open), the tablet catalog can be 10 columns wide, and every
    // player holds a hardware decoder plus its MediaCodec buffers. Letting the album's cap
    // through here exhausted a 256MB heap on tablets, so clamp it hard.
    val maxAutoplays = kurobaSettings.application.galleryMaxAutoplays.readBlocking()
      .coerceAtMost(CATALOG_AUTOPLAY_HARD_CAP)
    val isSplitLayout = kurobaSettings.application.isSplitLayoutModeBlocking()

    videoScope.launch {
      val cachedVideoFile = withContext(Dispatchers.IO) {
        cacheHandler
          .getCacheFileOrNull(CacheFileType.PostMediaFull, videoUrl)
          ?.takeIf { file -> file.exists() && file.length() > 0 }
      } ?: return@launch

      val runtime = Runtime.getRuntime()
      val availableMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024L * 1024L)
      if (activeVideoPlayerCount.get() >= maxAutoplays || availableMB < MIN_HEAP_MB_BEFORE_NEW_PLAYER) {
        return@launch
      }

      val textureView = TextureView(context).apply {
        alpha = 0f
        isClickable = false
        isFocusable = false
      }

      addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      videoTextureView = textureView

      activeVideoPlayerCount.incrementAndGet()
      videoPlayerCounted = true

      // The default LoadControl buffers tens of seconds of video per player, which is wasted on
      // short looping catalog clips and multiplies across every visible cell. Cap it instead.
      val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(5_000, 15_000, 2_000, 3_000)
        .setTargetBufferBytes(CATALOG_AUTOPLAY_TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

      @Suppress("DEPRECATION")
      val player = SimpleExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)
      ).setLoadControl(loadControl).build().also { p ->
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = 0f
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
          override fun onRenderedFirstFrame() {
            textureView.alpha = 1f
          }

          override fun onVideoSizeChanged(size: VideoSize) {
            applyVideoTransform(textureView, size.width, size.height)
          }
        })
        p.setVideoTextureView(textureView)
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(cachedVideoFile)))
        p.prepare()
      }

      videoPlayer = player

      // In SPLIT mode the catalog pane stays visible while a thread is read in the right pane,
      // so its players would otherwise keep decoding for as long as the thread is open - on top
      // of whatever the thread itself allocates. Pausing only the cell whose own thread is open
      // was not enough; pause the whole catalog whenever any thread is open.
      if (isSplitLayout) {
        currentOpenedDescriptorStateManagerLazy.get()
          .currentThreadDescriptorFlow
          .onEach { openedThreadDescriptor ->
            player.playWhenReady = openedThreadDescriptor == null
          }
          .collect()
      }
    }
  }

  private fun applyVideoTransform(textureView: TextureView, videoWidth: Int, videoHeight: Int) {
    val viewWidth = textureView.width.toFloat()
    val viewHeight = textureView.height.toFloat()
    if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
      return
    }

    val videoAr = videoWidth.toFloat() / videoHeight.toFloat()
    val viewAr = viewWidth / viewHeight

    // Match whatever the static thumbnail does so the video does not jump when it appears
    val cropToFill = thumbnailViewOptions?.postThumbnailScaling != PostThumbnailScaling.FitCenter

    val scaleX: Float
    val scaleY: Float
    if (cropToFill == (videoAr > viewAr)) {
      scaleX = videoAr / viewAr
      scaleY = 1f
    } else {
      scaleX = 1f
      scaleY = viewAr / videoAr
    }

    val matrix = Matrix()
    matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
    textureView.setTransform(matrix)
  }

  private fun unbindVideoPlayer() {
    videoScope.cancelChildren()

    videoPlayer?.let { player ->
      player.stop()
      player.release()
    }
    videoPlayer = null

    if (videoPlayerCounted) {
      activeVideoPlayerCount.decrementAndGet()
      videoPlayerCounted = false
    }

    videoTextureView?.let { textureView -> removeView(textureView) }
    videoTextureView = null
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    unbindVideoPlayer()
  }

  override fun draw(canvas: Canvas) {
    super.draw(canvas)

    val chanPostImage = postImage
      ?: return

    if (thumbnail.error) {
      return
    }

    if (nsfwMode) {
      canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), nsfwModePaint)
    }

    if (chanPostImage.isPlayableType()) {
      val iconScale = 1
      val scalar = (Math.pow(2.0, iconScale.toDouble()) - 1) / Math.pow(2.0, iconScale.toDouble())
      val x = (width / 2.0 - playIcon.intrinsicWidth * scalar).toInt()
      val y = (height / 2.0 - playIcon.intrinsicHeight * scalar).toInt()

      playIconBounds.set(x, y, x + playIcon.intrinsicWidth * iconScale, y + playIcon.intrinsicHeight * iconScale)
      playIcon.bounds = playIconBounds
      playIcon.draw(canvas)
    }

    if (hasThirdEyeImageMaybe) {
      val x = (width - thirdEyeIconSize - cornerIndicatorMargin).toInt()
      val y = cornerIndicatorMargin.toInt()

      val circleRadius = thirdEyeIconSize.toFloat() / 2f
      canvas.drawCircle(x.toFloat() + circleRadius, y.toFloat() + circleRadius, circleRadius, thirdEyeIconBgPaint)

      thirdEyeIconBounds.set(x, y, x + thirdEyeIconSize, y + thirdEyeIconSize)
      thirdEyeIcon.bounds = thirdEyeIconBounds
      thirdEyeIcon.draw(canvas)
    }

    if (segmentedCircleDrawable != null && showPrefetchLoadingIndicator && prefetching) {
      canvas.withTranslation(cornerIndicatorMargin, cornerIndicatorMargin) {
        circularProgressDrawableBounds.set(0, 0, prefetchIndicatorSize, prefetchIndicatorSize)
        segmentedCircleDrawable!!.bounds = circularProgressDrawableBounds
        segmentedCircleDrawable!!.draw(canvas)
      }
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    if (ratio == 0f) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec)
      return
    }

    val heightMode = MeasureSpec.getMode(heightMeasureSpec)
    if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
      && (heightMode == MeasureSpec.UNSPECIFIED || heightMode == MeasureSpec.AT_MOST)
    ) {
      val width = MeasureSpec.getSize(widthMeasureSpec)
      super.onMeasure(
        widthMeasureSpec,
        MeasureSpec.makeMeasureSpec((width / ratio).toInt(), MeasureSpec.EXACTLY)
      )
    } else {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
  }

  companion object {
    private const val TAG = "PostImageThumbnailView"
    // Never run more than this many catalog autoplays at once, regardless of what the album's
    // "max simultaneous autoplays" setting says. See bindVideoAutoplayIfNeeded().
    private const val CATALOG_AUTOPLAY_HARD_CAP = 4
    private const val CATALOG_AUTOPLAY_TARGET_BUFFER_BYTES = 2 * 1024 * 1024

    // A video decoder needs considerably more than a few spare MB to spin up. 40MB was low
    // enough that the last player could be created into an almost-full heap and then OOM while
    // decoding rather than at creation time.
    private const val MIN_HEAP_MB_BEFORE_NEW_PLAYER = 96L
    private val cornerIndicatorMargin = dp(4f).toFloat()
    private val prefetchIndicatorSize = dp(16f)
    private val thirdEyeIconSize = dp(16f)
    private val OMITTED_FILES_INDICATOR_PADDING = dp(4f)
    private val playIcon = getDrawable(R.drawable.ic_play_circle_outline_white_24dp)
    private val glowInterpolator = AccelerateDecelerateInterpolator()

    private val nsfwModePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = ColorUtils.setAlphaComponent(Color.DKGRAY, 225)
      style = Paint.Style.FILL
    }

    private val thirdEyeIconBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = ColorUtils.setAlphaComponent(Color.BLACK, 128)
      style = Paint.Style.FILL
    }
  }

}