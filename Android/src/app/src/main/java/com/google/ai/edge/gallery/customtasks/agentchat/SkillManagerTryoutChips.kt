package selfgemma.talk.customtasks.agentchat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.compose.material.icons.outlined.Tag
import selfgemma.talk.common.SkillTryOutChip

val TRYOUT_CHIPS: List<SkillTryOutChip> =
  listOf(
    SkillTryOutChip(
      icon = Icons.Outlined.Map,
      label = "Interactive Map",
      prompt = "Show me Googleplex on interactive map.",
      skillName = "interactive-map",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Kitchen,
      label = "Kitchen Adventure",
      prompt = "Start kitchen adventure",
      skillName = "kitchen-adventure",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Tag,
      label = "Calculate Hash",
      prompt = "What is the sha1 hash of \"gemma\"?",
      skillName = "calculate-hash",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.ScreenRotation,
      label = "Text Spinner",
      prompt = "Spin \"Gemma\" on my head",
      skillName = "text-spinner",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Email,
      label = "Send Email",
      prompt = "Send email 'Good morning' to abc@example.com. Content: 'Any plans for tonight?'",
      skillName = "send-email",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.SentimentVerySatisfied,
      label = "Track my mood",
      prompt =
        "Log yesterday's mood as 2 because it was raining quite heavily, and log today's mood as 9 because I had a great time playing pickleball again. Then show me my mood dashboard.",
      skillName = "mood-tracker",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.LocalLibrary,
      label = "Query Wikipedia",
      prompt = "Check Wikipedia about Oscars 2026. Tell me who won the best picture.",
      skillName = "query-wikipedia",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.QrCode,
      label = "Generate QR code",
      prompt = "Generate QR code for https://deepmind.google/models/gemma/",
      skillName = "qr-code",
    ),
  )
