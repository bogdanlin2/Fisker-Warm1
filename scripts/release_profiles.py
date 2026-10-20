"""Public, pinned package/certificate pairs; no private signing material."""

# Stage 7.3: full-frame 30-second exact-ID passive capture, no vehicle commands.
VERSION_CODE = 10
VERSION_NAME = '0.3.4-lab'
APPLICATION_LABEL = 'Fisker Warm HVAC 0.3.4'
DIAGNOSTIC_REVISION = 'hvac-passive-stage7.3-1'
DEFAULT_PROFILE = 'lab03'
PROFILES = {
    'lab03': {
        'applicationId': 'com.fiskerwarm.lab03',
        'certificateSha256': '3c1716e279eb2a9cfc8b1c90d54845a2cb74a92ecc1df3bf2dc0955a2b39be74',
        'apkFilename': 'Fisker-Warm-HVAC-Passive-0.3.4-Lab03.apk',
        'artifactName': 'Fisker-Warm-HVAC-Passive-0.3.4-Lab03-APK',
    },
    'lab02': {
        'applicationId': 'com.fiskerwarm.lab02',
        'certificateSha256': '848edb87d8b74d2c20efd191a0fa3086c2466aa9a1169628826ac8f5fb78983e',
        'apkFilename': 'Fisker-Warm-HVAC-Passive-0.3.4.apk',
        'artifactName': 'Fisker-Warm-HVAC-Passive-0.3.4-APK',
    },
}
