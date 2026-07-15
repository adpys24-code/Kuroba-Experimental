package com.github.k1rakishou.chan.ui.compose.badge

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeText
import com.github.k1rakishou.chan.ui.compose.ktu
import com.github.k1rakishou.chan.ui.compose.providers.LocalChanTheme
import com.github.k1rakishou.chan.ui.helper.PinHelper
import com.github.k1rakishou.chan.utils.appDependencies
import com.github.k1rakishou.core_themes.ThemeEngine

sealed class MenuItemBadge {
  data object Dot : MenuItemBadge()

  data class Counter(
    val counter: Int,
    val highlight: Boolean = false
  ) : MenuItemBadge()
}

@Composable
fun BoxScope.BottomPanelBadge(menuItemBadge: MenuItemBadge) {
  val chanTheme = LocalChanTheme.current
  val kurobaSettings = appDependencies().kurobaSettings

  when (menuItemBadge) {
    MenuItemBadge.Dot -> {
      Box(
        modifier = Modifier
          .size(10.dp)
          .offset(x = 8.dp, y = 8.dp)
          .background(color = chanTheme.accentColorCompose, shape = CircleShape)
          .align(Alignment.TopCenter)
      )
    }
    is MenuItemBadge.Counter -> {
      val counter by animateIntAsState(
        targetValue = menuItemBadge.counter,
        label = "Menu item badge counter animation"
      )
      val highlight = menuItemBadge.highlight
      val watchEnabled by kurobaSettings.application.watchEnabled.listen().collectAsState(initial = false)

      val backgroundColor = if (!watchEnabled) {
        chanTheme.bookmarkCounterNotWatchingColorCompose
      } else if (highlight) {
        chanTheme.bookmarkCounterHasRepliesColorCompose
      } else {
        chanTheme.bookmarkCounterNormalColorCompose
      }

      val textColor = remember(key1 = backgroundColor) {
        if (ThemeEngine.isDarkColor(backgroundColor)) {
          Color.White
        } else {
          Color.Black
        }
      }

      val counterText = remember(key1 = counter) { PinHelper.getShortUnreadCount(counter) }

      val xOffset = when (counterText.length) {
        1 -> 3.dp
        2 -> 3.dp
        3 -> 3.dp
        else -> 5.dp
      }

      KurobaComposeText(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset(x = xOffset, y = 2.dp)
          .drawWithContent {
            drawRoundRect(
              color = backgroundColor,
              alpha = 0.92f,
              cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )
            drawContent()
          }
          .padding(horizontal = 3.dp, vertical = 1.dp),
        text = counterText,
        color = textColor,
        fontSize = 8.ktu.fixedSize()
      )
    }
  }
}