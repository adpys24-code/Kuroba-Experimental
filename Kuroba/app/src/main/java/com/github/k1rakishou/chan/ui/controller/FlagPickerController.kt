package com.github.k1rakishou.chan.ui.controller

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.usecase.LoadBoardFlagsUseCase
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeCard
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeText
import com.github.k1rakishou.chan.ui.compose.components.kurobaClickable
import com.github.k1rakishou.chan.ui.compose.ktu
import com.github.k1rakishou.chan.ui.controller.base.BaseFloatingComposeController

class FlagPickerController(
  context: Context,
  private val flagInfoList: List<LoadBoardFlagsUseCase.FlagInfo>,
  private val onFlagSelected: (LoadBoardFlagsUseCase.FlagInfo) -> Unit,
  private val onDismissed: () -> Unit
) : BaseFloatingComposeController(context) {

  override fun injectActivityDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override fun onDestroy() {
    super.onDestroy()
    onDismissed()
  }

  @Composable
  override fun BoxScope.BuildContent() {
    KurobaComposeCard(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 480.dp)
    ) {
      LazyColumn(
        modifier = Modifier.padding(vertical = 4.dp)
      ) {
        items(flagInfoList) { flagInfo ->
          FlagRow(
            flagInfo = flagInfo,
            onClick = {
              onFlagSelected(flagInfo)
              pop()
            }
          )
        }
      }
    }
  }

  @Composable
  private fun FlagRow(
    flagInfo: LoadBoardFlagsUseCase.FlagInfo,
    onClick: () -> Unit
  ) {
    val context = LocalContext.current
    val iconUrl = flagInfo.iconUrl

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(40.dp)
        .kurobaClickable(bounded = true, onClick = onClick)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (iconUrl != null) {
        val imageRequest = remember(iconUrl) {
          ImageRequest.Builder(context)
            .data(iconUrl)
            .build()
        }
        Box(
          modifier = Modifier.size(width = 20.dp, height = 14.dp),
          contentAlignment = Alignment.Center
        ) {
          AsyncImage(
            modifier = Modifier.matchParentSize(),
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
      } else {
        Spacer(modifier = Modifier.width(28.dp))
      }

      KurobaComposeText(
        text = flagInfo.asUserReadableString(),
        fontSize = 14.ktu.fixedSize()
      )
    }
  }
}
