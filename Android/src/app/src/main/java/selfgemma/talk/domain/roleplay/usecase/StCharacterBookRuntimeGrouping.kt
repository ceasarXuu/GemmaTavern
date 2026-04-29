package selfgemma.talk.domain.roleplay.usecase

import kotlin.random.Random

internal fun filterGroupedCandidates(
  candidates: List<RuntimeCandidate>,
  activated: Map<String, RuntimeEntry>,
  runtimeSettings: StWorldRuntimeSettings,
): List<RuntimeEntry> {
  if (candidates.none { it.entry.extensions.group.isNotBlank() }) {
    return candidates
      .sortedWith(compareByDescending<RuntimeCandidate> { it.stickyActive }.thenBy { it.entry.order })
      .map { it.entry }
  }

  val kept = candidates.toMutableList()
  val grouped =
    linkedMapOf<String, MutableList<RuntimeCandidate>>().apply {
      candidates
        .filter { it.entry.extensions.group.isNotBlank() }
        .forEach { runtimeCandidate ->
          runtimeCandidate.entry.extensions.group
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { groupName ->
              getOrPut(groupName) { mutableListOf() }.add(runtimeCandidate)
            }
        }
    }
  grouped.forEach { (groupName, groupEntries) ->
    if (groupEntries.isEmpty()) {
      return@forEach
    }
    if (activated.values.any { it.extensions.group.split(',').map(String::trim).contains(groupName) }) {
      kept.removeAll(groupEntries)
      return@forEach
    }
    val stickyEntries = groupEntries.filter { it.stickyActive }
    if (stickyEntries.isNotEmpty()) {
      kept.removeAll(groupEntries.filterNot { it in stickyEntries })
      return@forEach
    }
    val scoreFiltered = filterGroupByScore(groupEntries, runtimeSettings)
    val overrides = groupEntries.filter { it.entry.extensions.groupOverride }.sortedBy { it.entry.order }
    val winner =
      when {
        overrides.isNotEmpty() -> overrides.first()
        else -> weightedPick(scoreFiltered)
      }
    kept.removeAll(groupEntries.filterNot { it == winner })
  }
  return kept
    .sortedWith(compareByDescending<RuntimeCandidate> { it.stickyActive }.thenBy { it.entry.order })
    .map { it.entry }
}

internal fun filterGroupByScore(
  entries: List<RuntimeCandidate>,
  runtimeSettings: StWorldRuntimeSettings,
): List<RuntimeCandidate> {
  val shouldScore = runtimeSettings.useGroupScoring || entries.any { it.entry.extensions.useGroupScoring == true }
  if (!shouldScore) {
    return entries
  }
  val maxScore = entries.maxOfOrNull { it.score } ?: return entries
  return entries.filter { entry ->
    val scored = entry.entry.extensions.useGroupScoring ?: runtimeSettings.useGroupScoring
    !scored || entry.score == maxScore
  }
}

internal fun weightedPick(entries: List<RuntimeCandidate>): RuntimeCandidate {
  val total = entries.sumOf { it.entry.extensions.groupWeight.coerceAtLeast(1) }
  var roll = Random.nextInt(total.coerceAtLeast(1))
  entries.forEach { entry ->
    roll -= entry.entry.extensions.groupWeight.coerceAtLeast(1)
    if (roll < 0) {
      return entry
    }
  }
  return entries.first()
}
