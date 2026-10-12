# Fisker Warm Lab 0.3.2 — CAN stream patch

- Incremental parsing of BLE CAN notifications; no accumulation of the entire capture in a 48 KB buffer.
- Bounded 1500 lines, 160 chars per line, 12 samples per ID; report `truncated` when limits are exceeded.
- `BUFFER FULL` from the adapter is still reported as `adapterBufferFull=true` and `captureQuality=INCOMPLETE`; this patch **cannot guarantee** elimination of hardware adapter overflow.
- Correct JSON appVersion 0.3.2-lab and Android versionCode 6.
- Heating remains blocked, no new vehicle commands.
- APK requires Android SDK 35 and signing; source ZIP is not installable on Samsung.
