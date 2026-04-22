# Roleplay Supported Tools

This document is the source of truth for roleplay tools that the on-device
agent may use during a turn.

It tracks:

- whether a tool is implemented,
- whether it works out of the box,
- whether it depends on Android runtime permission,
- whether it depends on an external service,
- and the priority order for future delivery.

## Status legend

- `Implemented`: available in the app now.
- `Planned`: approved direction, not implemented yet.
- `Deferred`: intentionally not scheduled in the near term.
- `Avoid`: not aligned with the current product direction.

## Delivery policy

- Prefer local-first tools with no user configuration.
- Do not make paid third-party services a required dependency for core roleplay.
- Permission-gated tools must be explicit about privacy and user consent.
- External tool facts are turn-scoped and should not become long-term memory by default.

## Current catalog

| Priority | Tool | Status | Config | Permission / dependency | Purpose |
| --- | --- | --- | --- | --- | --- |
| P0 | `getDeviceSystemTime` | Implemented | None | No external dependency | Real-world date, lunar date, time, timezone |
| P1 | `getDeviceBatteryStatus` | Implemented | None | No external dependency | Battery percent, charging state, battery saver |
| P2 | `getDeviceNetworkStatus` | Implemented | None | `ACCESS_NETWORK_STATE` already declared | Online / offline, transport, validation, metered |
| P3 | `getDeviceContext` | Implemented | None | No external dependency | Locale, region, weekday, 12h/24h preference, language |
| P4 | `getApproximateLocation` | Implemented | None | Explicit settings toggle plus location runtime permission | City / district scale context for grounded replies |
| P5 | `getCalendarSnapshot` | Implemented | None | Explicit settings toggle plus calendar runtime permission | Upcoming events and near-term schedule context |
| P6 | `getNextAlarmHint` | Implemented | None | No external dependency | Natural "I need to wake up later" style grounding |
| P7 | `queryWikipedia` | Implemented | None | External network access only | Safe factual lookup without full web search scope |
| P8 | `getWeather` | Implemented | None | External weather service plus location consent | Weather grounding once location exists |
| P9 | `placeLookupOrMapContext` | Implemented | None | External OpenStreetMap / Nominatim lookup, optional location bias | Nearby place and map context, more agentic than humanizing |
| P10 | `webSearch` | Avoid | None | Search backend or scraping complexity | Useful, but not the right first step for "alive" roleplay |

## Current implementation order

The current recommended implementation order is:

1. `getDeviceSystemTime`
2. `getDeviceBatteryStatus`
3. `getDeviceNetworkStatus`
4. `getDeviceContext`
5. `getNextAlarmHint`
6. `getApproximateLocation`
7. `getCalendarSnapshot`
8. `queryWikipedia`
9. `getWeather`
10. `placeLookupOrMapContext`

Permission-gated tools are hidden from the model until the user explicitly
enables them in Settings and grants the matching Android runtime permission.

## Notes on deferred or avoided tools

- `webSearch` is intentionally not a near-term priority because it makes the
  role more capable at research, but does not by itself make the role feel more
  like a real person with a present-tense device context.
- `queryWikipedia`, `getWeather`, and `placeLookupOrMapContext` use public
  network endpoints with no project-owned API key. That keeps the open-source
  build zero-config, but maintainers should still watch reliability and rate
  limits over time.
- High-privacy tools such as notification reading, contacts, SMS, or gallery
  crawling are out of scope for now even if they could increase realism.
