# Roleplay Memory System Architecture V1

## Goal

This document maps the current roleplay-memory product requirements onto the
`selfgemma.talk` Android codebase.

The target is not a generic "chat memory" feature. The target is a local-first memory runtime for
roleplay under these hard constraints:

- effective prompt budget is around `4k`
- runtime is fully offline
- primary models are on-device Gemma-class small models
- long-term continuity is more important than raw history retention
- branch safety for `regenerate`, `edit`, and `rollback` is mandatory

## Positioning

This document describes architecture shape, not the full source of product intent.

For current memory work, architecture decisions should be grounded in the retained public
architecture and validation references:

- `docs/roleplay-context-budget-architecture.md`
- `docs/roleplay-memory-acceptance-standard.md`
- this architecture document

This architecture treats the memory layer as a "continuity compiler":

1. normalize free-form dialogue into limited structured operations
2. preserve long-term truth as verifiable atoms, not prose summaries
3. compile only the minimum working set needed for the next turn
4. protect continuity, style, scene state, and relationship state under small-window inference

## Product Principles

The implementation must preserve these principles from the PRD:

- role style is anchored by a fixed `Role Kernel`, not by replaying large history
- long-term truth comes from structured atoms with evidence
- summaries are cache, not source-of-truth memory
- scene state and relationship state are first-class runtime state
- branch isolation is a hard invariant
- sparse local retrieval is the default; dense embedding is optional future enhancement only

## Current Codebase Mapping

The current roleplay runtime already contains useful foundations:

- canonical role data and ST compatibility
  - `RoleCard.stCard`
  - `StCharacterBookRuntime`
  - `RoleInteropState`
- prompt budgeting foundations
  - `ModelContextProfile`
  - `ContextBudgetPlanner`
  - `PromptAssembler`
- local persistence
  - Room database for sessions, messages, summaries, memories, and events
- send flow orchestration
  - `SendRoleplayMessageUseCase`

Relevant existing files:

- `Android/src/app/src/main/java/selfgemma/talk/data/roleplay/db/RoleplayDatabase.kt`
- `Android/src/app/src/main/java/selfgemma/talk/data/roleplay/db/entity/RoleplayEntities.kt`
- `Android/src/app/src/main/java/selfgemma/talk/domain/roleplay/usecase/SendRoleplayMessageUseCase.kt`
- `Android/src/app/src/main/java/selfgemma/talk/domain/roleplay/usecase/PromptAssembler.kt`
- `Android/src/app/src/main/java/selfgemma/talk/domain/roleplay/usecase/ContextBudgetPlanner.kt`
- `Android/src/app/src/main/java/selfgemma/talk/domain/roleplay/usecase/StCharacterBookRuntime.kt`
- `Android/src/app/src/main/java/selfgemma/talk/domain/roleplay/usecase/CompileRuntimeRoleProfileUseCase.kt`

The current implementation is not sufficient for the PRD because:

- `messages` do not model accepted history vs branch-local candidates
- `session_summaries` act as operational continuity, but the PRD requires summaries to be cache only
- `memories` store flat natural-language snippets instead of structured truth atoms
- there is no first-class `RuntimeStateSnapshot`
- there is no first-class `OpenThread`
- there is no planner / extractor / validator split
- memory extraction currently depends on lightweight regex heuristics only

## Target Runtime Layers

The runtime is split into seven layers.

### L0. Character Kernel

Purpose:

- lock role identity, style, invariants, and speaking behavior

Source:

- compiled from `RoleCard` and ST-compatible fields

Properties:

- stable across turns
- small and always injected
- independent from session history

### L1. Runtime State

Purpose:

- represent the current scene and current relationship state

Examples:

- where the conversation is happening
- which entities are present
- current emotional tension
- current objective
- what is unresolved right now

This layer replaces the current misuse of summary text as live state.

### L2. Recent Window

Purpose:

- preserve local coherence for the latest turns

Properties:

- always present
- bounded by strict token budget
- never used as long-term truth storage

### L3. Open Threads

Purpose:

- track unresolved promises, tasks, questions, mysteries, emotional threads, and pending reveals

Properties:

- first-class retrieval target
- one of the strongest continuity anchors in small-window roleplay

### L4. Semantic Memory

Purpose:

- store stable facts, user preferences, canon-compatible persistent facts, and long-lived relationship facts

Properties:

- stored as structured atoms with evidence
- promoted from `candidate` to `stable` through validation and repeated confirmation

### L5. Episodic Memory

Purpose:

- store important events and condensed episode-level recalls

Properties:

- event-centric, not full prose summaries
- retrieved on demand

### L6. Raw Transcript / Evidence Store

Purpose:

- preserve source turns, branch history, edit ancestry, and evidence trails

Properties:

- never fully injected into prompt
- used for validation, replay, and debugging

## Domain Model

The new domain model should align with the PRD, while remaining compatible with the current app.

### CharacterKernel

This is a compiled projection of role truth for inference-time use.

Suggested model:

```kotlin
data class CharacterKernel(
  val roleId: String,
  val version: Int,
  val identityJson: String,
  val speechStyleJson: String,
  val invariantsJson: String,
  val microExemplar: String,
  val tokenBudget: Int,
  val compiledAt: Long,
)
```

Notes:

- `RoleRuntimeProfile` can remain the host for this compiled data in Phase 1
- `CompileRuntimeRoleProfileUseCase` becomes the builder for `CharacterKernel`
- do not replace `RoleCard.stCard` as the canonical source

### Continuity / Session

Current `Session` remains the app-visible continuity container.

Phase 1 does not require a separate `continuities` table if `sessions` are semantically equivalent,
but the runtime should treat session state as continuity state, not merely a chat list item.

### Turn

The current `MessageEntity` must evolve into a true turn model.

New required fields:

- `branchId: String`
- `accepted: Boolean`
- `editedFromTurnId: String?`
- `supersededTurnId: String?`
- `isCanonical: Boolean`

Rules:

- user turns are canonical immediately after submit
- assistant turns start as branch-local candidates
- only accepted assistant turns may participate in long-term stabilization

### RuntimeStateSnapshot

New entity required.

Suggested model:

```kotlin
data class RuntimeStateSnapshot(
  val sessionId: String,
  val sceneJson: String,
  val relationshipJson: String,
  val activeEntitiesJson: String,
  val updatedAt: Long,
  val sourceTurnId: String? = null,
)
```

Suggested `sceneJson` fields:

- `location`
- `timeOfDay`
- `goal`
- `hazards`
- `inventory`
- `recentAction`

Suggested `relationshipJson` fields:

- `trust`
- `tension`
- `intimacy`
- `dominance`
- `currentMood`
- `lastShiftReason`

### MemoryAtom

This is the new long-term truth object and replaces flat `MemoryItem` as the source of truth.

Suggested model:

```kotlin
enum class MemoryPlane { IC, OOC, CANON, SHARED }
enum class MemoryNamespace { SEMANTIC, EPISODIC, PROMISE, WORLD }
enum class MemoryStability { TRANSIENT, CANDIDATE, STABLE, LOCKED }
enum class MemoryEpistemicStatus { OBSERVED, SELF_REPORT, THIRD_PARTY_CLAIM, INFERRED, DISPUTED }
enum class MemoryBranchScope { ACCEPTED_ONLY, BRANCH_LOCAL }

data class MemoryAtom(
  val id: String,
  val sessionId: String,
  val roleId: String,
  val plane: MemoryPlane,
  val namespace: MemoryNamespace,
  val subject: String,
  val predicate: String,
  val objectValue: String,
  val normalizedObjectValue: String,
  val stability: MemoryStability,
  val epistemicStatus: MemoryEpistemicStatus,
  val salience: Float,
  val confidence: Float,
  val timeStartTurnId: String? = null,
  val timeEndTurnId: String? = null,
  val branchScope: MemoryBranchScope,
  val sourceTurnIds: List<String>,
  val evidenceQuote: String,
  val supersedesMemoryId: String? = null,
  val tombstone: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
  val lastUsedAt: Long? = null,
)
```

Rules:

- `INFERRED` cannot be written as `STABLE` directly
- conflicting facts create new versions, they do not overwrite prior truth
- `OOC` and `IC` memories never cross-write without an explicit rule

### OpenThread

New entity required.

Suggested model:

```kotlin
enum class OpenThreadType { PROMISE, QUESTION, TASK, MYSTERY, EMOTIONAL }
enum class OpenThreadStatus { OPEN, RESOLVED, DROPPED }
enum class OpenThreadOwner { USER, ASSISTANT, SHARED }

data class OpenThread(
  val id: String,
  val sessionId: String,
  val type: OpenThreadType,
  val content: String,
  val owner: OpenThreadOwner,
  val priority: Int,
  val status: OpenThreadStatus,
  val sourceTurnIds: List<String>,
  val resolvedByTurnId: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
)
```

### CompactionCache

This replaces `SessionSummary` as a cache-only structure.

Suggested model:

```kotlin
enum class CompactionSummaryType { CHAPTER, ARC, SCENE }

data class CompactionCacheEntry(
  val id: String,
  val sessionId: String,
  val rangeStartTurnId: String,
  val rangeEndTurnId: String,
  val summaryType: CompactionSummaryType,
  val compactText: String,
  val sourceHash: String,
  val tokenEstimate: Int,
  val updatedAt: Long,
)
```

This data is retrievable cache, not long-term truth.

## Room Schema Plan

Phase 0 schema changes should be additive where possible.

### Tables to Add

- `runtime_state_snapshots`
- `memory_atoms`
- `open_threads`
- `compaction_cache`
- `memory_entities`
- `memory_query_logs`
- `memory_write_logs`

### Tables to Evolve

- `messages`
  - add branch and acceptance fields
- `sessions`
  - may keep `lastSummary` for UI compatibility only
- `session_summaries`
  - keep temporarily, mark as deprecated cache source
- `memories`
  - keep temporarily for migration only

### Indexing Strategy

Use SQLite-native indexing first.

Required indexes:

- `memory_atoms(sessionId, plane, namespace, stability, tombstone)`
- `memory_atoms(roleId, normalizedObjectValue)`
- `open_threads(sessionId, status, priority, updatedAt)`
- `runtime_state_snapshots(sessionId, updatedAt DESC)`
- `messages(sessionId, branchId, accepted, seq)`

Recommended FTS:

- `memory_atoms_fts`
  - content source should include `subject`, `predicate`, `objectValue`, `evidenceQuote`
- `open_threads_fts`
  - content source should include `content`
- `compaction_cache_fts`
  - content source should include `compactText`

No dense vector store is required in V1.

## Branch Isolation Model

Branch safety is a hard requirement.

### Invariants

- only accepted assistant turns affect stable long-term state
- regenerate creates a branch-local assistant candidate
- edit creates a new branch root and invalidates forward candidate state
- rollback restores runtime state to the last accepted turn boundary
- branch-local memory writes never leak into accepted-only stable memory

### Accepted History Rule

The continuity source of truth is:

- all accepted user turns
- all accepted assistant turns
- the latest runtime state snapshot derived from accepted history

This requires the send flow to split "generated" from "accepted".

## Planner / Retriever / Compiler / Extractor / Validator Split

The current `SendRoleplayMessageUseCase` should be decomposed into runtime modules.

### Planner

Purpose:

- convert current free-form input and current continuity state into a limited retrieval intent

Output schema should include:

- target entities
- target namespaces
- whether scene lookup is needed
- whether open-thread retrieval is required
- whether semantic recall is required
- time scope
- plane preference

Properties:

- no-thinking
- strict JSON output
- triggered only when needed

### Retriever

Retrieval order:

1. runtime state
2. open threads
3. relationship state
4. semantic memory
5. episodic memory
6. recent-window fallback

Primary scoring signals:

- namespace match
- entity overlap
- lexical / FTS match
- scene overlap
- salience
- recency
- contradiction penalty
- already-covered penalty

The PRD scoring formula is a good starting point and should be preserved in code as tunable weights.

### Prompt Compiler

The current prompt system should evolve from "section collection" to "slot budget compilation".

Target prompt slots:

- `Role Kernel`
- `Runtime State`
- `Open Threads`
- `Semantic Facts`
- `Episodic Recall`
- `Recent Window`
- `Current User Input`

Hard-retain rules:

- never drop the minimum `Role Kernel`
- never drop the minimum `Runtime State`
- never drop the latest user turn
- retain at least one high-priority open thread when open threads exist

Trim order:

1. episodic recall
2. lower-salience semantic facts
3. older recent turns
4. lower-priority open threads
5. never cut below the role-kernel floor

### Extractor

Purpose:

- convert the latest accepted mini-window into structured memory operations

Output style:

- no-thinking
- strict JSON
- recent mini-window only

Allowed operations:

- `ignore`
- `update_scene`
- `update_relation`
- `add_open_thread`
- `resolve_open_thread`
- `upsert_semantic_fact`
- `append_episode`
- `mark_candidate_only`

The current regex extractor can remain as a fallback path when the extractor is skipped for latency
or model-readiness reasons.

### Validator

The validator decides whether extracted operations can be committed.

Hard rules:

1. evidence turn must exist
2. user facts must be grounded in user turn evidence or explicit confirmation
3. assistant inference cannot directly become `STABLE`
4. `IC` and `OOC` may not cross-write
5. rejected branches may not write to accepted history
6. conflicting facts create versions instead of overwriting
7. inferred facts default to `CANDIDATE`

### Promotion

Promotion upgrades memory from `CANDIDATE` to `STABLE`.

Suggested triggers:

- repeated confirmation
- high-confidence user correction
- repeated retrieval and continued consistency
- explicit user pinning

## Prompt Budget For 4k

The exact values should remain model-dependent, but V1 should use stable slot ranges.

Recommended budget:

| Section | Target Tokens |
| --- | ---: |
| Role Kernel | 220-320 |
| Runtime State | 60-120 |
| Open Threads | 40-100 |
| Semantic Facts | 80-180 |
| Episodic Recall | 80-160 |
| Recent Window | 800-1400 |
| Current User Input | dynamic |
| Reserved Output | 512-768 |

Integration notes:

- `ModelContextProfile` stays the source for total usable input budget
- `ContextBudgetPlanner` should be extended to understand memory tiers, not only generic sections
- compact/minimal rendering should exist for runtime state, open threads, and facts

## Runtime State Maintenance

Runtime state should be updated from explicit operations, not from summary prose.

### Scene State Fields

Minimum fields for V1:

- `location`
- `timeContext`
- `objective`
- `activeEntities`
- `inventory`
- `lastMeaningfulChange`

### Relationship State Fields

Minimum fields for V1:

- `trust`
- `tension`
- `intimacy`
- `dominance`
- `mood`
- `lastShiftReason`

### Update Policy

- runtime state changes only through validated extractor operations
- user corrections override prior state immediately
- rollback restores the last accepted snapshot before the reverted turns

## Logging and Debuggability

The memory runtime must be observable.

New debug logs and structured events should cover:

- planner trigger reason
- planner output
- retrieval candidates and final memory pack
- prompt slot token usage
- extractor output
- validator rejection reasons
- promotion results
- drift monitor signals

Recommended event types:

- `MEMORY_PLANNER_TRIGGERED`
- `MEMORY_QUERY_EXECUTED`
- `MEMORY_PACK_COMPILED`
- `MEMORY_OP_EXTRACTED`
- `MEMORY_OP_REJECTED`
- `MEMORY_ATOM_PROMOTED`
- `RUNTIME_STATE_UPDATED`
- `OPEN_THREAD_UPDATED`
- `BRANCH_ACCEPTED`
- `BRANCH_REJECTED`

This extends the current `SessionEvent` logging model instead of replacing it.

## Performance Strategy

The design must satisfy PRD latency and memory targets under offline Android execution.

### Default Strategy

- use Kotlin logic + Room queries for most memory work
- use planner and extractor as low-frequency helper inference only
- default to no-thinking for planner and extractor
- skip planner on trivial continuation turns when recent window is sufficient
- skip extractor on some turns under load; write raw transcript first and defer maintenance

### Degradation Order

When resources are tight:

1. skip extractor
2. retrieve only runtime state and open threads
3. skip episodic retrieval
4. shrink recent window
5. preserve minimum role kernel

### Embedding Policy

V1:

- no dense embedding dependency
- no vector database
- no sidecar embedding process in the main path

Phase 3 optional enhancement:

- on-device embedding rerank only
- rerank top sparse candidates, not full-corpus retrieval

## Migration Strategy

The migration should avoid breaking the current roleplay feature in one step.

### Phase 0

Deliverables:

- new Room schema
- new domain models
- repository interfaces for memory atoms, runtime state, and open threads
- token instrumentation for prompt sections
- event/log scaffolding

No user-visible memory behavior change is required in this phase.

### Phase 1 MVP

Deliverables:

- character kernel compilation
- runtime state snapshots
- open-thread tracking
- candidate/stable semantic memory atoms
- sparse retrieval via FTS
- prompt compiler v1

Compatibility:

- keep legacy summary and memory reads as fallback while new pipeline stabilizes

### Phase 2 Stabilization

Deliverables:

- branch-safe acceptance model
- edit / rollback safety
- compaction cache
- user correction flow
- promotion rules
- validator hardening

### Phase 3 Optional Enhancement

Deliverables:

- embedding rerank sidecar
- richer entity alias graph
- automatic drift mitigation
- user-facing memory repair tools

## Concrete Code Changes

This section maps the design to concrete app modules.

### New Packages

Recommended new package structure under `selfgemma.talk.domain.roleplay`:

- `memory/model`
- `memory/repository`
- `memory/usecase`
- `memory/runtime`
- `memory/planner`
- `memory/extractor`
- `memory/validator`

Recommended data-layer packages:

- `data/roleplay/db/entity/memory`
- `data/roleplay/db/dao/memory`
- `data/roleplay/repository/memory`

### Existing Classes To Refactor

`SendRoleplayMessageUseCase`

- remove direct ownership of summary-as-memory behavior
- call planner/retriever/compiler before generation
- write assistant output as candidate branch turn first
- trigger extractor only after acceptance boundary

`PromptAssembler`

- accept structured runtime state and open threads as first-class prompt inputs
- stop treating session summary as a required continuity carrier

`PromptMaterialBuilder`

- add sections for:
  - `Runtime State`
  - `Open Threads`
  - `Semantic Facts`
  - `Episodic Recall`

`SummarizeSessionUseCase`

- convert into compaction-cache maintenance
- do not treat summary text as long-term truth

`ExtractMemoriesUseCase`

- convert current implementation into fallback extractor
- keep manual pinning support by converting it into explicit stable `MemoryAtom` writes

`RoleplayChatViewModel`

- expose branch acceptance, user correction, and memory-debug state later
- stop assuming that "generation completed" equals "canonical continuity committed"

## Out Of Scope For V1

The following are intentionally excluded from the first release architecture:

- cloud sync
- remote services of any kind
- vector-first retrieval
- multimodal long-term memory as a core path
- complex world simulation engine
- automatic full graph UI for memory visualization

## Acceptance Checklist

This design is considered correctly implemented when:

- role style remains stable over long sessions without relying on huge history replay
- scene continuity is preserved through explicit runtime state rather than summary prose
- unresolved promises and open loops are retrievable and prompt-visible
- a single hallucinated assistant reply cannot quickly pollute stable memory
- regenerate and edit do not leak rejected branches into canonical memory
- prompt compilation stays within small-window budgets with deterministic trim order
- the memory subsystem is debuggable through logs and event traces

## Engineering Notes

- Keep all UI text changes multi-language aware. This document introduces no runtime strings.
- Keep ST compatibility red lines intact. `RoleCard.stCard` remains canonical truth.
- Prefer additive migrations first. Remove legacy summary/memory dependencies only after the new
  pipeline proves stable.
- The send pipeline should remain responsive even when planner/extractor are skipped.

## Recommended Next Step

Implement `Phase 0` first:

1. add Room entities and DAOs for runtime state, open threads, and memory atoms
2. add repository interfaces and mappers
3. extend prompt instrumentation to report slot budgets
4. split current send flow interfaces without changing user-visible behavior yet

Do not start with embedding work.
