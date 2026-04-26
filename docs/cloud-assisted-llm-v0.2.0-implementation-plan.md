# Cloud-Assisted LLM v0.2.0 Implementation Plan

This document defines the first production slice for GemmaTavern cloud-assisted
LLM support. The app remains local-backend-first: roleplay state, memory,
tools, permissions, routing, logs, and fallback all run on device. Cloud models
are optional inference providers called directly from the local app with the
user's API key.

## Product Scope

v0.2.0 supports three fixed providers:

- OpenRouter
- DeepSeek
- Claude

The normal user settings surface exposes only:

- Provider
- API key
- Model name

The normal user settings surface must not expose:

- base URL
- endpoint path
- API version
- request headers
- stream format
- tool schema format
- provider-specific advanced parameters

Those details belong inside provider adapters. A future developer mode may add a
custom endpoint, but it is not part of the v0.2.0 user path.

## Model Name UX

Model presets are form-fill helpers only. Business routing must not depend on
hard-coded model-name branching.

DeepSeek presets:

- `deepseek-v4-flash` as the default recommendation
- `deepseek-v4-pro` for stronger reasoning and long context

Claude presets:

- `claude-sonnet-4-6` as the default recommendation
- `claude-opus-4-6` for strongest quality
- `claude-haiku-4-5` for fast and cheaper turns

OpenRouter does not ship a large in-app model list. The app provides a custom
model-name input and may remember recent user-entered values.

Users can always type a custom model name for any provider.

## Architecture

The roleplay flow should not learn provider-specific protocol details. v0.2.0
adds a cloud inference layer with a small internal request/response model:

```text
selfgemma/talk/domain/cloudllm/
  CloudProviderId
  CloudModelConfig
  CloudModelPreset
  CloudModelCapability
  CloudGenerationRequest
  CloudGenerationEvent
  CloudGenerationResult
  CloudProviderError
  CloudLlmProviderAdapter
  CloudModelRouter

selfgemma/talk/data/cloudllm/
  CloudCredentialStore
  CloudModelConfigRepository
  OpenRouterAdapter
  DeepSeekAdapter
  ClaudeAdapter
```

The existing roleplay runtime keeps ownership of:

- canonical chat messages
- stable synopsis
- semantic memory
- external evidence
- tool invocation persistence
- session events and debug export
- local fallback

Provider adapters own only:

- request serialization
- response and stream parsing
- provider error mapping
- provider capability defaults

## Routing Contract

The default policy is cloud-preferred, local-guaranteed:

1. If cloud is disabled, route to the local model.
2. If API key or model name is missing, route to the local model.
3. If the device is offline, route to the local model.
4. If the selected provider is in cooldown after repeated failures, route to the
   local model.
5. If the request requires a capability the provider cannot accept, normalize
   the request through local media/tool bridging when possible.
6. If cloud generation fails before a complete answer is committed, retry the
   same assistant placeholder through the local model.

Routing must not use keyword, regex, or enumerated intent rules to decide tool
calling. Tool calling stays model-driven. The host registers capabilities,
enforces permissions, executes approved tools, logs results, and injects tool
results back into the model turn.

## Provider Adapter Requirements

OpenRouter:

- Uses the OpenAI-compatible chat completions endpoint.
- Supports text and streaming in v0.2.0.
- Tool calling and image support are capability-gated by the selected model and
  local configuration.

DeepSeek:

- Uses the DeepSeek OpenAI-compatible chat completions endpoint.
- Supports text, streaming, and tool calls in v0.2.0.
- Raw audio and image upload are not required for v0.2.0.

Claude:

- Uses the Anthropic Messages API.
- Supports text, streaming, tool use, and image input when enabled.
- Tool calls are represented internally through the same `CloudToolCall` model
  used by the other providers.

## Multimodal Bridging

The app stores original media locally. Cloud upload is allowed only when the
user enables it and the provider/model capability allows it.

If cloud cannot accept a media type:

- Images are converted to local context text before dispatch.
- Audio is converted to local transcript or local semantic summary before
  dispatch.
- The cloud model receives the derived text and enough attachment metadata to
  understand that the user supplied media.

If cloud supports tool calling, local media understanding can also be exposed as
a tool. The cloud model decides whether to call it. The host does not trigger it
by keyword.

## Observability

Cloud routing adds session events and debug export data for:

- cloud route started
- cloud route completed
- cloud route fallback
- cloud provider error
- cloud media bridge used
- cloud connection test result

API keys must never be written to logs, Room, debug export, crash text, or test
fixtures.

## Implementation Milestones

1. Land this document and keep it as the v0.2.0 contract.
2. Add cloud domain models, provider presets, and repository interfaces.
3. Add encrypted credential storage for provider API keys.
4. Add minimal provider adapters and fake HTTP tests.
5. Add router and health/cooldown decisions.
6. Add cloud settings UI with localized strings.
7. Wire the roleplay inference path through a coordinator.
8. Add fallback session events and debug export fields.
9. Add multimodal normalization for image and audio attachments.
10. Run unit tests, debug build, and device install verification.

## Acceptance Criteria

- Users configure cloud inference with provider, API key, and model name only.
- DeepSeek and Claude show model preset chips, while all providers support
  custom model names.
- OpenRouter does not ship a large in-app model catalog.
- Cloud success and fallback both update the same roleplay turn cleanly.
- Offline, missing key, 401, 429, timeout, and 5xx paths all fall back locally.
- Tool calls remain model-driven and continue to persist through
  `tool_invocations`, `external_facts`, and `session_events`.
- Raw media never leaves the device unless the user enables cloud media upload.
- Debug export contains enough routing evidence to diagnose failures and no
  secrets.
