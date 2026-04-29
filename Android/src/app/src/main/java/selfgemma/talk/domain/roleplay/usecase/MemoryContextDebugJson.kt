package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.SessionSummary

/**
 * Debug-payload JSON serializers extracted from [CompileRoleplayMemoryContextUseCase].
 * All helpers are pure (parameter-only) and are used only by debug logging emitted from the use case.
 */

internal fun RuntimeStateSnapshot?.toDebugJsonObject(): JsonObject {
  return JsonObject().apply {
    if (this@toDebugJsonObject == null) {
      addProperty("present", false)
      return@apply
    }
    addProperty("present", true)
    addProperty("updatedAt", updatedAt)
    addProperty("sourceMessageId", sourceMessageId)
    addProperty("scene", sceneJson.compactForDebug())
    addProperty("relationship", relationshipJson.compactForDebug())
    addProperty("entities", activeEntitiesJson.compactForDebug())
  }
}

internal fun SessionSummary?.toDebugJsonObject(): JsonObject {
  return JsonObject().apply {
    if (this@toDebugJsonObject == null) {
      addProperty("present", false)
      return@apply
    }
    addProperty("present", true)
    addProperty("updatedAt", updatedAt)
    addProperty("coveredUntilSeq", coveredUntilSeq)
    addProperty("summary", summaryText.compactForDebug(maxLength = 240))
  }
}

internal fun RoleplayMemoryPackBudgetReport.toDebugJsonObject(): JsonObject {
  return JsonObject().apply {
    addProperty("targetTokens", targetTokens)
    addProperty("estimatedTokens", estimatedTokens)
    addProperty("mode", mode.name)
    addProperty("externalFactTokens", externalFactTokens)
    addProperty("runtimeStateTokens", runtimeStateTokens)
    addProperty("openThreadTokens", openThreadTokens)
    addProperty("memoryAtomTokens", memoryAtomTokens)
    addProperty("fallbackSummaryTokens", fallbackSummaryTokens)
    addProperty("fallbackMemoryTokens", fallbackMemoryTokens)
    addProperty("droppedExternalFactCount", droppedExternalFactCount)
    addProperty("droppedOpenThreadCount", droppedOpenThreadCount)
    addProperty("droppedMemoryAtomCount", droppedMemoryAtomCount)
    addProperty("droppedFallbackMemoryCount", droppedFallbackMemoryCount)
    addProperty("droppedFallbackSummary", droppedFallbackSummary)
  }
}

internal fun List<RoleplayExternalFact>.toExternalFactDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toExternalFactDebugJsonArray.forEach { fact ->
      add(
        JsonObject().apply {
          addProperty("id", fact.id)
          addProperty("sourceToolName", fact.sourceToolName)
          addProperty("factKey", fact.factKey)
          addProperty("factType", fact.factType)
          addProperty("title", fact.title.compactForDebug(maxLength = 80))
          addProperty("content", fact.content.compactForDebug(maxLength = 180))
          addProperty("ephemeral", fact.ephemeral)
          addProperty("summaryEligible", fact.summaryEligible)
          addProperty("capturedAt", fact.capturedAt)
          addProperty("freshnessTtlMillis", fact.freshnessTtlMillis ?: -1L)
          addProperty("toolInvocationId", fact.toolInvocationId)
        },
      )
    }
  }
}

internal fun List<OpenThread>.toOpenThreadDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toOpenThreadDebugJsonArray.forEach { thread ->
      add(
        JsonObject().apply {
          addProperty("id", thread.id)
          addProperty("type", thread.type.name)
          addProperty("status", thread.status.name)
          addProperty("priority", thread.priority)
          addProperty("content", thread.content.compactForDebug(maxLength = 160))
        },
      )
    }
  }
}

internal fun List<MemoryAtom>.toMemoryAtomDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toMemoryAtomDebugJsonArray.forEach { atom ->
      add(
        JsonObject().apply {
          addProperty("id", atom.id)
          addProperty("plane", atom.plane.name)
          addProperty("namespace", atom.namespace.name)
          addProperty("stability", atom.stability.name)
          addProperty("subject", atom.subject.compactForDebug(maxLength = 64))
          addProperty("predicate", atom.predicate.compactForDebug(maxLength = 64))
          addProperty("objectValue", atom.objectValue.compactForDebug(maxLength = 120))
          addProperty("evidence", atom.evidenceQuote.compactForDebug(maxLength = 120))
        },
      )
    }
  }
}

internal fun List<CompactionCacheEntry>.toCompactionDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toCompactionDebugJsonArray.forEach { entry ->
      add(
        JsonObject().apply {
          addProperty("id", entry.id)
          addProperty("summaryType", entry.summaryType.name)
          addProperty("rangeStartMessageId", entry.rangeStartMessageId)
          addProperty("rangeEndMessageId", entry.rangeEndMessageId)
          addProperty("compactText", entry.compactText.compactForDebug(maxLength = 180))
          addProperty("tokenEstimate", entry.tokenEstimate)
        },
      )
    }
  }
}

internal fun List<MemoryItem>.toLegacyMemoryDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toLegacyMemoryDebugJsonArray.forEach { memory ->
      add(
        JsonObject().apply {
          addProperty("id", memory.id)
          addProperty("category", memory.category.name)
          addProperty("content", memory.content.compactForDebug(maxLength = 160))
          addProperty("confidence", memory.confidence)
        },
      )
    }
  }
}

internal fun List<RoleplayMemoryNeed>.toDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toDebugJsonArray.forEach { add(it.name) }
  }
}

internal fun List<String>.toStringJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toStringJsonArray.forEach(::add)
  }
}

internal fun String.compactForDebug(maxLength: Int = 200): String {
  return replace(Regex("\\s+"), " ").trim().take(maxLength)
}
