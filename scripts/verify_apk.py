#!/usr/bin/env python3
"""Inspect the built APK's signature and finite foreground-only manifest contract."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIGNER_SHA256 = '3c1716e279eb2a9cfc8b1c90d54845a2cb74a92ecc1df3bf2dc0955a2b39be74'

def run(argv):
    return subprocess.run([str(a) for a in argv], check=True, text=True, capture_output=True).stdout

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-tools', required=True)
    parser.add_argument('--apk', default=str(ROOT/'dist/Fisker-Warm-Lab-0.3.3.apk'))
    parser.add_argument('--previous-apk', action='append', default=[], help='Inspect package/signature compatibility with a previous APK')
    args = parser.parse_args()
    bt = Path(args.build_tools).resolve()
    apk = Path(args.apk).resolve()
    java = shutil.which('java')
    if not java: parser.error('JDK required')
    signature = run([java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',apk])
    badging = run([bt/'aapt2','dump','badging',apk])
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    signer = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',signature)
    xml = run([bt/'aapt2','dump','xmltree','--file','AndroidManifest.xml',apk])
    run([bt/'zipalign','-c','4',apk])
    permissions = set(re.findall(r'"(android\.permission\.[A-Z_]+)"', xml))
    expected = {'android.permission.BLUETOOTH','android.permission.BLUETOOTH_ADMIN',
                'android.permission.ACCESS_FINE_LOCATION','android.permission.BLUETOOTH_SCAN',
                'android.permission.BLUETOOTH_CONNECT'}
    checks = {
        'apkSignatureV2': 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature,
        'apkSignatureV3': 'Verified using v3 scheme (APK Signature Scheme v3): true' in signature,
        'expectedSigningCertificate': bool(signer and signer.group(1) == SIGNER_SHA256),
        'onlyOneSigner': 'Number of signers: 1' in signature,
        'applicationId': bool(package and package.group(1) == 'com.fiskerwarm.lab03'),
        'versionCode': bool(package and package.group(2) == '7'),
        'versionName': bool(package and package.group(3) == '0.3.3-lab'),
        'minSdk26': "minSdkVersion:'26'" in badging,
        'targetSdk35': "targetSdkVersion:'35'" in badging,
        'applicationLabel': "application: label='Fisker Warm Lab 0.3.3'" in badging,
        'exactPermissionSet': permissions == expected,
        'noInternet': 'android.permission.INTERNET' not in permissions,
        'noServicesReceiversProviders': not re.search(r'E: (service|receiver|provider)\b',xml),
        'onlyOneActivity': len(re.findall(r'E: activity\b',xml)) == 1,
        'notDebuggable': bool(re.search(r':debuggable\([^\n]+\)=false',xml)),
        'backupDisabled': bool(re.search(r':allowBackup\([^\n]+\)=false',xml)),
        'cleartextDisabled': bool(re.search(r':usesCleartextTraffic\([^\n]+\)=false',xml)),
        'aligned': True,
    }
    result = {'apk':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
              'applicationId':package.group(1) if package else None,
              'signerCertificateSha256':signer.group(1) if signer else None,
              'checks':checks,'installedOnPhone':False,'vehicleTested':False,
              'scope':'Artifact inspection only; does not validate BLE hardware or heating.'}
    compatibility=[]
    for previous in args.previous_apk:
        old=Path(previous).resolve()
        old_signature=run([java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',old])
        old_badging=run([bt/'aapt2','dump','badging',old])
        old_package=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",old_badging)
        old_signer=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',old_signature)
        if not old_package or not old_signer: raise SystemExit('Could not inspect previous APK identity')
        same_package=bool(package and old_package.group(1)==package.group(1))
        same_signer=bool(signer and old_signer.group(1)==signer.group(1))
        higher_version=bool(package and int(package.group(2))>int(old_package.group(2)))
        compatibility.append({'apk':old.name,'applicationId':old_package.group(1),'versionName':old_package.group(3),
                              'signerCertificateSha256':old_signer.group(1),'sameApplicationId':same_package,
                              'sameSigningCertificate':same_signer,'higherVersionCode':higher_version,
                              'inPlaceUpdateCompatible':same_package and same_signer and higher_version})
    if compatibility: result['previousApkCompatibility']=compatibility
    out = ROOT/'build'; out.mkdir(exist_ok=True)
    (out/'apk-manifest-tree.txt').write_text(xml,encoding='utf-8')
    (out/'apk-inspection.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))
    if not all(checks.values()): raise SystemExit('APK contract inspection failed')

if __name__ == '__main__': main()
