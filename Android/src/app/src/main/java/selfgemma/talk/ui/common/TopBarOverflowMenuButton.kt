package selfgemma.talk.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R

@Composable
fun TopBarOverflowMenuButton(
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  menuContent: @Composable ColumnScope.() -> Unit,
) {
  Box(modifier = modifier) {
    IconButton(onClick = { onExpandedChange(true) }) {
      Icon(
        imageVector = Icons.Rounded.MoreVert,
        contentDescription = stringResource(R.string.cd_menu),
      )
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { onExpandedChange(false) },
      content = menuContent,
    )
  }
}
