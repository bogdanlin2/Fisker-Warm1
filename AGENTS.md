# Fisker Warm

This repository is the canonical editable source. The Android project is at the repository root; do not upload or extract a source ZIP in a workflow.

## Required invariants

- Keep `applicationId` / manifest package `com.fiskerwarm.lab03`.
- Do not modify `ReadOnlyPolicy.java` or `HeatSafety.java`.
- Do not add unverified CAN, UDS, or HVAC commands, or enable vehicle climate transmission.
- Heating remains blocked until independently verified on the owner's vehicle. A successful APK build is not evidence of vehicle operation.
- Never commit keystores, signing passwords, vehicle keys, or other secrets. Do not include them in logs, artifacts, or caches.
- Preserve the existing Lab03 signing certificate. Never generate a replacement key automatically.
- Keep the APK version, display name, output filename, and verifier expectations consistent. Increase Android versionCode for future releases.

## Build and verify

Use JDK 17+, official Android SDK Platform 35, and Build Tools 35.0.0.

```sh
python3 scripts/test_core.py
ANDROID_SDK_ROOT=/path/to/android-sdk python3 scripts/build_android.py
python3 scripts/verify_apk.py --build-tools /path/to/android-sdk/build-tools/35.0.0
```

All 151 core checks must pass. For local signing, restore the owner's existing key and password as `build/local-lab-signing.p12` and `build/.local-signing-password`, with directory mode 0700 and file modes 0600.

GitHub Actions builds `main` after each push and supports `workflow_dispatch`. It requires the repository Actions secrets `FISKERWARM_KEYSTORE_B64` and `FISKERWARM_SIGNING_PASSWORD`. Public certificate fingerprint: `3c1716e279eb2a9cfc8b1c90d54845a2cb74a92ecc1df3bf2dc0955a2b39be74`.

After a change, inspect the run and downloadable artifacts for that exact commit. Report permission or signing-secret blockers plainly; never substitute an APK signed with a different identity.
