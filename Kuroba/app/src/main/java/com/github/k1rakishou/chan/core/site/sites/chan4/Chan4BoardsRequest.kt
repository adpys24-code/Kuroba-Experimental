package com.github.k1rakishou.chan.core.site.sites.chan4

import com.github.k1rakishou.chan.core.base.okhttp.ProxiedOkHttpClient
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.net.AbstractRequest
import com.github.k1rakishou.chan.core.repository.BoardFlagInfoRepository
import com.github.k1rakishou.chan.core.usecase.LoadBoardFlagsUseCase
import com.github.k1rakishou.common.jsonArray
import com.github.k1rakishou.common.jsonObject
import com.github.k1rakishou.common.useJsonReader
import com.github.k1rakishou.model.data.board.BoardBuilder
import com.github.k1rakishou.model.data.board.ChanBoard
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.model.data.site.SiteBoards
import com.google.gson.stream.JsonReader
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException

class Chan4BoardsRequest(
  private val siteDescriptor: SiteDescriptor,
  private val boardManager: BoardManager,
  private val boardFlagInfoRepository: BoardFlagInfoRepository,
  request: Request,
  proxiedOkHttpClient: ProxiedOkHttpClient
) : AbstractRequest<SiteBoards>(request, proxiedOkHttpClient) {

  override suspend fun processBody(responseBody: ResponseBody): SiteBoards {
    return responseBody.useJsonReader { jsonReader -> readJson(jsonReader) }
  }

  private suspend fun readJson(reader: JsonReader): SiteBoards {
    val list: MutableList<ChanBoard> = ArrayList()
    val boardFlagsList: MutableList<Pair<String, List<LoadBoardFlagsUseCase.FlagInfo>>> = ArrayList()

    reader.jsonObject {
      while (hasNext()) {
        val key = nextName()
        if (key == "boards") {
          jsonArray {
            while (hasNext()) {
              val (board, flags) = readBoardEntry(this)
              if (board != null) {
                list.add(board)
                if (flags != null) {
                  boardFlagsList += board.boardDescriptor.boardCode to flags
                }
              }
            }
          }
        } else {
          skipValue()
        }
      }
    }

    // Pre-populate the flag cache from API data so HTML scraping is not needed
    for ((boardCode, flags) in boardFlagsList) {
      boardFlagInfoRepository.prePopulateBoardFlags(
        BoardDescriptor.create(siteDescriptor, boardCode),
        flags
      )
    }

    return SiteBoards.Result.Success(siteDescriptor, list)
  }

  @Throws(IOException::class)
  private fun readBoardEntry(reader: JsonReader): Pair<ChanBoard?, List<LoadBoardFlagsUseCase.FlagInfo>?> {
    var parsedFlags: List<LoadBoardFlagsUseCase.FlagInfo>? = null
    val board = reader.jsonObject {
      val board = BoardBuilder(siteDescriptor)

      while (hasNext()) {
        when (nextName()) {
          "title" -> board.name = nextString()
          "board" -> board.code = nextString()
          "ws_board" -> board.workSafe = nextInt() == 1
          "per_page" -> board.perPage = nextInt()
          "pages" -> board.pages = nextInt()
          "max_filesize" -> board.maxFileSize = nextInt()
          "max_webm_filesize" -> board.maxWebmSize = nextInt()
          "max_comment_chars" -> board.maxCommentChars = nextInt()
          "bump_limit" -> board.bumpLimit = nextInt()
          "image_limit" -> board.imageLimit = nextInt()
          "cooldowns" -> readCooldowns(this, board)
          "spoilers" -> board.spoilers = nextInt() == 1
          "custom_spoilers" -> board.customSpoilers = nextInt()
          "user_ids" -> board.userIds = nextInt() == 1
          "country_flags" -> board.countryFlags = nextInt() == 1
          "board_flags" -> {
            board.countryFlags = true
            parsedFlags = readBoardFlags(this)
          }
          "meta_description" -> board.description = nextString()
          else -> skipValue()
        }
      }

      board.maxMediaWidth = 10000
      board.maxMediaHeight = 10000

      if (board.hasMissingInfo()) {
        return@jsonObject null
      }

      return@jsonObject board.toChanBoard(boardManager.byBoardDescriptor(board.boardDescriptor()))
    }

    // Post-process: add icon URLs now that we have the board code
    if (board != null && parsedFlags != null) {
      val boardCode = board.boardDescriptor.boardCode
      parsedFlags = parsedFlags!!.map { flagInfo ->
        if (flagInfo.flagKey == "0") flagInfo
        else flagInfo.copy(iconUrl = "https://s.4cdn.org/image/flags/$boardCode/${flagInfo.flagKey.lowercase()}.gif")
      }
    }

    return board to parsedFlags
  }

  private fun readBoardFlags(reader: JsonReader): List<LoadBoardFlagsUseCase.FlagInfo> {
    val flags = mutableListOf<LoadBoardFlagsUseCase.FlagInfo>()
    // "No flag" is always the first option so users can clear their selection
    flags += LoadBoardFlagsUseCase.FlagInfo("0", "No flag")
    reader.jsonObject {
      while (hasNext()) {
        val flagKey = nextName()
        val flagDescription = nextString()
        if (flagKey.isNotBlank() && flagDescription.isNotBlank()) {
          // iconUrl will be set in readBoardEntry after boardCode is known
          flags += LoadBoardFlagsUseCase.FlagInfo(flagKey, flagDescription)
        }
      }
    }
    return flags
  }
  
  private fun readCooldowns(reader: JsonReader, boardBuilder: BoardBuilder) {
    reader.jsonObject {
      while (hasNext()) {
        when (nextName()) {
          "threads" -> boardBuilder.cooldownThreads = nextInt()
          "replies" -> boardBuilder.cooldownReplies = nextInt()
          "images" -> boardBuilder.cooldownImages = nextInt()
          else -> skipValue()
        }
      }
    }
  }
  
  companion object {
    private const val TAG = "Chan4BoardsRequest"
  }
}