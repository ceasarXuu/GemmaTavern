package selfgemma.talk.domain.roleplay.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.ModelContextProfile

class ContextBudgetPlannerTest {
  private val planner = ContextBudgetPlanner(TokenEstimator())

  @Test
  fun plan_compactsOptionalSectionsBeforeDroppingCoreSection() {
    val material =
      PromptMaterial(
        preambleLines = listOf("You are roleplaying as Iris."),
        sections =
          listOf(
            PromptSectionCandidate(
              id = PromptSectionId.CORE_CHARACTER,
              title = "Core Character",
              fullBody = "full core ".repeat(80),
              compactBody = "compact core",
              priority = PromptSectionPriority.REQUIRED,
              required = true,
            ),
            PromptSectionCandidate(
              id = PromptSectionId.EXAMPLE_DIALOGUE,
              title = "Example Dialogue",
              fullBody = "full example ".repeat(80),
              compactBody = "brief cue",
              priority = PromptSectionPriority.OPTIONAL,
            ),
          ),
        responseRules = listOf("- Reply naturally."),
      )

    val plan =
      planner.plan(
        material = material,
        contextProfile =
          ModelContextProfile(
            contextWindowTokens = 768,
            reservedOutputTokens = 256,
            reservedThinkingTokens = 0,
            safetyMarginTokens = 256,
          ),
      )

    assertTrue(plan.prompt.contains("full core") || plan.prompt.contains("compact core"))
    assertTrue(plan.prompt.contains("brief cue"))
    assertFalse(plan.prompt.contains("full example full example"))
    assertTrue(plan.report.compactedSectionIds.contains(PromptSectionId.EXAMPLE_DIALOGUE.name))
  }

  @Test
  fun plan_drops_optional_sections_when_budget_remains_tight() {
    val material =
      PromptMaterial(
        preambleLines = listOf("You are roleplaying as Iris."),
        sections =
          listOf(
            PromptSectionCandidate(
              id = PromptSectionId.CORE_CHARACTER,
              title = "Core Character",
              fullBody = "full core ".repeat(90),
              compactBody = "core compact",
              minimalBody = "core minimal",
              priority = PromptSectionPriority.REQUIRED,
              required = true,
            ),
            PromptSectionCandidate(
              id = PromptSectionId.SESSION_SUMMARY,
              title = "Session Summary",
              fullBody = "summary ".repeat(60),
              compactBody = "short summary",
              priority = PromptSectionPriority.LOW,
            ),
          ),
        responseRules = listOf("- Reply naturally."),
      )

    val plan =
      planner.plan(
        material = material,
        contextProfile =
          ModelContextProfile(
            contextWindowTokens = 640,
            reservedOutputTokens = 256,
            reservedThinkingTokens = 0,
            safetyMarginTokens = 256,
          ),
      )

    assertTrue(plan.prompt.contains("core compact") || plan.prompt.contains("core minimal"))
    assertFalse(plan.prompt.contains("full core full core"))
    assertFalse(plan.prompt.contains("[Session Summary]"))
    assertTrue(plan.report.droppedSectionIds.contains(PromptSectionId.SESSION_SUMMARY.name))
  }
}
