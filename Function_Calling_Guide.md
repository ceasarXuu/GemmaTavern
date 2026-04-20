# Extending Skills and Function Calling

Gemma Tavern exposes two extension surfaces:

1. shareable agent skills,
2. native Android actions implemented in Kotlin.

## Clone the repository

```shell
git clone https://github.com/ceasarXuu/GemmaTavern.git
cd GemmaTavern/Android/src
```

## Option 1: Add a shareable skill

Use this path when your feature can be described by instructions plus optional JavaScript.

- repository examples for community sharing live under `skills/<skill-name>/`
- built-in app skills live under `Android/src/app/src/main/assets/skills/<skill-name>/`
- every skill needs a `SKILL.md`
- JavaScript-backed skills also include a `scripts/` directory

See `skills/README.md` for the packaging format.

## Option 2: Add a native mobile action

Use this path when the model must trigger app-controlled Android behavior.

### 1. Define the action shape

Add the action type and payload model in `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/Actions.kt`.

### 2. Expose the tool to the model

Add a new tool entry in `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsTools.kt`.

Keep the tool description and parameter descriptions explicit so the model knows when to call it.

### 3. Implement the Android behavior

Handle the new action in `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsViewModel.kt`.

If the action needs extra context, update the system prompt in `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/mobileactions/MobileActionsTask.kt`.

## Validation

From `Android/src`, run:

```shell
./gradlew.bat :app:assembleDebug
```

Then verify on device:

```shell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -W -n selfgemma.talk/.MainActivity
```

## Rules for public contributions

- do not hardcode secrets or private URLs,
- keep user-facing strings localizable,
- document new permissions or optional services,
- update public docs if your extension changes setup or behavior.
