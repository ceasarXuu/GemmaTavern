package selfgemma.talk.feature.roleplay.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.feature.roleplay.common.RoleAvatar

@Composable
internal fun ChatMessageBubble(
  message: Message,
  roleName: String,
  roleAvatarUri: String?,
  userName: String,
  userAvatarUri: String?,
  animateOnEnter: Boolean,
  onRoleAvatarClick: (() -> Unit)?,
  onUserAvatarClick: (() -> Unit)?,
  onMessageLongPress: ((Message) -> Unit)?,
) {
  val isUser = message.side == MessageSide.USER
  val isImageMessage = message.kind == MessageKind.IMAGE
  val bubbleShape =
    if (isUser) {
      RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp)
    } else {
      RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 8.dp, bottomEnd = 24.dp)
    }
  val bubbleColor =
    if (isUser) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerHigh
    }
  val bubbleTextColor =
    if (isUser) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSurface
    }
  val content: @Composable () -> Unit = {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
      verticalAlignment = Alignment.Top,
    ) {
      if (!isUser) {
        RoleAvatar(
          name = roleName,
          avatarUri = roleAvatarUri,
          onClick = onRoleAvatarClick,
          modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
      }

      Column(
        modifier = Modifier.widthIn(max = if (isImageMessage) 280.dp else 340.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = if (isUser) userName else roleName,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 4.dp),
        )

        Surface(
          modifier =
            if (message.supportsRoleplayActions() && onMessageLongPress != null) {
              Modifier.combinedClickable(
                onClick = {},
                onLongClick = { onMessageLongPress(message) },
              )
            } else {
              Modifier
            },
          shape = bubbleShape,
          tonalElevation = 0.dp,
          color = bubbleColor,
        ) {
          if (message.status == MessageStatus.STREAMING && message.content.isBlank() && message.kind == MessageKind.TEXT) {
            TypingIndicator()
          } else if (isImageMessage) {
            RoleplayImageMessageBody(
              message = message,
              imageShape = bubbleShape,
            )
          } else {
            Column(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              RenderRoleplayMessageBody(
                message = message,
                isUser = isUser,
                textColor = bubbleTextColor,
              )
            }
          }
        }

        if (isUser) {
          Text(
            text = stringResource(R.string.chat_message_read),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }

      if (isUser) {
        Spacer(modifier = Modifier.width(8.dp))
        RoleAvatar(
          name = userName,
          avatarUri = userAvatarUri,
          onClick = onUserAvatarClick,
          modifier = Modifier.size(32.dp),
        )
      }
    }
  }

  if (animateOnEnter) {
    AnimatedVisibility(
      visible = true,
      enter = fadeIn(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioMediumBouncy,
        )
      ) + slideInHorizontally(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        initialOffsetX = { if (isUser) it / 3 else -it / 3 },
      ) + scaleIn(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        initialScale = 0.9f,
      ),
      exit = fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
      content()
    }
  } else {
    content()
  }
}

internal fun shouldKeepLatestMessageVisible(listState: LazyListState, latestItemIndex: Int): Boolean {
  val layoutInfo = listState.layoutInfo
  val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
  val bottomGap =
    lastVisibleItem.offset + lastVisibleItem.size - layoutInfo.viewportEndOffset
  return lastVisibleItem.index >= latestItemIndex - 1 && bottomGap < 120
}

internal suspend fun scrollToItem(listState: LazyListState, itemIndex: Int, animate: Boolean) {
  if (itemIndex < 0) {
    return
  }

  if (animate) {
    listState.animateScrollToItem(index = itemIndex)
  } else {
    listState.scrollToItem(index = itemIndex)
  }
}

internal fun calculateLatestListItemIndex(
  itemCount: Int,
): Int {
  if (itemCount == 0) {
    return -1
  }

  return itemCount - 1
}
