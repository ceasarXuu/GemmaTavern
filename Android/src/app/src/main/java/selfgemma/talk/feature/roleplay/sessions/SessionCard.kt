package selfgemma.talk.feature.roleplay.sessions

import android.util.Log
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.feature.roleplay.common.RoleAvatar

private const val TAG = "SessionCard"

@Composable
internal fun SessionCard(
  session: SessionListItemUiState,
  isExpanded: Boolean,
  onExpandChange: (Boolean) -> Unit,
  onOpen: () -> Unit,
  onImportChat: () -> Unit,
  onExportChat: () -> Unit,
  onExportDebugBundle: () -> Unit,
  onTogglePin: () -> Unit,
  onArchive: () -> Unit,
  onDelete: () -> Unit,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val expandedOffsetPx = with(density) { -SESSION_CARD_ACTION_REVEAL_WIDTH_DP.toPx() }
  val dragMinOffsetPx = with(density) { -SESSION_CARD_ACTION_DRAG_LIMIT_DP.toPx() }
  val dragThresholdPx = with(density) { -SESSION_CARD_ACTION_DRAG_THRESHOLD_DP.toPx() }
  var offsetX by remember(isExpanded, expandedOffsetPx) {
    mutableFloatStateOf(if (isExpanded) expandedOffsetPx else 0f)
  }

  val animatedOffsetX by animateFloatAsState(
    targetValue = offsetX,
    animationSpec =
      spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
      ),
    label = "offset",
  )

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(80.dp)
        .clip(MaterialTheme.shapes.large)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    SessionCardActions(
      session = session,
      onImportChat = onImportChat,
      onExportChat = onExportChat,
      onExportDebugBundle = onExportDebugBundle,
      onTogglePin = onTogglePin,
      onArchive = onArchive,
      onDelete = onDelete,
    )

    Box(
      modifier =
        Modifier
          .matchParentSize()
          .graphicsLayer { translationX = animatedOffsetX }
          .background(MaterialTheme.colorScheme.surface)
          .pointerInput(session.id, isExpanded) {
            detectHorizontalDragGestures(
              onDragStart = {
                if (!isExpanded) {
                  offsetX = 0f
                } else {
                  onExpandChange(false)
                  offsetX = 0f
                }
              },
              onHorizontalDrag = { change, dragAmount ->
                change.consume()
                offsetX = (offsetX + dragAmount).coerceIn(dragMinOffsetPx, 0f)
              },
              onDragEnd = {
                if (offsetX < dragThresholdPx) {
                  offsetX = expandedOffsetPx
                  Log.d(
                    TAG,
                    "session card action rail expanded sessionId=${session.id} " +
                      "revealWidthPx=${-expandedOffsetPx}"
                  )
                  onExpandChange(true)
                } else {
                  offsetX = 0f
                  Log.d(TAG, "session card action rail collapsed sessionId=${session.id}")
                  onExpandChange(false)
                }
              },
            )
          }
          .clickable(
            interactionSource =
              remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            enabled = !isExpanded,
          ) {
            onOpen()
          },
    ) {
      SessionCardContent(session = session, updatedAt = formatTime(session.updatedAt, context))
    }
  }
}

@Composable
private fun SessionCardActions(
  session: SessionListItemUiState,
  onImportChat: () -> Unit,
  onExportChat: () -> Unit,
  onExportDebugBundle: () -> Unit,
  onTogglePin: () -> Unit,
  onArchive: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp),
    horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SessionActionButton(
      onClick = onImportChat,
      icon = Icons.Rounded.FileUpload,
      contentDescription = stringResource(R.string.sessions_import_chat),
      containerColor = MaterialTheme.colorScheme.tertiaryContainer,
      contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    SessionActionButtonSpacer()
    SessionActionButton(
      onClick = onExportChat,
      icon = Icons.Rounded.Download,
      contentDescription = stringResource(R.string.sessions_export_chat),
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    SessionActionButtonSpacer()
    SessionActionButton(
      onClick = onExportDebugBundle,
      icon = Icons.Rounded.BugReport,
      contentDescription = stringResource(R.string.sessions_export_debug_bundle),
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    SessionActionButtonSpacer()
    SessionActionButton(
      onClick = onDelete,
      icon = Icons.Rounded.Delete,
      contentDescription = stringResource(R.string.delete),
      containerColor = MaterialTheme.colorScheme.errorContainer,
      contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    SessionActionButtonSpacer()
    SessionActionButton(
      onClick = onArchive,
      icon = Icons.Rounded.Archive,
      contentDescription = stringResource(R.string.roles_builtin_title),
      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SessionActionButtonSpacer()
    SessionActionButton(
      onClick = onTogglePin,
      icon = if (session.pinned) Icons.Rounded.Close else Icons.Rounded.PushPin,
      contentDescription = stringResource(if (session.pinned) R.string.sessions_unpin else R.string.sessions_pin),
      containerColor = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
  }
}

@Composable
private fun SessionActionButton(
  onClick: () -> Unit,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String,
  containerColor: androidx.compose.ui.graphics.Color,
  contentColor: androidx.compose.ui.graphics.Color,
) {
  FilledTonalIconButton(
    onClick = onClick,
    modifier = Modifier.size(52.dp),
    colors =
      IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = containerColor,
        contentColor = contentColor,
      ),
  ) {
    Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(26.dp))
  }
}

@Composable
private fun SessionActionButtonSpacer() {
  Spacer(Modifier.width(8.dp))
}

@Composable
private fun SessionCardContent(session: SessionListItemUiState, updatedAt: String) {
  Row(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RoleAvatar(
      name = session.roleName,
      avatarUri = session.avatarUri,
      modifier = Modifier.size(56.dp),
    )
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      SessionCardTitleRow(session = session, updatedAt = updatedAt)
      Text(
        session.lastMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun SessionCardTitleRow(session: SessionListItemUiState, updatedAt: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        session.roleName,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      if (session.pinned) {
        SessionPinnedBadge()
      }
    }
    Text(
      updatedAt,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SessionPinnedBadge() {
  Row(
    modifier =
      Modifier
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(
      Icons.Rounded.PushPin,
      contentDescription = stringResource(R.string.sessions_pin),
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(14.dp),
    )
    Text(
      text = stringResource(R.string.sessions_pin),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
  }
}

private val SESSION_CARD_ACTION_REVEAL_WIDTH_DP = 196.dp
private val SESSION_CARD_ACTION_DRAG_LIMIT_DP = 212.dp
private val SESSION_CARD_ACTION_DRAG_THRESHOLD_DP = 96.dp

private fun formatTime(timestamp: Long, context: android.content.Context): String {
  val now = System.currentTimeMillis()
  val diff = now - timestamp
  return when {
    diff < 60_000L -> context.getString(R.string.sessions_minutes_ago, 0).replace("0", "")
    diff < 3_600_000L -> context.getString(R.string.sessions_minutes_ago, diff / 60_000L)
    diff < 86_400_000L -> context.getString(R.string.sessions_hours_ago, diff / 3_600_000L)
    diff < 604_800_000L -> context.getString(R.string.sessions_days_ago, diff / 86_400_000L)
    else ->
      java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
  }
}
