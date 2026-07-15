package com.github.k1rakishou.chan.features.setup.boards.selection

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.ui.compose.SiteIconElement
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeText
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeTextBarButton
import com.github.k1rakishou.chan.ui.compose.components.KurobaSearchInput
import com.github.k1rakishou.chan.ui.compose.components.kurobaClickable
import com.github.k1rakishou.chan.ui.compose.forEachTextValue
import com.github.k1rakishou.chan.ui.compose.ktu
import com.github.k1rakishou.chan.ui.compose.providers.LocalChanTheme
import com.github.k1rakishou.chan.ui.controller.base.BaseFloatingComposeController
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.chan.core.manager.ArchivesManager
import com.github.k1rakishou.chan.core.site.SiteConfiguration
import com.github.k1rakishou.model.data.site.SiteBoards
import com.github.k1rakishou.chan.utils.InputWithQuerySorter
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class LegacyBoardDropdownController(
  context: Context,
  private val currentCatalogDescriptor: ChanDescriptor.ICatalogDescriptor?,
  private val onBoardSelected: (ChanDescriptor.CatalogDescriptor) -> Unit,
  private val onOpenSitesSettings: () -> Unit
) : BaseFloatingComposeController(context) {

  @Inject
  lateinit var siteManagerLazy: Lazy<SiteManager>
  @Inject
  lateinit var boardManagerLazy: Lazy<BoardManager>
  @Inject
  lateinit var archivesManagerLazy: Lazy<ArchivesManager>

  private val archivesManager: ArchivesManager
    get() = archivesManagerLazy.get()


  private val siteManager: SiteManager
    get() = siteManagerLazy.get()
  private val boardManager: BoardManager
    get() = boardManagerLazy.get()

  override fun injectActivityDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  private sealed interface ListItem {
    data class SiteHeader(val siteDescriptor: SiteDescriptor) : ListItem
    data class Board(
      val descriptor: BoardDescriptor,
      val boardCode: String,
      val boardName: String?,
      val isSelected: Boolean
    ) : ListItem
    data class DirectNav(val boardCode: String, val siteDescriptor: SiteDescriptor) : ListItem
  }

  @Composable
  override fun BoxScope.BuildContent() {
    val chanTheme = LocalChanTheme.current
    val searchQueryState = rememberTextFieldState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(key1 = Unit) {
      searchQueryState.forEachTextValue { text ->
        query = text.toString()
      }
    }

    // boardsFetched flips true (on main thread) when all-boards data is ready, triggering recomposition
    var boardsFetched by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = Unit) {
      withContext(Dispatchers.IO) {
        val sitesToFetch = mutableListOf<com.github.k1rakishou.chan.core.site.Site>()
        siteManager.viewActiveSitesOrderedWhile { chanSiteData, site ->
          if (!archivesManager.isSiteArchive(chanSiteData.siteDescriptor)
            && !site.hasSiteFeature(SiteConfiguration.SiteFeature.CatalogComposition)) {
            sitesToFetch += site
          }
          true
        }
        for (site in sitesToFetch) {
          try { site.actions.loadBoardInfo().filterIsInstance<SiteBoards.Result>().first() } catch (_: Exception) {}
        }
      }
      // back on main thread — safe to update Compose state
      boardsFetched = true
    }

    fun buildActiveBoardsBySite(): List<Pair<SiteDescriptor, List<ListItem.Board>>> {
      val result = mutableListOf<Pair<SiteDescriptor, List<ListItem.Board>>>()
      siteManager.viewSitesOrdered { chanSiteData, _ ->
        val sd = chanSiteData.siteDescriptor
        val boards = mutableListOf<ListItem.Board>()
        boardManager.viewBoardsOrdered(
          boardViewMode = BoardManager.BoardViewMode.Active,
          siteDescriptor = sd
        ) { chanBoard ->
          val bd = chanBoard.boardDescriptor
          val isSelected = currentCatalogDescriptor
            ?.let { it is ChanDescriptor.CatalogDescriptor && it.boardDescriptor == bd }
            ?: false
          boards += ListItem.Board(
            descriptor = bd,
            boardCode = bd.boardCode,
            boardName = chanBoard.name?.takeIf { it.isNotBlank() },
            isSelected = isSelected
          )
          true
        }
        if (boards.isNotEmpty()) result += Pair(sd, boards)
        true
      }
      return result
    }

    fun searchAllBoardsBySite(q: String): List<Pair<SiteDescriptor, List<ListItem.Board>>> {
      val result = mutableListOf<Pair<SiteDescriptor, List<ListItem.Board>>>()
      siteManager.viewSitesOrdered { chanSiteData, _ ->
        val sd = chanSiteData.siteDescriptor
        val boards = mutableListOf<ListItem.Board>()
        // viewBoardsWhile iterates boardsMap directly — has ALL boards, not just active
        boardManager.viewBoardsWhile(
          boardViewMode = BoardManager.BoardViewMode.All,
          siteDescriptor = sd
        ) { chanBoard ->
          val bd = chanBoard.boardDescriptor
          val code = bd.boardCode
          val name = chanBoard.name ?: ""
          if (code.lowercase().contains(q) || name.lowercase().contains(q)) {
            val isSelected = currentCatalogDescriptor
              ?.let { it is ChanDescriptor.CatalogDescriptor && it.boardDescriptor == bd }
              ?: false
            boards += ListItem.Board(
              descriptor = bd,
              boardCode = code,
              boardName = name.takeIf { it.isNotBlank() },
              isSelected = isSelected
            )
          }
          true
        }
        if (boards.isNotEmpty()) {
          val sorted = InputWithQuerySorter.sort(boards, q) { it.boardCode }
          result += Pair(sd, sorted)
        }
        true
      }
      return result
    }

    val activeBoardsBySite = remember(boardsFetched) { buildActiveBoardsBySite() }
    val firstSiteDescriptor = remember(boardsFetched) { activeBoardsBySite.firstOrNull()?.first }

    val listItems = remember(query, boardsFetched) {
      val q = query.trim().lowercase()
      val result = mutableListOf<ListItem>()

      if (q.isNotEmpty()) {
        val code = q.filter { it.isLetterOrDigit() }
        if (code.isNotBlank() && firstSiteDescriptor != null) {
          result += ListItem.DirectNav(code, firstSiteDescriptor)
        }
      }

      val boardsBySite = if (q.isEmpty()) activeBoardsBySite else searchAllBoardsBySite(q)

      boardsBySite.forEach { (sd, boards) ->
        val filtered = boards
        if (filtered.isNotEmpty()) {
          result += ListItem.SiteHeader(sd)
          result += filtered
        }
      }
      result
    }

    // Fill height so the card anchors to top; empty space below is transparent and passes touches
    // through to the scrim (BaseFloatingComposeController's click-outside handler).
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight()
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .shadow(elevation = 8.dp)
          .background(chanTheme.backColorCompose)
      ) {
        // Header: search + Sites button
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          KurobaSearchInput(
            modifier = Modifier
              .weight(1f)
              .padding(end = 4.dp),
            color = chanTheme.textColorPrimaryCompose,
            searchQueryState = searchQueryState
          )
          KurobaComposeTextBarButton(
            text = stringResource(id = R.string.controller_boards_selection_sites),
            onClick = {
              pop()
              onOpenSitesSettings()
            }
          )
        }

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
        ) {
          items(
            items = listItems,
            key = { item ->
              when (item) {
                is ListItem.SiteHeader -> "header_${item.siteDescriptor.siteName}"
                is ListItem.Board -> "board_${item.descriptor}"
                is ListItem.DirectNav -> "direct_${item.boardCode}"
              }
            }
          ) { item ->
            when (item) {
              is ListItem.SiteHeader -> SiteHeaderRow(item.siteDescriptor)
              is ListItem.Board -> BoardRow(item)
              is ListItem.DirectNav -> DirectNavRow(item.boardCode, item.siteDescriptor)
            }
          }
        }
      }
    }
  }

  @Composable
  private fun SiteHeaderRow(siteDescriptor: SiteDescriptor) {
    val chanTheme = LocalChanTheme.current
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(modifier = Modifier.size(40.dp)) {
        SiteIconElement(siteDescriptor = siteDescriptor)
      }
      Spacer(modifier = Modifier.width(8.dp))
      KurobaComposeText(
        text = siteDescriptor.siteName,
        fontSize = 16.ktu,
        color = chanTheme.textColorPrimaryCompose,
        maxLines = 1
      )
      Spacer(modifier = Modifier.width(8.dp))
      Box(
        modifier = Modifier
          .weight(1f)
          .height(1.dp)
          .background(chanTheme.textColorSecondaryCompose.copy(alpha = 0.5f))
      )
    }
  }

  @Composable
  private fun BoardRow(item: ListItem.Board) {
    val chanTheme = LocalChanTheme.current
    val bgColor = if (item.isSelected) {
      chanTheme.postHighlightedColorCompose
    } else {
      chanTheme.backColorCompose
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .background(bgColor)
        .kurobaClickable(bounded = true, onClick = {
          val catalogDescriptor = ChanDescriptor.CatalogDescriptor.create(item.descriptor)
          onBoardSelected(catalogDescriptor)
          pop()
        })
        .padding(all = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      val displayText = if (item.boardName != null) {
        "/${item.boardCode}/ — ${item.boardName}"
      } else {
        "/${item.boardCode}/"
      }
      KurobaComposeText(
        text = displayText,
        fontSize = 18.ktu,
        color = chanTheme.textColorPrimaryCompose,
        maxLines = 3
      )
    }
  }

  @Composable
  private fun DirectNavRow(boardCode: String, siteDescriptor: SiteDescriptor) {
    val chanTheme = LocalChanTheme.current
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .background(chanTheme.accentColorCompose.copy(alpha = 0.12f))
        .kurobaClickable(bounded = true, onClick = {
          val boardDescriptor = BoardDescriptor.create(siteDescriptor, boardCode)
          val catalogDescriptor = ChanDescriptor.CatalogDescriptor.create(boardDescriptor)
          onBoardSelected(catalogDescriptor)
          pop()
        })
        .padding(all = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      KurobaComposeText(
        text = stringResource(id = R.string.board_selection_open_board_directly, boardCode),
        fontSize = 18.ktu,
        color = chanTheme.accentColorCompose,
        maxLines = 1
      )
    }
  }
}
