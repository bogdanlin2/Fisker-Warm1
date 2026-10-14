#!/usr/bin/env python3
"""Build a platform-only Java APK with official Android SDK tools; no Gradle downloads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from release_profiles import DEFAULT_PROFILE, PROFILES, VERSION_CODE, VERSION_NAME

ROOT = Path(__file__).resolve().parents[1]

def run(args, **kw):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True, **kw)
    if result.stdout.strip(): print(result.stdout.strip())
    if result.stderr.strip(): print(result.stderr.strip())
    result.check_returncode()
    return result

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--sdk-root', default=os.environ.get('ANDROID_SDK_ROOT'))
    p.add_argument('--build-tools', help='Override directory containing aapt2, zipalign and lib/d8.jar')
    p.add_argument('--profile', choices=sorted(PROFILES), default=DEFAULT_PROFILE)
    args = p.parse_args()
    profile = PROFILES[args.profile]
    if not args.sdk_root: p.error('Set ANDROID_SDK_ROOT or --sdk-root')
    sdk = Path(args.sdk_root).resolve()
    candidates = [sdk / 'platforms/android-35/android.jar', sdk / 'android-35/android.jar']
    platform = next((f for f in candidates if f.is_file()), None)
    bt = Path(args.build_tools).resolve() if args.build_tools else sdk / 'build-tools/35.0.0'
    if platform is None or not (bt/'lib/d8.jar').is_file(): p.error('Need Android 35 platform and build-tools 35.0.0')
    java=shutil.which('java'); keytool=shutil.which('keytool')
    if not java: p.error('Need JDK 17 or later (java and compiler module)')
    if not keytool: keytool=str(Path(java).resolve().parent/'keytool')
    build=ROOT/'build'; build.mkdir(exist_ok=True)
    key=build/'local-lab-signing.p12'; password_file=build/'.local-signing-password'
    if not key.is_file() or not password_file.is_file():
        p.error('Restore the existing signing key and password for the selected profile into build/; new keys are never generated automatically')
    env=os.environ.copy();env['FISKERWARM_SIGNING_PASSWORD']=password_file.read_text().strip()
    if not env['FISKERWARM_SIGNING_PASSWORD']: p.error('Signing password is empty')
    certificate=build/'signer-certificate.der'
    run([keytool,'-exportcert','-keystore',key,'-alias','lab','-storepass:env','FISKERWARM_SIGNING_PASSWORD','-file',certificate],env=env)
    if hashlib.sha256(certificate.read_bytes()).hexdigest() != profile['certificateSha256']:
        p.error('Signing certificate differs from the pinned certificate for ' + profile['applicationId'])
    classes=build/'classes'; dex=build/'dex'
    for d in [classes,dex]:
        if d.exists(): shutil.rmtree(d)
        d.mkdir()
    sources=sorted((ROOT/'app/src/main/java').rglob('*.java'))
    run([java,'com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-classpath',platform,'-d',classes,*sources])
    jar=build/'classes.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
        for f in sorted(classes.rglob('*.class')): z.write(f,f.relative_to(classes).as_posix())
    run([java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',platform,'--output',dex,jar])
    tool_ext='.exe' if os.name=='nt' else ''
    resource=build/'resources.apk'
    source_manifest=ROOT/'app/src/main/AndroidManifest.xml'
    manifest_text=source_manifest.read_text(encoding='utf-8')
    original_package='package="com.fiskerwarm.lab03"'
    if manifest_text.count(original_package) != 1:
        p.error('Source Manifest must keep the canonical com.fiskerwarm.lab03 package')
    build_manifest=build/'AndroidManifest.xml'
    build_manifest.write_text(manifest_text.replace(original_package,'package="'+profile['applicationId']+'"'),encoding='utf-8')
    run([bt/('aapt2'+tool_ext),'link','-I',platform,'--manifest',build_manifest,
         '--min-sdk-version','26','--target-sdk-version','35','-o',resource])
    unsigned=build/'unsigned.apk'; aligned=build/'aligned.apk'
    shutil.copyfile(resource,unsigned)
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
        for f in sorted(dex.glob('*.dex')): z.write(f,f.name)
    run([bt/('zipalign'+tool_ext),'-f','4',unsigned,aligned])
    out=ROOT/'dist';out.mkdir(exist_ok=True);apk=out/profile['apkFilename']
    run([java,'-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','lab',
         '--ks-pass','env:FISKERWARM_SIGNING_PASSWORD','--key-pass','env:FISKERWARM_SIGNING_PASSWORD','--out',apk,aligned],env=env)
    verification=run([java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',apk])
    (build/'apk-signature.txt').write_text(verification.stdout,encoding='utf-8')
    manifest=run([bt/('aapt2'+tool_ext),'dump','badging',apk])
    (build/'apk-badging.txt').write_text(manifest.stdout,encoding='utf-8')
    (build/'build-result.json').write_text(json.dumps({
        'file':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'sizeBytes':apk.stat().st_size,
        'compileSdk':35,'targetSdk':35,'minSdk':26,'profile':args.profile,
        'applicationId':profile['applicationId'],'versionCode':VERSION_CODE,'versionName':VERSION_NAME,
        'signerCertificateSha256':profile['certificateSha256'],'heatingAvailable':False,'readOnlyCanCapture':True,'climateProtocolVerified':False,
        'built':True,'installedOnSamsung':False,'testedOnVehicle':False,
        'sourceCommit':run(['git','rev-parse','HEAD'],cwd=ROOT).stdout.strip(),
        'sourceTreeDirty':bool(run(['git','status','--porcelain'],cwd=ROOT).stdout.strip()),
        'diagnosticRevision':'can-diagnostics-1',
    },indent=2)+'\n')
    print('APK:',apk)

if __name__=='__main__': main()
