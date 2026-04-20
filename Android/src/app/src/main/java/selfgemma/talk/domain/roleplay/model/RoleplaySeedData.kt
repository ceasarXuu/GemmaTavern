package selfgemma.talk.domain.roleplay.model

import android.content.Context
import selfgemma.talk.R

object RoleplaySeedData {
  const val BUILTIN_GEMMA_ROLE_ID = "builtin_gemma_tavern_hostess"

  fun defaultRoles(context: Context, now: Long, defaultModelId: String? = null): List<RoleCard> {
    return listOf(
      RoleCard(
        id = BUILTIN_GEMMA_ROLE_ID,
        name = context.getString(R.string.builtin_role_gemma_name),
        summary = context.getString(R.string.builtin_role_gemma_summary),
        systemPrompt = context.getString(R.string.builtin_role_gemma_system_prompt),
        personaDescription = context.getString(R.string.builtin_role_gemma_persona_description),
        worldSettings = context.getString(R.string.builtin_role_gemma_world_settings),
        openingLine = context.getString(R.string.builtin_role_gemma_opening_line),
        exampleDialogues =
          listOf(
            context.getString(R.string.builtin_role_gemma_example_dialogue_1),
            context.getString(R.string.builtin_role_gemma_example_dialogue_2),
          ),
        safetyPolicy = context.getString(R.string.builtin_role_gemma_safety_policy),
        defaultModelId = defaultModelId,
        enableThinking = true,
        tags =
          listOf(
            context.getString(R.string.builtin_role_gemma_tag_tavern),
            context.getString(R.string.builtin_role_gemma_tag_hostess),
            context.getString(R.string.builtin_role_gemma_tag_flirty),
          ),
        builtIn = true,
        createdAt = now,
        updatedAt = now,
      )
    )
  }
}
