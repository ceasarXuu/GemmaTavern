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
package selfgemma.talk.customtasks.mobileactions

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.ui.graphics.vector.ImageVector
import selfgemma.talk.R

internal const val MOBILE_ACTIONS_SCREEN_TAG = "AGMAScreen"

internal data class PromptTemplate(@StringRes val labelResId: Int, val prompt: String)

internal val PROMPT_TEMPLATES =
  listOf(
    PromptTemplate(
      labelResId = R.string.prompt_template_label_flash_on,
      prompt = "Turn on flashlight",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_flash_off,
      prompt = "Turn off flashlight",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_create_contact,
      prompt =
        "Create contact John Smith with email address js@example.com and phone number 123 456 7890.",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_send_email,
      prompt =
        "Send an email to js@example.com with subject \"Meeting\" and body \"Hi John, let's meet at 3pm tomorrow.\"",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_create_calendar_event,
      prompt = "Create a calendar event at 2:30pm tomorrow for \"team meeting\"",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_show_location_on_map,
      prompt = "Show Googleplex on map",
    ),
    PromptTemplate(
      labelResId = R.string.prompt_template_label_open_wifi_settings,
      prompt = "Open WIFI settings",
    ),
  )

internal data class SampleActionItem(@StringRes val labelResId: Int, val icon: ImageVector)

internal val SAMPLE_ACTION_ITEMS =
  listOf(
    SampleActionItem(
      labelResId = R.string.prompt_template_label_flash_on_off,
      icon = Icons.Outlined.FlashlightOn,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_create_contact,
      icon = Icons.Outlined.PersonAdd,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_send_email,
      icon = Icons.Outlined.Email,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_create_calendar_event,
      icon = Icons.Outlined.CalendarMonth,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_show_location_on_map,
      icon = Icons.Outlined.Map,
    ),
    SampleActionItem(
      labelResId = R.string.prompt_template_label_open_wifi_settings,
      icon = Icons.Outlined.Wifi,
    ),
  )

internal data class MobileActionsTab(@StringRes val labelResId: Int, val icon: ImageVector)

internal val MOBILE_ACTIONS_TABS =
  listOf(
    MobileActionsTab(
      labelResId = R.string.mobile_actions_tab_model_response,
      icon = Icons.AutoMirrored.Rounded.Article,
    ),
    MobileActionsTab(
      labelResId = R.string.mobile_actions_tab_function_called,
      icon = Icons.Rounded.Functions,
    ),
  )

internal fun genFormattedFunctionCall(action: Action, resources: Resources): String {
  val strFunctionName = action.functionCallDetails.functionName
  val functionNameLabel = resources.getString(R.string.function_name)
  var content = "**$functionNameLabel**:\n- $strFunctionName"
  if (action.functionCallDetails.parameters.isNotEmpty()) {
    val parametersLabel =
      resources.getQuantityString(R.plurals.parameter, action.functionCallDetails.parameters.size)
    val strParameters =
      action.functionCallDetails.parameters.joinToString("\n") { "- ${it.first}: \"${it.second}\"" }
    content += "\n\n**$parametersLabel**:\n$strParameters"
  }
  return content
}
