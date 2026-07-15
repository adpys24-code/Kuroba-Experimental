package com.github.k1rakishou.chan.features.reply.left

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.features.reply.data.ReplyLayoutState
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeCard
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeText
import com.github.k1rakishou.chan.ui.compose.components.kurobaClickable
import com.github.k1rakishou.chan.ui.compose.ktu

@Composable
internal fun ReplyFormattingButtons(
  replyLayoutEnabled: Boolean,
  replyLayoutState: ReplyLayoutState,
  onFlagSelectorClicked: (ChanDescriptor) -> Unit = {}
) {
  val postFormatterButtons by replyLayoutState.postFormatterButtons
  val hasFlagsToShow by replyLayoutState.hasFlagsToShow
  val currentFlag by replyLayoutState.flag

  if (postFormatterButtons.isEmpty() && !hasFlagsToShow) {
    return
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .wrapContentHeight(),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
  ) {
    FlowRow(
      modifier = Modifier
        .weight(1f)
        .wrapContentHeight(),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      postFormatterButtons.forEach { postFormatterButton ->
        key(postFormatterButton.openTag) {
          KurobaComposeCard(
            modifier = Modifier
              .wrapContentHeight()
              .wrapContentWidth()
              .kurobaClickable(
                enabled = replyLayoutEnabled,
                bounded = true,
                onClick = { replyLayoutState.insertTags(postFormatterButton) }
              )
              .padding(vertical = 6.dp, horizontal = 10.dp),
            shape = remember { RoundedCornerShape(4.dp) },
            elevation = 0.dp
          ) {
            KurobaComposeText(
              text = postFormatterButton.title,
              fontSize = 14.ktu.fixedSize()
            )
          }
        }
      }
    }

    if (hasFlagsToShow) {
      val flagLabel = if (currentFlag == null || currentFlag!!.flagKey == "0") {
        stringResource(id = R.string.reply_flag_selector_default_label)
      } else {
        "[${currentFlag!!.flagKey}]"
      }
      KurobaComposeCard(
        modifier = Modifier
          .wrapContentHeight()
          .wrapContentWidth()
          .kurobaClickable(
            enabled = replyLayoutEnabled,
            bounded = true,
            onClick = { onFlagSelectorClicked(replyLayoutState.chanDescriptor) }
          )
          .padding(vertical = 6.dp, horizontal = 10.dp),
        shape = remember { RoundedCornerShape(4.dp) },
        elevation = 0.dp
      ) {
        KurobaComposeText(
          text = flagLabel,
          fontSize = 14.ktu.fixedSize()
        )
      }
    }
  }
}