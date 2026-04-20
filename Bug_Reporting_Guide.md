# Bug Reporting Guide

Please file bugs through GitHub Issues for this repository.

## Include these details

- app version or Git commit,
- device model and Android version,
- model name or model file involved,
- exact reproduction steps,
- expected result,
- actual result.

If the issue is intermittent, say how often it reproduces and whether it happens after a cold launch.

## Recommended attachments

- screenshot or screen recording,
- a short `adb logcat` excerpt around the failure,
- a full Android bug report zip for crashes, startup failures, or data-loss issues.

## Capture a full bug report on device

1. Enable Android developer options.
2. Reproduce the issue.
3. Open Settings and enter Developer options.
4. Tap `Take bug report`.
5. Choose `Full report`.
6. Attach the generated zip to the issue, or upload it somewhere stable and share the link.

## Capture diagnostics with adb

Use these commands if you already have adb set up.

```shell
adb devices
adb logcat -d > gemmatavern-logcat.txt
adb bugreport gemmatavern-bugreport
```

For a specific device:

```shell
adb -s <device-serial> logcat -d > gemmatavern-logcat.txt
adb -s <device-serial> bugreport gemmatavern-bugreport
```

## Sensitive data

Bug reports and logs can contain prompts, file names, package names, and other local device details.

- redact anything private before posting publicly,
- prefer trimmed logs when a full bug report is unnecessary,
- mention if you had to remove details that might affect diagnosis.
