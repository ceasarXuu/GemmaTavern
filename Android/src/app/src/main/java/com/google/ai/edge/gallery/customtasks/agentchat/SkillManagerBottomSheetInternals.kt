/*
 * Copyright 2026 Google LLC
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

package selfgemma.talk.customtasks.agentchat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selfgemma.talk.R

internal enum class AddSkillOptionType {
  FeaturedList,
  RemoteUrl,
  LocalImport,
  ManualInput,
}

internal data class AddSkillOption(
  val type: AddSkillOptionType,
  @StringRes val titleResId: Int,
  @StringRes val descriptionResId: Int,
  val icon: ImageVector,
)

internal val ADD_SKILL_OPTIONS =
  listOf(
    AddSkillOption(
      type = AddSkillOptionType.RemoteUrl,
      titleResId = R.string.add_skill_option_url_title,
      descriptionResId = R.string.add_skill_option_url_description,
      icon = Icons.Rounded.Link,
    ),
    AddSkillOption(
      type = AddSkillOptionType.LocalImport,
      titleResId = R.string.add_skill_option_local_title,
      descriptionResId = R.string.add_skill_option_local_description,
      icon = Icons.Outlined.DriveFolderUpload,
    ),
  )

val BUTTON_CONTENT_PADDING = PaddingValues(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 2.dp)

internal const val SKILL_MANAGER_TAG = "AGSkillManagerBottomSheet"

internal fun scrollToBottomOfList(scope: CoroutineScope, listState: LazyListState) {
  scope.launch {
    delay(300)
    if (listState.layoutInfo.totalItemsCount > 0) {
      listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
  }
}
