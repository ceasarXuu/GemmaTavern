package selfgemma.talk.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun appOutlinedTextFieldColors() =
  OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    disabledContainerColor = MaterialTheme.colorScheme.surface,
    errorContainerColor = MaterialTheme.colorScheme.surface,
  )

@Composable
fun AppOutlinedTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  minLines: Int = 1,
  maxLines: Int = minLines,
  singleLine: Boolean = maxLines == 1,
  isError: Boolean = false,
  textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  visualTransformation: VisualTransformation = VisualTransformation.None,
  label: (@Composable () -> Unit)? = null,
  placeholder: (@Composable () -> Unit)? = null,
  supportingText: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    enabled = enabled,
    minLines = minLines,
    maxLines = maxLines,
    singleLine = singleLine,
    isError = isError,
    textStyle = textStyle,
    keyboardOptions = keyboardOptions,
    visualTransformation = visualTransformation,
    label = label,
    placeholder = placeholder,
    supportingText = supportingText,
    trailingIcon = trailingIcon,
    shape = MaterialTheme.shapes.large,
    colors = appOutlinedTextFieldColors(),
  )
}
