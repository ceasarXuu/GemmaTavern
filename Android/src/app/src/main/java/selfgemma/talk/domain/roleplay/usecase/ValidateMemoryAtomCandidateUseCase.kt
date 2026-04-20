package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import kotlin.math.min
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.MessageSide

data class MemoryAtomValidationResult(
  val accepted: Boolean,
  val atom: MemoryAtom? = null,
  val tombstoneIds: List<String> = emptyList(),
  val promoted: Boolean = false,
  val rejectionReason: String? = null,
)

class ValidateMemoryAtomCandidateUseCase @Inject constructor() {
  operator fun invoke(
    candidate: MemoryAtom,
    sourceSide: MessageSide,
    pinned: Boolean,
    existingAtoms: List<MemoryAtom>,
  ): MemoryAtomValidationResult {
    if (candidate.sourceMessageIds.isEmpty() && !pinned) {
      return MemoryAtomValidationResult(accepted = false, rejectionReason = "missing_evidence")
    }

    val sameKey = existingAtoms.firstOrNull { atom -> !atom.tombstone && atom.hasSameKey(candidate) }
    val conflicts =
      existingAtoms.filter { atom ->
        !atom.tombstone && atom.id != sameKey?.id && atom.conflictsWith(candidate)
      }

    if (
      !pinned &&
        sourceSide == MessageSide.ASSISTANT &&
        conflicts.any { conflict -> conflict.stability == MemoryStability.STABLE || conflict.stability == MemoryStability.LOCKED }
    ) {
      return MemoryAtomValidationResult(accepted = false, rejectionReason = "assistant_conflicts_with_stable")
    }

    val mergedSources = (sameKey?.sourceMessageIds.orEmpty() + candidate.sourceMessageIds).distinct()
    val evidenceCount = mergedSources.size
    val tombstoneIds =
      if (pinned || sourceSide == MessageSide.USER) {
        conflicts.map { it.id }
      } else {
        emptyList()
      }
    val nextStability =
      when {
        pinned -> MemoryStability.LOCKED
        sameKey?.stability == MemoryStability.LOCKED -> MemoryStability.LOCKED
        sourceSide == MessageSide.USER -> MemoryStability.STABLE
        sameKey?.stability == MemoryStability.STABLE -> MemoryStability.STABLE
        sourceSide == MessageSide.ASSISTANT && evidenceCount >= 3 && conflicts.isEmpty() -> MemoryStability.STABLE
        else -> MemoryStability.CANDIDATE
      }
    val nextConfidence =
      min(
        0.99f,
        maxOf(sameKey?.confidence ?: 0f, candidate.confidence) + ((evidenceCount - 1).coerceAtLeast(0) * 0.08f),
      )
    val nextEpistemicStatus =
      when {
        pinned -> MemoryEpistemicStatus.SELF_REPORT
        sourceSide == MessageSide.USER -> MemoryEpistemicStatus.SELF_REPORT
        sameKey?.epistemicStatus == MemoryEpistemicStatus.SELF_REPORT -> MemoryEpistemicStatus.SELF_REPORT
        else -> candidate.epistemicStatus
      }
    val nextAtom =
      candidate.copy(
        id = sameKey?.id ?: candidate.id,
        stability = nextStability,
        epistemicStatus = nextEpistemicStatus,
        salience = maxOf(sameKey?.salience ?: 0f, candidate.salience),
        confidence = nextConfidence,
        sourceMessageIds = mergedSources,
        supersedesMemoryId = tombstoneIds.firstOrNull(),
        createdAt = sameKey?.createdAt ?: candidate.createdAt,
        updatedAt = candidate.updatedAt,
        lastUsedAt = sameKey?.lastUsedAt,
      )

    return MemoryAtomValidationResult(
      accepted = true,
      atom = nextAtom,
      tombstoneIds = tombstoneIds,
      promoted = sameKey != null && sameKey.stability != nextAtom.stability && nextAtom.stability.rank > sameKey.stability.rank,
    )
  }

  private fun MemoryAtom.hasSameKey(other: MemoryAtom): Boolean {
    return namespace == other.namespace &&
      subject == other.subject &&
      predicate == other.predicate &&
      normalizedObjectValue == other.normalizedObjectValue
  }

  private fun MemoryAtom.conflictsWith(other: MemoryAtom): Boolean {
    return namespace == other.namespace &&
      subject == other.subject &&
      predicate == other.predicate &&
      normalizedObjectValue != other.normalizedObjectValue
  }

  private val MemoryStability.rank: Int
    get() =
      when (this) {
        MemoryStability.TRANSIENT -> 0
        MemoryStability.CANDIDATE -> 1
        MemoryStability.STABLE -> 2
        MemoryStability.LOCKED -> 3
      }
}
