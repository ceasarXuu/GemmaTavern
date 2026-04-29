package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness
import selfgemma.talk.domain.roleplay.model.freshness

internal fun buildOpenThreadVariants(openThreads: List<OpenThread>): MemoryVariants {
  val rendered =
    openThreads.map { thread ->
      "- [${thread.type.name.lowercase()}/${thread.owner.name.lowercase()}/p${thread.priority}] ${thread.content.normalizeWhitespace().take(PMB_MAX_THREAD_LINE_LENGTH)}"
    }
  return MemoryVariants(
    full = rendered.joinToString("\n"),
    compact = rendered.take(2).joinToString("\n"),
    minimal = rendered.take(1).joinToString("\n"),
  )
}

internal fun buildExternalFactVariants(externalFacts: List<RoleplayExternalFact>): MemoryVariants {
  val rendered =
    externalFacts.map { fact ->
      "- [${fact.freshnessLabel()}/${fact.sourceToolName.normalizeWhitespace()}] ${fact.title.normalizeWhitespace()}: ${fact.content.normalizeWhitespace().take(PMB_MAX_EXTERNAL_FACT_LINE_LENGTH)}"
    }
  return MemoryVariants(
    full = rendered.joinToString("\n"),
    compact = rendered.take(2).joinToString("\n"),
    minimal = rendered.take(1).joinToString("\n"),
  )
}

internal fun buildMemoryAtomVariants(memoryAtoms: List<MemoryAtom>): MemoryVariants {
  val rendered =
    memoryAtoms.map { atom ->
      "- ${atom.subject.normalizeWhitespace()} ${atom.predicate.normalizeWhitespace()}: ${atom.objectValue.normalizeWhitespace().take(PMB_MAX_MEMORY_LINE_LENGTH)}"
    }
  return MemoryVariants(
    full = rendered.joinToString("\n"),
    compact = rendered.take(2).joinToString("\n"),
    minimal = rendered.take(1).joinToString("\n"),
  )
}

internal fun buildMemoryVariants(memories: List<MemoryItem>): MemoryVariants {
  val rendered =
    memories.map { memory ->
      "- ${memory.category.name.lowercase()}: ${memory.content.trim()}"
    }
  return MemoryVariants(
    full = rendered.joinToString("\n"),
    compact = rendered.take(2).joinToString("\n"),
    minimal = rendered.take(1).joinToString("\n"),
  )
}

internal fun RoleplayExternalFact.freshnessLabel(): String {
  return when (freshness()) {
    RoleplayExternalFactFreshness.FRESH -> "fresh"
    RoleplayExternalFactFreshness.STALE -> "stale"
    RoleplayExternalFactFreshness.STABLE -> "stable"
  }
}
