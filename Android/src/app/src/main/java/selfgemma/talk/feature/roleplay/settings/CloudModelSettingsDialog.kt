package selfgemma.talk.feature.roleplay.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import selfgemma.talk.R
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudModelPresets
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.ui.common.AppSingleChoiceRow

@Composable
fun CloudModelSettingsDialog(
  initialConfig: CloudModelConfig,
  apiKeySaved: Boolean,
  onDismiss: () -> Unit,
  onSave: (CloudModelConfig, String) -> Unit,
  onClearApiKey: (CloudProviderId) -> Unit,
) {
  var enabled by remember(initialConfig) { mutableStateOf(initialConfig.enabled) }
  var selectedProvider by remember(initialConfig) { mutableStateOf(initialConfig.providerId) }
  var modelName by remember(initialConfig) { mutableStateOf(initialConfig.modelName) }
  var apiKeyInput by remember(initialConfig) { mutableStateOf("") }

  val selectedProviderHasSavedKey = apiKeySaved && selectedProvider == initialConfig.providerId
  val presets = CloudModelPresets.forProvider(selectedProvider)

  AlertDialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
    title = { Text(stringResource(R.string.settings_cloud_model_dialog_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.settings_cloud_model_enabled_title),
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = stringResource(R.string.settings_cloud_model_enabled_summary),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Column(modifier = Modifier.selectableGroup()) {
          Text(
            text = stringResource(R.string.settings_cloud_model_provider_label),
            style = MaterialTheme.typography.titleSmall,
          )
          CloudProviderId.entries.forEach { providerId ->
            AppSingleChoiceRow(
              title = cloudProviderDisplayName(providerId),
              selected = providerId == selectedProvider,
              onClick = {
                selectedProvider = providerId
                modelName = CloudModelPresets.forProvider(providerId)
                  .firstOrNull { preset -> preset.recommended }
                  ?.modelName
                  .orEmpty()
              },
            )
          }
        }

        if (presets.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = stringResource(R.string.settings_cloud_model_presets_label),
              style = MaterialTheme.typography.titleSmall,
            )
            Row(
              modifier = Modifier.horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              presets.forEach { preset ->
                AssistChip(
                  onClick = { modelName = preset.modelName },
                  label = { Text(preset.displayName) },
                )
              }
            }
          }
        }

        OutlinedTextField(
          value = modelName,
          onValueChange = { modelName = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.settings_cloud_model_model_name_label)) },
          singleLine = true,
        )

        OutlinedTextField(
          value = apiKeyInput,
          onValueChange = { apiKeyInput = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.settings_cloud_model_api_key_label)) },
          placeholder = { Text(stringResource(R.string.settings_cloud_model_api_key_placeholder)) },
          supportingText =
            if (selectedProviderHasSavedKey) {
              { Text(stringResource(R.string.settings_cloud_model_api_key_saved)) }
            } else {
              null
            },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
      }
    },
    confirmButton = {
      TextButton(
        enabled = !enabled || modelName.isNotBlank(),
        onClick = {
          onSave(
            initialConfig.copy(
              enabled = enabled,
              providerId = selectedProvider,
              modelName = modelName,
            ),
            apiKeyInput,
          )
        },
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selectedProviderHasSavedKey) {
          TextButton(
            onClick = {
              onClearApiKey(selectedProvider)
              apiKeyInput = ""
            },
          ) {
            Text(stringResource(R.string.settings_cloud_model_clear_key))
          }
        }
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.cancel))
        }
      }
    },
  )
}

fun cloudProviderDisplayName(providerId: CloudProviderId): String {
  return when (providerId) {
    CloudProviderId.OPENROUTER -> "OpenRouter"
    CloudProviderId.DEEPSEEK -> "DeepSeek"
    CloudProviderId.CLAUDE -> "Claude"
  }
}
