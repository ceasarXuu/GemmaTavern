/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.ui.common

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.preview.MODEL_TEST1
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import selfgemma.talk.R
import selfgemma.talk.data.BooleanSwitchConfig
import selfgemma.talk.data.BottomSheetSelectorConfig
import selfgemma.talk.data.BottomSheetSelectorItem
import selfgemma.talk.data.Config
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.LabelConfig
import selfgemma.talk.data.NumberSliderConfig
import selfgemma.talk.data.SegmentedButtonConfig
import selfgemma.talk.data.ValueType
import selfgemma.talk.ui.theme.labelSmallNarrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGConfigDialog"

private data class Tab(@StringRes val labelResId: Int)

private val TABS =
  listOf(
    Tab(labelResId = R.string.config_dialog_tab_model_configs),
    Tab(labelResId = R.string.config_dialog_tab_system_prompt),
  )

/**
 * Displays a configuration dialog allowing users to modify settings through various input controls.
 */
@Composable
fun ConfigDialog(
  title: String,
  configs: List<Config>,
  initialValues: Map<String, Any>,
  onDismissed: () -> Unit,
  onOk: (values: Map<String, Any>, oldSystemPrompt: String, newSystemPrompt: String) -> Unit,
  okBtnLabel: String = "OK",
  subtitle: String = "",
  showCancel: Boolean = true,
  showSystemPromptEditorTab: Boolean = false,
  defaultSystemPrompt: String = "",
  curSystemPrompt: String = "",
) {
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }
  val interactionSource = remember { MutableInteractionSource() }
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  val savedSystemPrompt = remember { curSystemPrompt }
  var systemPrompt by remember { mutableStateOf(curSystemPrompt) }

  Dialog(onDismissRequest = onDismissed) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth()
          .clickable(
            interactionSource = interactionSource,
            indication = null, // Disable the ripple effect
          ) {
            focusManager.clearFocus()
          }
          .imePadding(),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Dialog title and subtitle.
        Column {
          Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
          )
          // Subtitle.
          if (subtitle.isNotEmpty()) {
            Text(
              subtitle,
              style = labelSmallNarrow,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.offset(y = (-6).dp),
            )
          }
        }

        // Tab.
        if (showSystemPromptEditorTab) {
          PrimaryTabRow(selectedTabIndex = selectedTabIndex, containerColor = Color.Transparent) {
            TABS.forEachIndexed { index, tab ->
              Tab(
                selected = selectedTabIndex == index,
                onClick = { selectedTabIndex = index },
                text = {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    val titleColor =
                      if (selectedTabIndex == index) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(stringResource(tab.labelResId), color = titleColor)
                  }
                },
              )
            }
          }
        }

        if (selectedTabIndex == 0) {
          // List of config rows.
          Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            ConfigEditorsPanel(configs = configs, values = values)
          }
        } else if (selectedTabIndex == 1) {
          OutlinedTextField(
            value = systemPrompt,
            modifier = Modifier.weight(1f, fill = false),
            textStyle = MaterialTheme.typography.bodySmall,
            onValueChange = { systemPrompt = it },
          )
        }

        // Button row.
        Row(
          horizontalArrangement =
            if (showSystemPromptEditorTab && selectedTabIndex == 1) {
              Arrangement.SpaceBetween
            } else {
              Arrangement.End
            },
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = 8.dp),
        ) {
          // Restore default button to restore system prompt.
          if (showSystemPromptEditorTab && selectedTabIndex == 1) {
            OutlinedButton(
              onClick = { systemPrompt = defaultSystemPrompt },
              contentPadding = SMALL_BUTTON_CONTENT_PADDING,
            ) {
              Text(stringResource(R.string.restore_default))
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Cancel button.
            if (showCancel) {
              TextButton(onClick = { onDismissed() }) { Text("Cancel") }
            }

            // Ok button
            Button(
              onClick = {
                Log.d(TAG, "Values from dialog: $values")
                onOk(values.toMap(), savedSystemPrompt, systemPrompt)
              }
            ) {
              Text(okBtnLabel)
            }
          }
        }
      }
    }
  }
}

