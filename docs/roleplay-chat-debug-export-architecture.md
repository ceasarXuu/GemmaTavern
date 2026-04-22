# Roleplay Chat Debug Export Architecture

This document defines the recommended debug-time export flow for pulling a
specific roleplay conversation out of a real Android device quickly and
reliably for dialogue-quality analysis.

## Goal

Make it cheap to answer:

- "Export the chat I am looking at right now."
- "Export that specific session from the sessions list."
- "Let Codex pull the exported bundle with adb and inspect it immediately."

The export must be:

- one-tap from the target chat or session,
- zero-config in the open-source build,
- accessible through normal `adb pull`,
- richer than the current ST-compatible chat export,
- and stable enough for repeat debugging.

## Current state

The repository already has a session-level export flow:

- `SessionsScreen.kt` exposes `exportChatJsonl(...)`
- `SessionsViewModel.kt` calls `ExportStChatJsonlFromSessionUseCase`
- `ExportStChatJsonlFromSessionUseCase.kt` exports messages into ST-compatible
  JSONL
- `StChatInteropMapper.kt` converts `Message` into interop records

That path is useful for SillyTavern compatibility, but it is not the right
artifact for runtime debugging because it drops or flattens critical data:

- `Message.status`, `accepted`, `isCanonical`, `branchId`, `latencyMs`
- `SessionEvent` debug payloads
- `ToolInvocation` rows
- raw `metadataJson`
- attachment metadata needed to understand image/audio turns
- export provenance such as app version and active model

## Product decision

Keep the current ST export untouched.

Add a second export path dedicated to debugging:

- feature name: `Roleplay Debug Export`
- artifact type: `roleplay_debug_bundle_v1`
- output format: single UTF-8 JSON file
- destination: public Downloads subfolder that adb can read directly

The debug export should be the default artifact for Codex-side conversation
analysis. The ST export remains an interoperability feature.

## User flow

### Entry points

Add two entry points:

1. `SessionsScreen`
   - add `Export Debug Bundle` alongside the existing import/export actions
   - this covers "export a specific chat from the session list"
2. `RoleplayChatScreen`
   - add `Export Debug Bundle` in the top-right overflow menu
   - this covers "export the chat I am currently looking at"

### Export behavior

When the user taps `Export Debug Bundle`:

1. Resolve the target `sessionId`
2. Build a snapshot from Room repositories
3. Serialize the snapshot into one JSON document
4. Write it to a known public path
5. Write or update a small `latest-debug-export.json` pointer file
6. Append a `SessionEventType.EXPORT` event for auditability
7. Show a short status message containing:
   - session title
   - short session id
   - exported file name

No SAF picker should be used for this debug path. The whole point is to avoid
friction.

## Output location

Use MediaStore Downloads so the app can write without asking for storage
permission on modern Android.

Recommended logical folder:

- `Download/GemmaTavern/debug-exports/`

Recommended files:

- `roleplay-debug-<sessionId>-<yyyyMMdd-HHmmss>.json`
- `latest-debug-export.json`
- optional later: `debug-export-index.jsonl`

The `latest-debug-export.json` pointer file should contain:

- `schemaVersion`
- `sessionId`
- `roleName`
- `title`
- `exportedAt`
- `relativePath`
- `messageCount`
- `toolInvocationCount`

This makes retrieval trivial from adb:

```powershell
adb shell cat /sdcard/Download/GemmaTavern/debug-exports/latest-debug-export.json
adb pull /sdcard/Download/GemmaTavern/debug-exports/roleplay-debug-<sessionId>-<timestamp>.json
```

## Export bundle schema

Use one top-level JSON object. Do not spread one export over multiple files in
v1.

Recommended shape:

```json
{
  "schemaVersion": "roleplay_debug_bundle_v1",
  "exportedAt": 1760000000000,
  "app": {
    "applicationId": "selfgemma.talk",
    "versionName": "0.x.x",
    "versionCode": 0,
    "debugBuild": true
  },
  "session": {
    "id": "...",
    "title": "...",
    "roleId": "...",
    "activeModelId": "...",
    "createdAt": 0,
    "updatedAt": 0,
    "turnCount": 0,
    "summaryVersion": 0
  },
  "role": {
    "id": "...",
    "name": "...",
    "systemPrompt": "...",
    "personaDescription": "...",
    "worldSettings": "...",
    "openingLine": "..."
  },
  "userProfile": {
    "defaultPersonaId": "...",
    "personaName": "..."
  },
  "summary": { },
  "messages": [ ],
  "toolInvocations": [ ],
  "sessionEvents": [ ],
  "notes": {
    "exportKind": "manual_debug_export"
  }
}
```

### Messages section

Export the raw conversation model, not the ST projection.

Each message entry should preserve:

- `id`
- `seq`
- `side`
- `kind`
- `status`
- `accepted`
- `isCanonical`
- `branchId`
- `content`
- `isMarkdown`
- `errorMessage`
- `latencyMs`
- `accelerator`
- `parentMessageId`
- `regenerateGroupId`
- `editedFromMessageId`
- `supersededMessageId`
- `metadataJson`
- `createdAt`
- `updatedAt`

### Tool invocations section

Export the stored tool rows directly:

- `toolName`
- `status`
- `source`
- `stepIndex`
- `argsJson`
- `resultJson`
- `resultSummary`
- `artifactRefs`
- `errorMessage`
- `startedAt`
- `finishedAt`

### Session events section

Export all session events for the session, not just a recent tail. This keeps
memory planner, overflow recovery, continuity rollback, export, and tool result
application visible.

## Media policy

Do not inline large binary media in v1.

For image/audio messages:

- export attachment metadata from `metadataJson`
- preserve local URI strings if already stored
- preserve generated context text if present
- do not embed image bytes or audio bytes into the debug bundle

This keeps the export small enough for quick adb transfer while still letting
Codex understand what happened in the turn.

If later needed, add a separate opt-in `includeMediaFiles` mode that emits a
zip bundle. That should not be v1.

## Implementation shape

### New use cases

- `ExportRoleplayDebugBundleFromSessionUseCase`
  - orchestrates snapshot loading and file writing
- `WriteRoleplayDebugBundleUseCase`
  - owns MediaStore destination logic

### New serializer / mapper

- `RoleplayDebugExportMapper`
  - converts `Session`, `RoleCard`, `Message`, `ToolInvocation`,
    `SessionEvent`, and `SessionSummary` into the export DTO

### Repository reads to reuse

- `ConversationRepository.getSession(sessionId)`
- `ConversationRepository.listMessages(sessionId)`
- `ConversationRepository.listEvents(sessionId)`
- `ConversationRepository.getSummary(sessionId)`
- `RoleRepository.getRole(roleId)`
- `ToolInvocationRepository.listBySession(sessionId)` or
  `listBySessionAndTurn(...)` equivalent

If `ToolInvocationRepository` does not already expose session-wide listing,
add it. A debug export should not reconstruct tool rows indirectly from
`SessionEvent`.

### UI wiring

- `SessionsScreen.kt`
  - add a second export action labeled for debugging
- `SessionsViewModel.kt`
  - add `exportDebugBundle(sessionId: String)`
- `RoleplayChatScreen.kt`
  - add overflow action for current session export
- optionally `RoleplayChatViewModel.kt`
  - expose status message if chat-screen export is initiated there

## Logging and observability

Every debug export should append one `SessionEventType.EXPORT` entry with a
payload like:

```json
{
  "exportKind": "roleplay_debug_bundle_v1",
  "sessionId": "...",
  "fileName": "...",
  "messageCount": 42,
  "toolInvocationCount": 6
}
```

This keeps debug exports visible in the session history and helps explain which
artifact was generated from which state.

## Privacy and safety

The debug export is for developer inspection. It must still be explicit.

Rules:

- only export on explicit user action
- do not auto-export in background
- do not upload anywhere
- do not include secrets from DataStore
- do not include model files or binary media in v1

If a future release build exposes this action, the UI copy should make clear
that the artifact can contain private chat content.

## Recommended retrieval workflow for Codex

Target workflow after implementation:

1. User opens the target chat or session
2. User taps `Export Debug Bundle`
3. User tells Codex "I exported it"
4. Codex runs:

```powershell
adb shell cat /sdcard/Download/GemmaTavern/debug-exports/latest-debug-export.json
adb pull /sdcard/Download/GemmaTavern/debug-exports/<resolved-file-name> <local-temp-dir>
```

5. Codex reads the pulled JSON directly and analyzes:
   - prompt/response quality
   - interruptions and retries
   - tool decisions
   - continuity and memory events

No database copying, `run-as`, WAL juggling, or force-stop should be needed in
the steady-state debug workflow.

## Rollout order

1. Add export DTO and mapper
2. Add MediaStore writer and fixed debug export path
3. Add session-level UI action in `SessionsScreen`
4. Add current-chat action in `RoleplayChatScreen`
5. Add `latest-debug-export.json` pointer file
6. Add export event logging
7. Add unit tests for mapping and file writing

## Acceptance criteria

The feature is only done when all of these are true:

- exporting a chosen session requires one tap and no SAF file picker
- the artifact can be pulled by adb from Downloads
- the bundle contains raw messages, tool invocations, and session events
- export success writes an `EXPORT` session event
- Codex can inspect the chosen chat without touching the live Room database
