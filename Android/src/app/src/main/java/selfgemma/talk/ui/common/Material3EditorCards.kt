package selfgemma.talk.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R

@Composable
fun AppEditorCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  OutlinedCard(
    modifier = modifier.fillMaxWidth(),
    colors =
      CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surface,
      ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      content = content,
    )
  }
}

@Composable
fun AppEditorSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  supportingText: String? = null,
  required: Boolean = false,
  onShowHelp: (() -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.weight(1f),
      )
      if (required) {
        AppRequiredBadge()
      }
      actions?.invoke(this)
      if (onShowHelp != null) {
        IconButton(onClick = onShowHelp) {
          Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.cd_help),
          )
        }
      }
    }
    supportingText?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun AppRequiredBadge() {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    shape = MaterialTheme.shapes.small,
  ) {
    Text(
      text = stringResource(R.string.role_editor_required_badge),
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Composable
fun AppEditorInfoCard(
  message: String,
  modifier: Modifier = Modifier,
) {
  OutlinedCard(
    modifier = modifier.fillMaxWidth(),
    colors =
      CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
  ) {
    Text(
      text = message,
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
fun AppEditorStatusCard(
  message: String,
  isError: Boolean,
  modifier: Modifier = Modifier,
) {
  val containerColor =
    if (isError) {
      MaterialTheme.colorScheme.errorContainer
    } else {
      MaterialTheme.colorScheme.secondaryContainer
    }
  val contentColor =
    if (isError) {
      MaterialTheme.colorScheme.onErrorContainer
    } else {
      MaterialTheme.colorScheme.onSecondaryContainer
    }

  OutlinedCard(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
  ) {
    Text(
      text = message,
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = contentColor,
    )
  }
}
