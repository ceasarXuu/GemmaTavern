package selfgemma.talk.feature.roleplay.profile

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.feature.roleplay.common.RoleAvatar
import selfgemma.talk.ui.common.AppEditorCard
import selfgemma.talk.ui.common.AppEditorSectionHeader
import selfgemma.talk.ui.common.AppOutlinedTextField
import selfgemma.talk.ui.common.AppSingleChoiceRow

@Composable
internal fun MyProfileEditorContent(
  uiState: MyProfileUiState,
  contentPadding: PaddingValues,
  onPersonaNameChange: (String) -> Unit,
  onPersonaDescriptionChange: (String) -> Unit,
  onAvatarClick: () -> Unit,
  onPersonaPositionChange: (StPersonaDescriptionPosition) -> Unit,
  onPersonaDepthChange: (String) -> Unit,
  onPersonaRoleChange: (Int) -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(contentPadding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    PersonaAvatarCard(
      name = uiState.personaName,
      avatarUri = uiState.avatarUri,
      onAvatarClick = onAvatarClick,
      onShowHelp = onShowHelp,
    )
    EditorCard(
      title = stringResource(R.string.my_profile_persona_name_title),
      helpTopic = PersonaHelpTopic.NAME,
      onShowHelp = onShowHelp,
    ) {
      PersonaOutlinedTextField(
        value = uiState.personaName,
        onValueChange = onPersonaNameChange,
        modifier = Modifier.fillMaxWidth(),
        maxLines = 1,
        helpTopic = PersonaHelpTopic.NAME,
      )
    }
    EditorCard(
      title = stringResource(R.string.my_profile_persona_description_title),
      helpTopic = PersonaHelpTopic.DESCRIPTION,
      onShowHelp = onShowHelp,
    ) {
      PersonaOutlinedTextField(
        value = uiState.personaDescription,
        onValueChange = onPersonaDescriptionChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 8,
        helpTopic = PersonaHelpTopic.DESCRIPTION,
      )
    }
    PersonaPositionCard(
      selected = uiState.personaPosition,
      onSelected = onPersonaPositionChange,
      onShowHelp = onShowHelp,
    )
    if (uiState.personaPosition == StPersonaDescriptionPosition.AT_DEPTH) {
      EditorCard(
        title = stringResource(R.string.my_profile_persona_depth_title),
        helpTopic = PersonaHelpTopic.DEPTH,
        onShowHelp = onShowHelp,
      ) {
        PersonaOutlinedTextField(
          value = uiState.personaDepth,
          onValueChange = onPersonaDepthChange,
          modifier = Modifier.fillMaxWidth(),
          maxLines = 1,
          helpTopic = PersonaHelpTopic.DEPTH,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
      PersonaRoleCard(
        selectedRole = uiState.personaRole,
        onSelected = onPersonaRoleChange,
        onShowHelp = onShowHelp,
      )
    }
  }
}

@Composable
internal fun PersonaPositionCard(
  selected: StPersonaDescriptionPosition,
  onSelected: (StPersonaDescriptionPosition) -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  AppEditorCard {
    Column(
      modifier = Modifier.fillMaxWidth().selectableGroup(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AppEditorSectionHeader(
        title = stringResource(R.string.my_profile_persona_position_title),
        onShowHelp = { onShowHelp(PersonaHelpTopic.POSITION) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_in_prompt),
        selected = selected == StPersonaDescriptionPosition.IN_PROMPT,
        onClick = { onSelected(StPersonaDescriptionPosition.IN_PROMPT) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_top_an),
        selected = selected == StPersonaDescriptionPosition.TOP_AN,
        onClick = { onSelected(StPersonaDescriptionPosition.TOP_AN) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_bottom_an),
        selected = selected == StPersonaDescriptionPosition.BOTTOM_AN,
        onClick = { onSelected(StPersonaDescriptionPosition.BOTTOM_AN) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_at_depth),
        selected = selected == StPersonaDescriptionPosition.AT_DEPTH,
        onClick = { onSelected(StPersonaDescriptionPosition.AT_DEPTH) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_none),
        selected = selected == StPersonaDescriptionPosition.NONE,
        onClick = { onSelected(StPersonaDescriptionPosition.NONE) },
      )
    }
  }
}

@Composable
internal fun PersonaRoleCard(
  selectedRole: Int,
  onSelected: (Int) -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  AppEditorCard {
    Column(
      modifier = Modifier.fillMaxWidth().selectableGroup(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AppEditorSectionHeader(
        title = stringResource(R.string.my_profile_persona_role_title),
        onShowHelp = { onShowHelp(PersonaHelpTopic.ROLE) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_role_system),
        selected = selectedRole == 0,
        onClick = { onSelected(0) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_role_user),
        selected = selectedRole == 1,
        onClick = { onSelected(1) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_role_assistant),
        selected = selectedRole == 2,
        onClick = { onSelected(2) },
      )
    }
  }
}

@Composable
internal fun EditorCard(
  title: String,
  helpTopic: PersonaHelpTopic? = null,
  onShowHelp: ((PersonaHelpTopic) -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  AppEditorCard {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      AppEditorSectionHeader(
        title = title,
        onShowHelp =
          if (helpTopic != null && onShowHelp != null) {
            { onShowHelp(helpTopic) }
          } else {
            null
          },
      )
      content()
    }
  }
}

@Composable
internal fun PersonaAvatarCard(
  name: String,
  avatarUri: String?,
  onAvatarClick: () -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  AppEditorCard {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      AppEditorSectionHeader(
        title = stringResource(R.string.my_profile_avatar_title),
        supportingText =
          if (avatarUri.isNullOrBlank()) {
            stringResource(R.string.my_profile_avatar_tap_to_upload)
          } else {
            stringResource(R.string.my_profile_avatar_tap_to_edit)
          },
        onShowHelp = { onShowHelp(PersonaHelpTopic.AVATAR) },
      )
      RoleAvatar(
        name = name,
        avatarUri = avatarUri,
        modifier =
          Modifier
            .size(112.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onAvatarClick),
      )
      FilledTonalButton(onClick = onAvatarClick) {
        Text(
          if (avatarUri.isNullOrBlank()) {
            stringResource(R.string.role_editor_media_add)
          } else {
            stringResource(R.string.role_editor_media_replace)
          },
        )
      }
    }
  }
}

@Composable
internal fun PersonaOutlinedTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  minLines: Int = 1,
  maxLines: Int = minLines,
  helpTopic: PersonaHelpTopic? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
  val fieldSpec = personaTextFieldSpec(helpTopic)
  val currentCount = value.length
  val maxChars = fieldSpec?.maxChars
  val isOverLimit = maxChars != null && currentCount > maxChars
  LaunchedEffect(isOverLimit, helpTopic, currentCount, maxChars) {
    if (isOverLimit && helpTopic != null) {
      Log.w(MY_PROFILE_TAG, "persona field exceeds budget topic=$helpTopic count=$currentCount limit=$maxChars")
    }
  }
  AppOutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    minLines = minLines,
    maxLines = maxLines,
    singleLine = maxLines == 1,
    isError = isOverLimit,
    keyboardOptions = keyboardOptions,
    supportingText = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        Text(
          text =
            if (maxChars != null) {
              stringResource(R.string.role_editor_character_count_with_limit, currentCount, maxChars)
            } else {
              stringResource(R.string.role_editor_character_count_without_limit, currentCount)
            },
          style = MaterialTheme.typography.labelSmall,
          color =
            if (isOverLimit) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    },
  )
}
