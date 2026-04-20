# Roleplay Context Budget Architecture

## Goal

This document defines the context management architecture for SelfGemma Talk under small on-device
context windows, with `4k` as the primary target.

The design must satisfy both constraints:

- prevent `error code 3` and similar input-overflow failures during chat
- preserve ST compatibility for card import/export, ST world info runtime semantics, and ST chat
  metadata round-trip

## Problem Statement

The current roleplay runtime already reconstructs prompts per turn, which is the correct direction
for local models. However, it still assumes the assembled prompt can fit into the model window after
combining:

- system prompt and role card content
- ST world info / lorebook activations
- session summary
- relevant memories
- recent conversation
- current user input

That assumption does not hold for small on-device models. In practice, a large ST card can exceed
the runtime budget even before recent conversation is included.

## Compatibility Red Lines

The following behaviors are non-negotiable and must remain stable:

1. `RoleCard.stCard` remains the canonical ST truth source.
2. ST import/export must continue to preserve unknown fields and raw metadata.
3. `StCharacterBookRuntime` remains responsible for ST world info activation and
   `chat_metadata` state progression, including sticky/cooldown/recursive behavior.
4. ST chat import/export continues to use the original `interopChatMetadataJson`.
5. App-local `summary` and `memory` must not change ST keyword activation semantics.
6. Prompt ordering for ST-specific blocks must stay compatible with the current assembler,
   especially `post_history_instructions`, `depth_prompt`, and lorebook before/after placement.

## Architecture

The runtime is split into four layers:

### 1. ST Truth Layer

This layer stores and preserves canonical ST-compatible data:

- `RoleCard.stCard`
- `RoleInteropState`
- `Session.interopChatMetadataJson`
- message-level interop metadata

This layer is used for import, export, editing, and ST semantic preservation. It is never replaced
by compressed runtime projections.

### 2. Runtime Compile Layer

This layer compiles a small-window runtime profile from the canonical ST card. It exists only to
support on-device inference.

The compiled runtime profile may include:

- compressed core prompt
- compressed persona prompt
- compressed world prompt
- compressed style prompt
- example dialogue digest
- token estimates and oversize flags

This layer must not mutate ST truth data.

### 3. Budget Planning Layer

This layer allocates the available input budget for one inference turn.

The planner receives:

- model context profile
- compiled runtime role profile
- activated ST runtime sections
- session summary
- relevant memories
- recent conversation
- pending user input

The planner decides which sections survive, which sections degrade, and which sections are dropped
for the current turn.

### 4. Inference Layer

This layer sends the final prompt to the model runtime. If the underlying runtime still reports an
overflow error, the system enters a single aggressive recovery pass instead of repeatedly retrying.

## Prompt Assembly Strategy

Prompt assembly is split into two phases:

1. Material collection
   - collect all candidate sections without applying whole-prompt budget cuts
2. Budget planning
   - choose a final set of sections that fit the current model budget

Candidate sections include:

- core role
- safety
- persona
- world
- example digest
- session summary
- relevant memories
- recent conversation
- ST lorebook blocks
- ST author notes
- ST depth prompts
- ST outlet entries

## Budget Strategy For 4k Models

Default starting profile for `4096` token models:

- context window: `4096`
- reserved output: `768`
- safety margin: `256`
- usable input: `3072`

Recommended initial input distribution:

- live user input: `256`
- system and safety: `384`
- runtime core role: `768`
- post-history and depth prompt: `256`
- session summary: `256`
- memories: `256`
- recent conversation: `640`
- activated lore: `256`

These values are intentionally conservative because the current token estimator is approximate.

## Overflow Recovery

Overflow handling uses two stages:

1. preflight prevention
   - estimate and trim before calling the runtime
2. runtime recovery
   - if the runtime still throws an overflow error, rebuild the prompt once in aggressive compact
     mode

Aggressive compact mode applies the following order:

- remove example digest
- shrink recent conversation to the minimum viable window
- keep only pinned or top-ranked memory
- switch the role core from compact mode to minimal mode
- keep only the highest-priority activated lore

After one aggressive retry, the runtime error is returned to the UI.

## Planned Execution

The implementation is split into small themes:

1. add model context profile and budget foundations
2. compile runtime role profile from canonical ST cards
3. split prompt assembly into material building and budget planning
4. wire budget preflight and overflow recovery into send flow
5. add logs, events, and coverage tests

## Current Status

Step 1 is the current implementation target:

- add `ModelContextProfile`
- add model-side context budget keys and defaults
- add tests for profile derivation

The first step does not change ST runtime semantics and does not yet alter prompt assembly.
