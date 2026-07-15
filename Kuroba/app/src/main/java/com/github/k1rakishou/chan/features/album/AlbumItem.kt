package com.github.k1rakishou.chan.features.album

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.cache.CacheFileType
import com.github.k1rakishou.chan.core.cache.CacheHandler
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.features.view.media.MediaViewerControllerViewModel
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeText
import com.github.k1rakishou.chan.ui.compose.data.ChanDescriptorUi
import com.github.k1rakishou.chan.ui.compose.image.ImageLoaderRequest
import com.github.k1rakishou.chan.ui.compose.image.ImageLoaderRequestData
import com.github.k1rakishou.chan.ui.compose.image.ImageLoaderRequestProvider
import com.github.k1rakishou.chan.ui.compose.image.KurobaComposePostImageIndicators
import com.github.k1rakishou.chan.ui.compose.image.KurobaComposePostImageThumbnail
import com.github.k1rakishou.chan.ui.compose.ktu
import com.github.k1rakishou.chan.ui.compose.providers.LocalChanTheme
import com.github.k1rakishou.chan.ui.controller.base.ControllerKey
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.KurobaMediaType
import com.github.k1rakishou.chan.utils.MediaUtils
import com.github.k1rakishou.chan.utils.appDependencies
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.post.ChanPostImageType
import com.github.k1rakishou.v2.KurobaSettings
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.File

private const val TAG = "AlbumItem"
private const val TransparentBlackColorAlpha = 0.4f
private const val MIN_HEAP_MB_BEFORE_NEW_PLAYER = 40L
internal val activeVideoPlayerCount = java.util.concurrent.atomic.AtomicInteger(0)
private const val HighResCellsMaxSpanCountPhone = 3
private const val HighResCellsMaxSpanCountTablet = 5

@Composable
fun AlbumItem(
  modifier: Modifier,
  isInSelectionMode: Boolean,
  isSelected: Boolean,
  isNsfwModeEnabled: Boolean,
  showAlbumViewsImageDetails: Boolean,
  albumSpanCount: Int,
  controllerKey: ControllerKey,
  controllerViewModel: AlbumViewControllerViewModel,
  chanDescriptorUi: ChanDescriptorUi?,
  albumItemData: AlbumItemData,
  downloadingAlbumItem: DownloadingAlbumItem?,
  isVisible: Boolean = true,
  onClick: (AlbumItemData) -> Unit,
  onLongClick: (AlbumItemData) -> Unit,
  clearDownloadingAlbumItemState: (DownloadingAlbumItem) -> Unit
) {
  val onDemandContentLoaderManager = appDependencies().onDemandContentLoaderManager
  val kurobaSettings = appDependencies().kurobaSettings
  val cacheHandler = appDependencies().cacheHandler
  val siteManager = appDependencies().siteManager

  DisposableEffect(key1 = Unit) {
    onDemandContentLoaderManager.onPostBind(albumItemData.postDescriptor, albumItemData.isCatalogMode)
    onDispose { onDemandContentLoaderManager.onPostUnbind(albumItemData.postDescriptor, true) }
  }

  val canUseHighResCells = if (AppModuleAndroidUtils.isTablet) {
    albumSpanCount <= HighResCellsMaxSpanCountTablet
  } else {
    albumSpanCount <= HighResCellsMaxSpanCountPhone
  }

  // Null = not a video cell, non-null = should play (value is CacheHandler file or empty string = use URL)
  var videoSource by remember(albumItemData) { mutableStateOf<String?>(null) }
  var videoAutoplay by remember(albumItemData) { mutableStateOf(true) }


  LaunchedEffect(albumItemData, canUseHighResCells) {
    if (albumItemData.mediaType != KurobaMediaType.Video || !canUseHighResCells || isInSelectionMode) return@LaunchedEffect
    val imageUrl = albumItemData.fullImageUrl?.toString() ?: return@LaunchedEffect
    val isMp4 = imageUrl.endsWith(".mp4", ignoreCase = true) || imageUrl.endsWith(".m4v", ignoreCase = true)
    val (settingAllows, autoplay) = withContext(Dispatchers.IO) {
      val allows = if (isMp4) kurobaSettings.application.highResMp4Cells.readBlocking()
                   else kurobaSettings.application.highResWebmCells.readBlocking()
      val auto = kurobaSettings.application.highResVideoCellsAutoplay.readBlocking()
      allows to auto
    }
    if (!settingAllows) return@LaunchedEffect
    videoAutoplay = autoplay
    // Set the URL source immediately so the cell shows; it will stream if not cached locally.
    // Then keep checking for a local cache file to upgrade to (faster/no network).
    videoSource = imageUrl
    while (isActive) {
      val file = withContext(Dispatchers.IO) {
        cacheHandler.getCacheFileOrNull(CacheFileType.PostMediaFull, imageUrl)
          ?.takeIf { it.exists() && it.length() > 0 }
      }
      if (file != null) {
        videoSource = file.absolutePath
        break
      }
      delay(1000L)
    }
  }

  val requestProvider = remember(chanDescriptorUi, albumItemData, albumSpanCount, isInSelectionMode) {
    getImageLoaderRequestProvider(
      kurobaSettings = kurobaSettings,
      chanDescriptor = chanDescriptorUi?.chanDescriptor,
      albumItemData = albumItemData,
      albumSpanCount = albumSpanCount,
      isInSelectionMode = isInSelectionMode
    )
  }

  Box(
    modifier = modifier
      .then(
        Modifier.albumItemSelection(isInSelectionMode, isSelected)
      )
  ) {
    // Always show the static thumbnail as background (also covers buffering gap)
    KurobaComposePostImageThumbnail(
      modifier = Modifier.fillMaxWidth(),
      controllerKey = controllerKey,
      postImageThumbnailKey = albumItemData.albumItemDataKey,
      requestProvider = requestProvider,
      mediaType = albumItemData.mediaType,
      isNsfwModeEnabled = isNsfwModeEnabled,
      displayErrorMessage = true,
      showShimmerEffectWhenLoading = true,
      onClick = { onClick(albumItemData) },
      onLongClick = { onLongClick(albumItemData) }
    )

    // Overlay the live video player once a source is known
    val src = videoSource
    if (src != null) {
      val videoRequestHeaders = remember(albumItemData) {
        val props = mutableMapOf<String, String>()
        try {
          val siteDescriptor = albumItemData.postDescriptor.siteDescriptor()
          val remoteUrl = albumItemData.fullImageUrl
          if (remoteUrl != null) {
            val site = siteManager.bySiteDescriptorAndActive(siteDescriptor)
            site?.requestModifier?.modifyVideoStreamRequest(site, props, remoteUrl)
          }
        } catch (_: Exception) {}
        // ExoPlayer's DefaultHttpDataSource does not decompress gzip responses
        props.remove("Accept-Encoding")
        props as Map<String, String>
      }
      key(src) {
        VideoAlbumItemCell(
          modifier = Modifier.fillMaxWidth(),
          source = src,
          originalUrl = albumItemData.fullImageUrl?.toString() ?: src,
          autoplay = videoAutoplay,
          isVisible = isVisible,
          requestHeaders = videoRequestHeaders,
          controllerViewModel = controllerViewModel,
          albumSpanCount = albumSpanCount,
          onPlayerReady = { _, _ -> },
          onClick = { positionMs ->
            controllerViewModel.lastVideoClickPositionMs = positionMs
            onClick(albumItemData)
          },
          onLongClick = { onLongClick(albumItemData) }
        )
      }
    }

    DownloadingAlbumItemOverlay(
      modifier = Modifier.fillMaxSize(),
      downloadingAlbumItem = downloadingAlbumItem,
      clearDownloadingAlbumItemState = clearDownloadingAlbumItemState
    )

    if (chanDescriptorUi != null && albumItemData.fullImageUrlString != null) {
      KurobaComposePostImageIndicators(
        modifier = Modifier
          .align(Alignment.TopStart),
        chanDescriptor = chanDescriptorUi.chanDescriptor,
        postDescriptor = albumItemData.postDescriptor,
        imageFullUrlString = albumItemData.fullImageUrlString,
        backgroundAlpha = TransparentBlackColorAlpha
      )
    }

    if (showAlbumViewsImageDetails && albumItemData.albumItemPostData?.isNotEmpty() == true) {
      AlbumItemInfo(
        modifier = Modifier
          .fillMaxWidth()
          .wrapContentHeight()
          .align(Alignment.BottomCenter),
        albumItemPostData = albumItemData.albumItemPostData
      )
    }
  }
}

@Composable
private fun AlbumItemInfo(
  modifier: Modifier,
  albumItemPostData: AlbumViewControllerViewModel.AlbumItemPostData
) {
  Column(
    modifier = modifier
      .drawBehind { drawRect(Color.Black.copy(alpha = TransparentBlackColorAlpha)) }
      .padding(horizontal = 2.dp, vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    if (albumItemPostData.threadSubject != null) {
      KurobaComposeText(
        text = albumItemPostData.threadSubject,
        color = Color.White,
        maxLines = 1,
        fontSize = 10.ktu,
        overflow = TextOverflow.Ellipsis
      )
    }

    if (albumItemPostData.mediaInfo != null) {
      KurobaComposeText(
        text = albumItemPostData.mediaInfo,
        color = Color.White,
        maxLines = 1,
        fontSize = 10.ktu,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun DownloadingAlbumItemOverlay(
  modifier: Modifier,
  downloadingAlbumItem: DownloadingAlbumItem?,
  clearDownloadingAlbumItemState: (DownloadingAlbumItem) -> Unit
) {
  if (downloadingAlbumItem == null) {
    return
  }

  LaunchedEffect(key1 = downloadingAlbumItem.state) {
    when (downloadingAlbumItem.state) {
      DownloadingAlbumItem.State.Enqueued,
      DownloadingAlbumItem.State.Downloading -> {
        // no-op
      }
      DownloadingAlbumItem.State.Downloaded,
      DownloadingAlbumItem.State.FailedToDownload,
      DownloadingAlbumItem.State.Canceled -> {
        delay(2000)
        clearDownloadingAlbumItemState(downloadingAlbumItem)
      }
      DownloadingAlbumItem.State.Deleted -> {
        clearDownloadingAlbumItemState(downloadingAlbumItem)
      }
    }
  }

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center
  ) {
    Box {
      val painter = when (downloadingAlbumItem.state) {
        DownloadingAlbumItem.State.Enqueued -> {
          painterResource(id = R.drawable.ic_download_anim0)
        }
        DownloadingAlbumItem.State.Downloading -> {
          var animationAtEnd by remember { mutableStateOf(false) }

          LaunchedEffect(downloadingAlbumItem.state) {
            while (isActive) {
              animationAtEnd = !animationAtEnd
              delay(1500)
            }
          }

          rememberAnimatedVectorPainter(
            animatedImageVector = AnimatedImageVector.animatedVectorResource(id = R.drawable.ic_download_anim),
            atEnd = animationAtEnd
          )
        }
        DownloadingAlbumItem.State.Downloaded -> {
          painterResource(id = R.drawable.ic_download_anim1)
        }
        DownloadingAlbumItem.State.FailedToDownload,
        DownloadingAlbumItem.State.Canceled -> {
          painterResource(id = R.drawable.ic_baseline_warning_24)
        }
        DownloadingAlbumItem.State.Deleted -> {
          null
        }
      }

      if (painter != null) {
        Image(
          modifier = Modifier
            .size(40.dp)
            .drawBehind { drawCircle(color = Color.Black.copy(alpha = TransparentBlackColorAlpha)) }
            .padding(4.dp),
          painter = painter,
          contentDescription = null,
        )
      }
    }
  }
}

private fun Modifier.albumItemSelection(
  isInSelectionMode: Boolean,
  isSelected: Boolean,
): Modifier {
  return composed {
    val chanTheme = LocalChanTheme.current

    val inSelectionModeAndSelected = isInSelectionMode && isSelected
    val inSelectionModeAndNotSelected = isInSelectionMode && !isSelected

    val scaleAnimated = animateFloatAsState(
      targetValue = if (inSelectionModeAndSelected) 0.9f else 1f
    )
    val borderColorAnimated = animateColorAsState(
      targetValue = if (inSelectionModeAndSelected) chanTheme.accentColorCompose else Color.Transparent
    )
    val unselectedImageTintAlphaAnimated = animateFloatAsState(
      targetValue = if (inSelectionModeAndNotSelected) 0.6f else 0f
    )

    return@composed graphicsLayer {
      scaleX = scaleAnimated.value
      scaleY = scaleAnimated.value
    }.drawWithContent {
      drawContent()

      drawRect(
        color = chanTheme.backColorCompose,
        alpha = unselectedImageTintAlphaAnimated.value
      )

      drawRect(
        color = borderColorAnimated.value,
        style = Stroke(width = 2.dp.toPx())
      )
    }
  }
}

// source = local file absolute path OR http(s) URL.
// Uses ExoPlayer with the shared SimpleCache so bytes buffered here are already
// available when the media viewer opens — no re-buffering on tap.
@Composable
private fun VideoAlbumItemCell(
  modifier: Modifier,
  source: String,
  originalUrl: String,
  autoplay: Boolean,
  isVisible: Boolean,
  requestHeaders: Map<String, String>,
  controllerViewModel: AlbumViewControllerViewModel,
  albumSpanCount: Int,
  onPlayerReady: (SimpleExoPlayer, java.util.concurrent.atomic.AtomicBoolean) -> Unit,
  onClick: (positionMs: Long) -> Unit,
  onLongClick: () -> Unit
) {
  val context = LocalContext.current
  val exoPlayerCache = appDependencies().exoPlayerCache
  val kurobaSettings = appDependencies().kurobaSettings
  var videoVisible by remember { mutableStateOf(false) }

  val maxAutoplays = remember { kurobaSettings.application.galleryMaxAutoplays.readBlocking() }

  val playerReleased = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

  val player = remember {
    val runtime = Runtime.getRuntime()
    val availableMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024L * 1024L)
    val canCreate = activeVideoPlayerCount.get() < maxAutoplays
      && availableMB >= MIN_HEAP_MB_BEFORE_NEW_PLAYER

    if (!canCreate) {
      null
    } else {
      activeVideoPlayerCount.incrementAndGet()
      val dataSourceFactory = CacheDataSource.Factory()
        .setCache(exoPlayerCache.actualCache)
        .setUpstreamDataSourceFactory(
          DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        )
      @Suppress("DEPRECATION")
      SimpleExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)
      ).build().also { p ->
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = 0f
        p.playWhenReady = autoplay && isVisible
        p.addListener(object : Player.Listener {
          override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) videoVisible = true
          }
        })
        p.setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(source)))
        p.prepare()
        controllerViewModel.registerVideoPlayer(p, playerReleased)
      }
    }
  }

  LaunchedEffect(player) {
    if (player != null) {
      onPlayerReady(player, playerReleased)
    }
  }

  LaunchedEffect(isVisible) {
    player?.playWhenReady = autoplay && isVisible
  }

  DisposableEffect(Unit) {
    onDispose {
      if (player != null) {
        controllerViewModel.unregisterVideoPlayer(player)
        activeVideoPlayerCount.decrementAndGet()
        if (playerReleased.compareAndSet(false, true)) {
          player.stop()
          player.release()
        }
        // If playerReleased was already set (handoff), viewer owns the player lifecycle.
      }
      controllerViewModel.isAnyVideoZoomed = false
    }
  }

  val videoZoomEnabled = remember(albumSpanCount) { kurobaSettings.application.albumVideoZoom.readBlocking() && albumSpanCount == 1 }

  var zoom by remember { mutableFloatStateOf(1f) }
  var offsetX by remember { mutableFloatStateOf(0f) }
  var offsetY by remember { mutableFloatStateOf(0f) }
  var cellSize by remember { mutableStateOf(IntSize.Zero) }
  var videoWidth by remember { mutableStateOf(0) }
  var videoHeight by remember { mutableStateOf(0) }

  LaunchedEffect(player) {
    if (player == null) return@LaunchedEffect
    val listener = object : Player.Listener {
      override fun onVideoSizeChanged(size: com.google.android.exoplayer2.video.VideoSize) {
        if (size.width > 0 && size.height > 0) {
          videoWidth = size.width
          videoHeight = size.height
        }
      }
    }
    player.addListener(listener)
    kotlinx.coroutines.awaitCancellation()
  }

  if (player != null) {
    AndroidView(
      modifier = modifier
        .onSizeChanged { cellSize = it }
        .graphicsLayer {
          clip = true
          alpha = if (videoVisible) 1f else 0f
          if (videoZoomEnabled) {
            scaleX = zoom
            scaleY = zoom
            translationX = offsetX
            translationY = offsetY
          }
        }
        .pointerInput(videoZoomEnabled) {
          if (videoZoomEnabled) {
            coroutineScope {
              launch {
                // Custom gesture handler: only consume events during multi-touch (zoom)
                // or when already zoomed (single-finger pan). Single-finger at zoom=1
                // is NOT consumed so the LazyVerticalGrid can scroll normally.
                awaitEachGesture {
                  awaitFirstDown(requireUnconsumed = false)
                  do {
                    val event = awaitPointerEvent()
                    val pressedCount = event.changes.count { it.pressed }
                    if (pressedCount >= 2) {
                      val zoomChange = event.calculateZoom()
                      val panDelta = event.calculatePan()
                      val newZoom = (zoom * zoomChange).coerceIn(1f, 8f)
                      zoom = newZoom
                      controllerViewModel.isAnyVideoZoomed = zoom > 1f
                      if (zoom > 1f) {
                        val maxOffsetX = cellSize.width * (zoom - 1f) / 2f
                        val maxOffsetY = cellSize.height * (zoom - 1f) / 2f
                        offsetX = (offsetX + panDelta.x).coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = (offsetY + panDelta.y).coerceIn(-maxOffsetY, maxOffsetY)
                      } else {
                        offsetX = 0f
                        offsetY = 0f
                      }
                      event.changes.forEach { it.consume() }
                    } else if (pressedCount == 1 && zoom > 1f) {
                      val change = event.changes.firstOrNull { it.pressed } ?: continue
                      val panDelta = change.position - change.previousPosition
                      val maxOffsetX = cellSize.width * (zoom - 1f) / 2f
                      val maxOffsetY = cellSize.height * (zoom - 1f) / 2f
                      offsetX = (offsetX + panDelta.x).coerceIn(-maxOffsetX, maxOffsetX)
                      offsetY = (offsetY + panDelta.y).coerceIn(-maxOffsetY, maxOffsetY)
                      event.changes.forEach { it.consume() }
                    }
                    // Single finger at zoom == 1: do not consume → scroll passes through
                  } while (event.changes.any { it.pressed })
                }
              }
              launch {
                detectTapGestures(
                  onTap = {
                    onClick(player.currentPosition)
                  },
                  onLongPress = { onLongClick() },
                  onDoubleTap = {
                    zoom = 1f
                    offsetX = 0f
                    offsetY = 0f
                    controllerViewModel.isAnyVideoZoomed = false
                  }
                )
              }
            }
          } else {
            detectTapGestures(
              onTap = {
                onClick(player.currentPosition)
              },
              onLongPress = { onLongClick() }
            )
          }
        },
      factory = { ctx ->
        android.view.TextureView(ctx).also { player.setVideoTextureView(it) }
      },
      update = { textureView ->
        val vw = videoWidth.toFloat()
        val vh = videoHeight.toFloat()
        val cw = cellSize.width.toFloat()
        val ch = cellSize.height.toFloat()
        if (vw > 0f && vh > 0f && cw > 0f && ch > 0f) {
          val videoAr = vw / vh
          val viewAr = cw / ch
          // Scale so video fills the view (crop-to-fill, matching ContentScale.Crop)
          val scaleX: Float
          val scaleY: Float
          if (videoAr > viewAr) {
            // Video is wider than view: fill height, crop sides
            scaleX = videoAr / viewAr
            scaleY = 1f
          } else {
            // Video is taller than view: fill width, crop top/bottom
            scaleX = 1f
            scaleY = viewAr / videoAr
          }
          val matrix = android.graphics.Matrix()
          matrix.setScale(scaleX, scaleY, cw / 2f, ch / 2f)
          textureView.setTransform(matrix)
        }
      }
    )
  }
}

private fun extractVideoFrameToFile(cacheFile: File, frameDir: File, urlKey: String): File? {
  val frameFile = File(frameDir, "${urlKey.hashCode()}.jpg")
  if (frameFile.exists() && frameFile.length() > 0) return frameFile
  return try {
    val bitmap = MediaUtils.extractVideoFrameOrNull(cacheFile) ?: return null
    frameDir.mkdirs()
    frameFile.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
    if (frameFile.length() > 0) frameFile else null
  } catch (_: Exception) { null }
}

private fun getImageLoaderRequestProvider(
  kurobaSettings: KurobaSettings,
  chanDescriptor: ChanDescriptor?,
  albumItemData: AlbumItemData,
  albumSpanCount: Int,
  isInSelectionMode: Boolean
): ImageLoaderRequestProvider {
  val cacheHandler = appDependencies().cacheHandler
  val chanThreadsCache = appDependencies().chanThreadsCache
  val revealedSpoilerImagesManager = appDependencies().revealedSpoilerImagesManager
  val appContext = appDependencies().application

  return ImageLoaderRequestProvider(
    key = ImageLoaderRequestProvider.FullKey(arrayOf(chanDescriptor, albumItemData, albumItemData, isInSelectionMode)),
    provide = {
      if (chanDescriptor == null) {
        return@ImageLoaderRequestProvider null
      }

      return@ImageLoaderRequestProvider withContext(Dispatchers.IO) {
        val postImage = chanThreadsCache.getPostFromCache(chanDescriptor, albumItemData.postDescriptor)
          ?.firstPostImageOrNull { chanPostImage ->
            return@firstPostImageOrNull chanPostImage.actualThumbnailUrl == albumItemData.thumbnailImageUrl
              && chanPostImage.imageUrl == albumItemData.fullImageUrl
          }

        if (postImage == null) {
          Logger.error(TAG) { "getImageLoaderRequestProvider() failed to find postImage for ${albumItemData}" }
          return@withContext null
        }

        val revealSpoilerImage = revealedSpoilerImagesManager.isImageSpoilerImageRevealed(postImage)

        val canUseHighResCells = if (AppModuleAndroidUtils.isTablet) {
          albumSpanCount <= HighResCellsMaxSpanCountTablet
        } else {
          albumSpanCount <= HighResCellsMaxSpanCountPhone
        }

        val (imageUrl, cacheFileType) = getImageUrlAndCacheFileType(
          cacheHandler = cacheHandler,
          kurobaSettings = kurobaSettings,
          postImage = postImage,
          canUseHighResCells = canUseHighResCells,
          revealSpoilerImage = revealSpoilerImage || isInSelectionMode
        )

        if (imageUrl == null || cacheFileType == null) {
          Logger.error(TAG) {
            "getImageLoaderRequestProvider() failed to determine which imageUrl or cacheFileType to use " +
              "(imageUrl: ${imageUrl}, cacheFileType: ${cacheFileType})"
          }

          return@withContext null
        }

        if (cacheFileType == CacheFileType.PostMediaFull && postImage.type == ChanPostImageType.MOVIE) {
          val videoUrlStr = imageUrl.toString()
          val cacheFile = cacheHandler.getCacheFileOrNull(CacheFileType.PostMediaFull, videoUrlStr)
          if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
            val frameDir = File(appContext.cacheDir, "video_frames")
            val frameFile = extractVideoFrameToFile(cacheFile, frameDir, videoUrlStr)
            if (frameFile != null) {
              return@withContext ImageLoaderRequest(
                data = ImageLoaderRequestData.File(frameFile),
                transformations = emptyList()
              )
            }
          }
          val thumbnailUrl = postImage.getThumbnailUrl(kurobaSettings, revealSpoilerImage || isInSelectionMode)
          if (thumbnailUrl != null) {
            return@withContext ImageLoaderRequest(
              data = ImageLoaderRequestData.Url(
                httpUrl = thumbnailUrl,
                cacheFileType = CacheFileType.PostMediaThumbnail,
                postDescriptor = albumItemData.postDescriptor
              ),
              transformations = emptyList()
            )
          }
        }

        return@withContext ImageLoaderRequest(
          data = ImageLoaderRequestData.Url(
            httpUrl = imageUrl,
            cacheFileType = cacheFileType,
            postDescriptor = albumItemData.postDescriptor
          ),
          transformations = emptyList()
        )
      }
    }
  )
}

private fun getImageUrlAndCacheFileType(
  cacheHandler: CacheHandler,
  kurobaSettings: KurobaSettings,
  postImage: ChanPostImage,
  canUseHighResCells: Boolean,
  revealSpoilerImage: Boolean
): Pair<HttpUrl?, CacheFileType?> {
  val thumbnailUrl = postImage.getThumbnailUrl(
    kurobaSettings = kurobaSettings,
    isSpoilerRevealed = revealSpoilerImage
  )
  if (thumbnailUrl == null) {
    Logger.e(TAG, "getUrl() postImage: $postImage, has no thumbnail url")
    return null to null
  }

  val highRes = postImage.imageUrl != null
    && kurobaSettings.application.highResCells.readBlocking()
    && postImage.canBeUsedAsHighResolutionThumbnail(kurobaSettings)
    && canUseHighResCells
    && postImage.type == ChanPostImageType.STATIC
    && MediaViewerControllerViewModel.canAutoLoad(kurobaSettings, cacheHandler, postImage)

  val gifHighRes = postImage.imageUrl != null
    && kurobaSettings.application.highResGifCells.readBlocking()
    && postImage.canBeUsedAsHighResolutionThumbnail(kurobaSettings)
    && canUseHighResCells
    && postImage.type == ChanPostImageType.GIF
    && MediaViewerControllerViewModel.canAutoLoad(kurobaSettings, cacheHandler, postImage)

  val isMp4 = postImage.extension?.equals("mp4", ignoreCase = true) == true
    || postImage.extension?.equals("m4v", ignoreCase = true) == true
  val videoHighRes = postImage.imageUrl != null
    && canUseHighResCells
    && postImage.type == ChanPostImageType.MOVIE
    && ((isMp4 && kurobaSettings.application.highResMp4Cells.readBlocking())
        || (!isMp4 && kurobaSettings.application.highResWebmCells.readBlocking()))
    && cacheHandler.cacheFileExists(CacheFileType.PostMediaFull, postImage.imageUrl!!.toString())

  if (!highRes && !gifHighRes && !videoHighRes) {
    return thumbnailUrl to CacheFileType.PostMediaThumbnail
  }

  Logger.verbose(TAG) { "getUrl() using high-res thumbnail for ${postImage.actualThumbnailUrl}" }
  return postImage.imageUrl to CacheFileType.PostMediaFull
}
