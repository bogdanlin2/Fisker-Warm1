"""Public, pinned package/certificate pairs; no private signing material."""

# Real CAN diagnostics changes; keep the user-visible 0.3.3 name.
# Receive-filter network audit is new functionality; update the installed code 8 APK.
VERSION_CODE = 9
VERSION_NAME = '0.3.3-lab'
DIAGNOSTIC_REVISION = 'can-network-audit-1'
DEFAULT_PROFILE = 'lab03'
PROFILES = {
    'lab03': {
        'applicationId': 'com.fiskerwarm.lab03',
        'certificateSha256': '3c1716e279eb2a9cfc8b1c90d54845a2cb74a92ecc1df3bf2dc0955a2b39be74',
        'apkFilename': 'Fisker-Warm-Lab-0.3.3-CAN-Network-Audit.apk',
        'artifactName': 'Fisker-Warm-Lab-0.3.3-CAN-Network-Audit-APK',
    },
    'lab02': {
        'applicationId': 'com.fiskerwarm.lab02',
        'certificateSha256': '848edb87d8b74d2c20efd191a0fa3086c2466aa9a1169628826ac8f5fb78983e',
        'apkFilename': 'Fisker-Warm-Lab-0.3.3-CAN-Network-Audit-Update02.apk',
        'artifactName': 'Fisker-Warm-Lab-0.3.3-CAN-Network-Audit-Update02-APK',
    },
}
