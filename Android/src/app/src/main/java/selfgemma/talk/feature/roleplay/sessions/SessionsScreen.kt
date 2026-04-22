package selfgemma.talk.feature.roleplay.sessions

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import selfgemma.talk.AppTopBar
import selfgemma.talk.R
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.feature.roleplay.common.RoleAvatar
import selfgemma.talk.performance.TrackPerformanceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
  onOpenSession: (String) -> Unit,
  onOpenRoleCatalog: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenModelLibrary: () -> Unit,
  modifier: Modifier = Modifier,
  showFab: Boolean = true,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  viewModel: SessionsViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsState()
  LaunchedEffect(uiState.statusMessage) {
    uiState.statusMessage?.let { statusMessage ->
      Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
    }
  }
  var pendingDeleteSessionId by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingImportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingExportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
  var expandedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      val sessionId = pendingImportSessionId
      pendingImportSessionId = null
      if (sessionId != null && uri != null) {
        viewModel.importChatJsonl(sessionId = sessionId, uri = uri.toString())
      }
    }
  val exportLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri: Uri? ->
      val sessionId = pendingExportSessionId
      pendingExportSessionId = null
      if (sessionId != null && uri != null) {
        viewModel.exportChatJsonl(sessionId = sessionId, uri = uri.toString())
      }
    }

  TrackPerformanceState(
    key = "SessionsList",
    value = if (listState.isScrollInProgress) "scrolling" else null,
  )

  Scaffold(
    modifier = modifier,
    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    topBar = {
      AppTopBar(
        title = stringResource(R.string.tab_messages),
        rightAction =
          AppBarAction(actionType = AppBarActionType.MENU, actionFn = onOpenRoleCatalog),
      )
    },
    floatingActionButton = {
      if (showFab) {
        FloatingActionButton(onClick = onOpenRoleCatalog) {
          Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.sessions_new_session))
        }
      }
    },
  ) { innerPadding ->
    val combinedPadding =
      PaddingValues(
        top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
        start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
      )

    Box(modifier = Modifier.fillMaxSize().padding(combinedPadding)) {
      if (uiState.loading) {
        EmptySessionsState(
          title = stringResource(R.string.sessions_loading),
          onOpenRoleCatalog = onOpenRoleCatalog,
          onOpenModelLibrary = onOpenModelLibrary,
        )
        return@Box
      }

      if (uiState.sessions.isEmpty()) {
        EmptySessionsState(
          title = stringResource(R.string.sessions_empty_title),
          onOpenRoleCatalog = onOpenRoleCatalog,
          onOpenModelLibrary = onOpenModelLibrary,
        )
        return@Box
      }

      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        uiState.errorMessage?.let { errorMessage ->
          item {
            Text(
              errorMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        items(uiState.sessions, key = { it.id }) { session ->
          SessionCard(
            session = session,
            isExpanded = expandedSessionId == session.id,
            onExpandChange = { shouldExpand ->
              expandedSessionId = if (shouldExpand) session.id else null
            },
            onOpen = { onOpenSession(session.id) },
            onImportChat = {
              pendingImportSessionId = session.id
              importLauncher.launch("*/*")
            },
            onExportChat = {
              pendingExportSessionId = session.id
              val fileName =
                session.roleName.ifBlank { "session" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
              exportLauncher.launch("${fileName}-${session.id.take(8)}.jsonl")
            },
            onExportDebugBundle = {
              expandedSessionId = null
              viewModel.exportDebugBundle(session.id)
            },
            onTogglePin = {
              expandedSessionId = null
              viewModel.togglePin(session.id)
            },
            onArchive = { viewModel.archiveSession(session.id) },
            onDelete = { pendingDeleteSessionId = session.id },
          )
        }
      }

    }

    val sessionToDelete = uiState.sessions.firstOrNull { it.id == pendingDeleteSessionId }
    if (sessionToDelete != null) {
      AlertDialog(
        onDismissRequest = { pendingDeleteSessionId = null },
        title = { Text(stringResource(R.string.sessions_delete_title)) },
        text = {
          Text(stringResource(R.string.sessions_delete_content, sessionToDelete.roleName))
        },
        confirmButton = {
          androidx.compose.material3.FilledTonalButton(
            onClick = {
              viewModel.deleteSession(sessionToDelete.id)
              pendingDeleteSessionId = null
              expandedSessionId = null
            },
          ) {
            Text(stringResource(R.string.delete))
          }
        },
        dismissButton = {
          OutlinedButton(onClick = { pendingDeleteSessionId = null }) {
            Text(stringResource(R.string.cancel))
          }
        },
      )
    }
  }
}

@Composable
private fun SessionCard(
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
  var offsetX by remember(isExpanded) { mutableFloatStateOf(if (isExpanded) SESSION_CARD_EXPANDED_OFFSET else 0f) }
  val context = LocalContext.current

  val animatedOffsetX by animateFloatAsState(
    targetValue = offsetX,
    animationSpec =
      androidx.compose.animation.core.spring(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
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
    Row(
      modifier =
        Modifier
          .matchParentSize()
          .padding(start = 8.dp, end = 4.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalIconButton(
        onClick = onImportChat,
        modifier = Modifier.size(52.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
          ),
      ) {
        Icon(
          Icons.Rounded.FileUpload,
          contentDescription = stringResource(R.string.sessions_import_chat),
          modifier = Modifier.size(26.dp),
        )
      }
      Spacer(Modifier.width(8.dp))
      FilledTonalIconButton(
        onClick = onExportChat,
        modifier = Modifier.size(52.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          ),
      ) {
        Icon(
          Icons.Rounded.Download,
          contentDescription = stringResource(R.string.sessions_export_chat),
          modifier = Modifier.size(26.dp),
        )
      }
      Spacer(Modifier.width(8.dp))
      FilledTonalIconButton(
        onClick = onExportDebugBundle,
        modifier = Modifier.size(52.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      ) {
        Icon(
          Icons.Rounded.BugReport,
          contentDescription = stringResource(R.string.sessions_export_debug_bundle),
          modifier = Modifier.size(26.dp),
        )
      }
      Spacer(Modifier.width(8.dp))
      FilledTonalIconButton(
        onClick = onDelete,
        modifier = Modifier.size(52.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
          ),
      ) {
        Icon(
          Icons.Rounded.Delete,
          contentDescription = stringResource(R.string.delete),
          modifier = Modifier.size(26.dp),
        )
      }
      Spacer(Modifier.width(8.dp))
      FilledTonalIconButton(
        onClick = onArchive,
        modifier = Modifier.size(52.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
      ) {
        Icon(
          Icons.Rounded.Archive,
          contentDescription = stringResource(R.string.roles_builtin_title),
          modifier = Modifier.size(26.dp),
        )
      }
      Spacer(Modifier.width(8.dp))
      FilledTonalIconButton(
        onClick = onTogglePin,
        modifier = Modifier.size(52.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      ) {
        Icon(
          imageVector = if (session.pinned) Icons.Rounded.Close else Icons.Rounded.PushPin,
          contentDescription = stringResource(if (session.pinned) R.string.sessions_unpin else R.string.sessions_pin),
          modifier = Modifier.size(26.dp),
        )
      }
    }

    Box(
      modifier =
        Modifier
          .matchParentSize()
          .graphicsLayer {
            translationX = animatedOffsetX
          }
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
                val newOffset = (offsetX + dragAmount).coerceIn(SESSION_CARD_DRAG_MIN_OFFSET, 0f)
                offsetX = newOffset
              },
              onDragEnd = {
                when {
                  offsetX < SESSION_CARD_DRAG_THRESHOLD -> {
                    offsetX = SESSION_CARD_EXPANDED_OFFSET
                    onExpandChange(true)
                  }
                  else -> {
                    offsetX = 0f
                    onExpandChange(false)
                  }
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
                Row(
                  modifier =
                    Modifier
                      .clip(androidx.compose.foundation.shape.CircleShape)
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
            }
            Text(
              formatTime(session.updatedAt, context),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
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
  }
}

private const val SESSION_CARD_EXPANDED_OFFSET = -432f
private const val SESSION_CARD_DRAG_MIN_OFFSET = -452f
private const val SESSION_CARD_DRAG_THRESHOLD = -200f

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

@Composable
private fun EmptySessionsState(
  title: String,
  onOpenRoleCatalog: () -> Unit,
  onOpenModelLibrary: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(
      stringResource(R.string.sessions_empty_content),
      modifier = Modifier.padding(top = 12.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      androidx.compose.material3.FilledTonalButton(onClick = onOpenRoleCatalog) {
        Text(stringResource(R.string.sessions_choose_role))
      }
      androidx.compose.material3.OutlinedButton(onClick = onOpenModelLibrary) {
        Text(stringResource(R.string.sessions_model_library))
      }
    }
  }
}
