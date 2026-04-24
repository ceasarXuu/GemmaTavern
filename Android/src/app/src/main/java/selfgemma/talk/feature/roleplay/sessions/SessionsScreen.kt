package selfgemma.talk.feature.roleplay.sessions

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import selfgemma.talk.AppTopBar
import selfgemma.talk.R
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
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
