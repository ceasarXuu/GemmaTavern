package selfgemma.talk.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun AppPreferenceCard(
  title: String,
  summary: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  trailingContent: (@Composable () -> Unit)? = null,
) {
  OutlinedCard(modifier = modifier.fillMaxWidth()) {
    ListItem(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(enabled = enabled, onClick = onClick),
      headlineContent = {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
        )
      },
      supportingContent = {
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      trailingContent = trailingContent,
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
  }
}

@Composable
fun AppPreferenceSwitchCard(
  title: String,
  summary: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  OutlinedCard(modifier = modifier.fillMaxWidth()) {
    ListItem(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(enabled = enabled, onClick = { onCheckedChange(!checked) }),
      headlineContent = {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
        )
      },
      supportingContent = {
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      trailingContent = {
        Switch(
          checked = checked,
          onCheckedChange = onCheckedChange,
          enabled = enabled,
        )
      },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
  }
}

@Composable
fun AppSingleChoiceRow(
  title: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
) {
  ListItem(
    modifier =
      modifier
        .fillMaxWidth()
        .selectable(
          selected = selected,
          onClick = onClick,
        ),
    headlineContent = {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
      )
    },
    supportingContent =
      summary?.let {
        {
          Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
    trailingContent = {
      RadioButton(
        selected = selected,
        onClick = null,
      )
    },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
  )
}
