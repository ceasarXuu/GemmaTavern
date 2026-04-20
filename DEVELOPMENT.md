# Development Notes

## Local build

The Android Gradle wrapper lives under `Android/src`.

Typical commands from the repository root:

1. `Set-Location .\Android\src`
2. `./gradlew.bat :app:assembleDebug`
3. `./gradlew.bat :app:testDebugUnitTest`

For release validation, also run:

1. `./gradlew.bat :app:lintRelease`
2. `./gradlew.bat :app:assembleRelease`

## Optional service configuration

The default open-source build intentionally ships without private service credentials.

Optional settings live in `Android/src/gradle.properties`:

- `HUGGINGFACE_CLIENT_ID=`
- `HUGGINGFACE_REDIRECT_URI=selfgemma.talk.auth:/oauth2redirect`
- `ENABLE_FIREBASE=false`

### Hugging Face OAuth

Gated Hugging Face model downloads require your own Hugging Face OAuth app.

When you configure it:

1. Set `HUGGINGFACE_CLIENT_ID`.
2. Set `HUGGINGFACE_REDIRECT_URI`.
3. Keep the redirect URI scheme aligned with your Hugging Face app registration.

If these values are left empty, the app will still build, but gated Hugging Face downloads stay disabled.

### Firebase / FCM

Firebase is off by default for the public repository.

- `google-services.json` is not included.
- `ENABLE_FIREBASE` should remain `false` unless you maintain your own Firebase project.
- If you fully wire Firebase for your fork, keep the Google Services plugin and runtime configuration aligned with that setup.

## Public documentation boundary

- `README.md`, `DEVELOPMENT.md`, and `RELEASING.md` are the source of truth for build and release flow.
- Stable architecture and validation references live under `docs/`.
- Internal plans, one-off reports, and temporary working notes should stay out of the repository.
