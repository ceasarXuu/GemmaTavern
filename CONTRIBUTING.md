# Contributing

Thanks for contributing to Gemma Tavern.

## Scope

- Keep changes focused.
- Do not mix feature work with unrelated cleanup.
- Do not commit generated files, temporary exports, private credentials, or local-only reports.

## Before opening a pull request

From `Android/src`, run:

1. `./gradlew.bat :app:testDebugUnitTest`
2. `./gradlew.bat :app:lintRelease`
3. `./gradlew.bat :app:assembleRelease`

If your change affects app launch or install behavior, also verify on a device:

1. `adb install -r .\app\build\outputs\apk\release\app-release.apk`
2. `adb shell am start -W -n selfgemma.talk/.MainActivity`

## Documentation rules

- Update public docs when build, release, or user-visible behavior changes.
- Keep `README.md`, `DEVELOPMENT.md`, and `RELEASING.md` consistent.
- Only stable public references belong under `docs/`.

## Localization

This project ships multiple locales. Any user-facing text change should either:

1. update the affected translations, or
2. avoid changing UI copy until translations are ready.

## Pull request notes

Include:

- what changed,
- how you verified it,
- whether optional services or credentials are required.

