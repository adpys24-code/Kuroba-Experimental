package com.github.k1rakishou.chan.core.usecase

import com.github.k1rakishou.chan.core.base.okhttp.ProxiedOkHttpClient
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.groupOrNull
import com.github.k1rakishou.common.suspendConvertIntoJsoupDocument
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class LoadBoardFlagsUseCase(
  private val proxiedOkHttpClient: ProxiedOkHttpClient
) {

  suspend fun await(boardDescriptor: BoardDescriptor): ModularResult<List<FlagInfo>?> {
    return withContext(Dispatchers.IO) {
      return@withContext ModularResult.Try {
        val url = "https://boards.4chan.org/${boardDescriptor.boardCode}/"

        val request = Request.Builder()
          .get()
          .url(url)
          .build()

        val result = proxiedOkHttpClient.okHttpClient().suspendConvertIntoJsoupDocument(request)

        val document = if (result is ModularResult.Error) {
          return@Try null
        } else {
          (result as ModularResult.Value).value
        }

        // Use .flagSelector (class selector) instead of [class=flagSelector] (exact match)
        // so it still works if 4chan adds other CSS classes to the element
        val flagSelector = document.selectFirst("select.flagSelector")
          ?: document.selectFirst("select[name=flag]")
          ?: return@Try null

        val flags = mutableListOf<FlagInfo>()

        flagSelector.childNodes().forEach { node ->
          if (node !is Element) {
            return@forEach
          }

          if (!node.tagName().equals("option", ignoreCase = true)) {
            return@forEach
          }

          val matcher = FLAG_PATTERN.matcher(node.toString())
          if (!matcher.find()) {
            return@forEach
          }

          val flagKey = matcher.groupOrNull(1) ?: return@forEach
          val flagDescription = matcher.groupOrNull(2) ?: return@forEach

          // HTML scraper only triggers for boards with custom selectable flags (board_flags),
          // not for country_flags boards — so board-specific CDN path is always correct here.
          val iconUrl = if (flagKey == "0") null
          else "https://s.4cdn.org/image/flags/${boardDescriptor.boardCode}/${flagKey.lowercase()}.gif"
          flags += FlagInfo(
            flagKey = flagKey,
            flagDescription = flagDescription,
            iconUrl = iconUrl
          )
        }

        return@Try flags
      }
    }
  }

  data class FlagInfo(
    val flagKey: String,
    val flagDescription: String,
    val iconUrl: String? = null
  ) {

    fun asUserReadableString(): String {
      return "[${flagKey}] ${flagDescription}"
    }

  }

  companion object {
    private val FLAG_PATTERN = Pattern.compile("<option value=\"(.*)\">(.*)<\\/option>")
  }

}