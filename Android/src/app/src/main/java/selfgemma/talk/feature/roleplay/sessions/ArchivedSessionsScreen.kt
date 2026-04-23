package selfgemma.talk.feature.roleplay.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import selfgemma.talk.AppTopBar
import selfgemma.talk.R
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.feature.roleplay.common.RoleAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedSessionsScreen(
  navigateUp: () -> Unit,
  onSessionRestored: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  viewModel: ArchivedSessionsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  Scaffold(
    modifier = modifier,
    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    topBar = {
      AppTopBar(
        title = stringResource(R.string.settings_archived_sessions_title),
        leftAction =
          AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = navigateUp),
      )
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
      when {
        uiState.loading -> {
          ArchivedSessionsEmptyState(title = stringResource(R.string.sessions_loading))
        }
        uiState.sessions.isEmpty() -> {
          ArchivedSessionsEmptyState(title = stringResource(R.string.archived_sessions_empty))
        }
        else -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(uiState.sessions, key = { it.id }) { session ->
              ArchivedSessionCard(
                session = session,
                onRestore = { viewModel.restoreSession(session.id, onSessionRestored) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ArchivedSessionCard(
  session: SessionListItemUiState,
  onRestore: () -> Unit,
) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    ListItem(
      headlineContent = {
        Text(
          text = session.roleName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      supportingContent = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          if (session.title.isNotBlank()) {
            Text(
              text = session.title,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Text(
            text = session.lastMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      },
      leadingContent = {
        RoleAvatar(
          name = session.roleName,
          avatarUri = session.avatarUri,
          modifier = Modifier.size(48.dp),
        )
      },
      trailingContent = {
        Button(onClick = onRestore) {
          Text(stringResource(R.string.archived_sessions_restore))
        }
      },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
  }
}

@Composable
private fun ArchivedSessionsEmptyState(title: String) {
  Box(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
