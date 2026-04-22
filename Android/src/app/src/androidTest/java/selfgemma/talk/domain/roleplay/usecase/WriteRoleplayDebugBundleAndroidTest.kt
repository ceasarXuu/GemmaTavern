package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import selfgemma.talk.data.roleplay.repository.AndroidRoleplayDebugExportRepository
import selfgemma.talk.domain.roleplay.model.RoleplayDebugBundle
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportAppInfo
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportNotes
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin
import selfgemma.talk.domain.roleplay.model.RoleplayDebugMessageSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugRoleSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugSessionEventSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugSessionSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugSummarySnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugToolInvocationSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugUserProfileSnapshot

@RunWith(AndroidJUnit4::class)
class WriteRoleplayDebugBundleAndroidTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun write_persistsBundleAndLatestPointerInDownloads() {
    val serializer = RoleplayDebugExportJsonSerializer()
    val useCase =
      WriteRoleplayDebugBundleUseCase(
        repository = AndroidRoleplayDebugExportRepository(context),
        serializer = serializer,
      )
    val bundle = buildTestBundle()

    val result =
      runBlocking {
        useCase.write(bundle = bundle, origin = RoleplayDebugExportOrigin.CHAT_SCREEN)
      }
    val bundleJson = readText(result.bundleFile.contentUri)
    val pointerJson = readText(result.pointerFile.contentUri)

    assertEquals("latest-debug-export.json", result.pointerFile.fileName)
    assertTrue(result.bundleFile.fileName.startsWith("roleplay-debug-export-test-"))
    assertTrue(bundleJson.contains("\"schemaVersion\": \"roleplay_debug_bundle_v1\""))
    assertTrue(bundleJson.contains("\"sessionId\": \"export-test\"").not())
    assertTrue(bundleJson.contains("\"id\": \"export-test\""))
    assertTrue(pointerJson.contains("\"sessionId\": \"export-test\""))
    assertTrue(pointerJson.contains(result.bundleFile.fileName))
  }

  private fun readText(contentUri: String): String {
    return checkNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(contentUri))) {
      "Unable to read debug export uri=$contentUri"
    }.use { input ->
      String(input.readBytes(), StandardCharsets.UTF_8)
    }
  }

  private fun buildTestBundle(): RoleplayDebugBundle {
    return RoleplayDebugBundle(
      exportedAt = 1_713_888_000_000L,
      app =
        RoleplayDebugExportAppInfo(
          applicationId = "selfgemma.talk",
          versionName = "0.test",
          versionCode = 1,
          debugBuild = true,
        ),
      session =
        RoleplayDebugSessionSnapshot(
          id = "export-test",
          title = "Export Test Session",
          roleId = "role-test",
          activeModelId = "gemma-3n",
          pinned = false,
          archived = false,
          createdAt = 1_713_887_900_000L,
          updatedAt = 1_713_888_000_000L,
          lastMessageAt = 1_713_888_000_000L,
          lastSummary = "Short summary",
          lastUserMessageExcerpt = "Hello",
          lastAssistantMessageExcerpt = "Hi",
          turnCount = 1,
          summaryVersion = 1,
          draftInput = "",
          interopChatMetadataJson = "{}",
        ),
      role =
        RoleplayDebugRoleSnapshot(
          id = "role-test",
          name = "Captain Astra",
          avatarUri = null,
          coverUri = null,
          summary = "A disciplined captain.",
          systemPrompt = "Stay focused.",
          personaDescription = "Professional.",
          worldSettings = "Bridge",
          openingLine = "Status report.",
          exampleDialogues = listOf("Captain: Report."),
          tags = listOf("captain"),
          safetyPolicy = "",
          defaultModelId = "gemma-3n",
        ),
      userProfile =
        RoleplayDebugUserProfileSnapshot(
          activePersonaId = "captain",
          defaultPersonaId = "captain",
          userName = "Captain Mae",
          personaTitle = "Commander",
          personaDescription = "Steady under pressure.",
          personaDescriptionPosition = "IN_PROMPT",
          personaDescriptionDepth = 2,
          personaDescriptionRole = 0,
          avatarUri = null,
        ),
      summary =
        RoleplayDebugSummarySnapshot(
          version = 1,
          coveredUntilSeq = 2,
          summaryText = "A short bridge exchange.",
          tokenEstimate = 64,
          updatedAt = 1_713_888_000_000L,
        ),
      messages =
        listOf(
          RoleplayDebugMessageSnapshot(
            id = "user-1",
            seq = 1,
            branchId = "main",
            side = "USER",
            kind = "TEXT",
            status = "COMPLETED",
            accepted = true,
            isCanonical = true,
            content = "Hello",
            isMarkdown = false,
            errorMessage = null,
            latencyMs = null,
            accelerator = null,
            parentMessageId = null,
            regenerateGroupId = null,
            editedFromMessageId = null,
            supersededMessageId = null,
            metadataJson = null,
            createdAt = 1_713_887_950_000L,
            updatedAt = 1_713_887_950_000L,
          )
        ),
      toolInvocations =
        listOf(
          RoleplayDebugToolInvocationSnapshot(
            id = "tool-1",
            turnId = "assistant-1",
            toolName = "getDeviceSystemTime",
            source = "NATIVE",
            status = "SUCCEEDED",
            stepIndex = 0,
            argsJson = "{}",
            resultJson = """{"time24h":"18:07"}""",
            resultSummary = "18:07",
            artifactRefs = emptyList(),
            errorMessage = null,
            startedAt = 1_713_887_960_000L,
            finishedAt = 1_713_887_960_500L,
          )
        ),
      sessionEvents =
        listOf(
          RoleplayDebugSessionEventSnapshot(
            id = "event-1",
            eventType = "TOOL_CALL_COMPLETED",
            payloadJson = """{"toolName":"getDeviceSystemTime"}""",
            createdAt = 1_713_887_961_000L,
          )
        ),
      notes =
        RoleplayDebugExportNotes(
          exportKind = "manual_debug_export",
          initiatedFrom = RoleplayDebugExportOrigin.CHAT_SCREEN.rawValue,
        ),
    )
  }
}
