#!/usr/bin/env python3
"""Build independent Remote/Gateway APKs using the established owner signing certificate."""
import argparse,hashlib,json,os,shutil,subprocess,zipfile
from pathlib import Path
from release_profiles import PROFILES
ROOT=Path(__file__).resolve().parents[1]
def run(args,**kw):
 r=subprocess.run([str(x) for x in args],capture_output=True,text=True,**kw)
 if r.returncode:
  print(r.stdout);print(r.stderr);r.check_returncode()
 return r

def main():
 p=argparse.ArgumentParser();p.add_argument('--sdk-root',default=os.environ.get('ANDROID_SDK_ROOT'));p.add_argument('--profile',choices=PROFILES,required=True);a=p.parse_args()
 if not a.sdk_root:p.error('Android SDK 35 is required')
 sdk=Path(a.sdk_root);platform=sdk/'platforms/android-35/android.jar';bt=sdk/'build-tools/35.0.0';java=shutil.which('java')
 if not platform.is_file() or not (bt/'lib/d8.jar').is_file():p.error('Need official Android SDK 35 + build-tools 35.0.0')
 key=ROOT/'build/local-lab-signing.p12';pwd=ROOT/'build/.local-signing-password'
 if not key.is_file() or not pwd.is_file():p.error('Restore existing owner key; this script never generates a key')
 env=os.environ.copy();env['FISKERWARM_SIGNING_PASSWORD']=pwd.read_text().strip()
 if not env['FISKERWARM_SIGNING_PASSWORD']:p.error('Empty signing password')
 expected=PROFILES[a.profile]['certificateSha256'];out=ROOT/'dist';out.mkdir(exist_ok=True);evidence=[]
 for role in ('remote','gateway'):
  build=ROOT/'build'/('internet-'+role)
  if build.exists():shutil.rmtree(build)
  build.mkdir();classes=build/'classes';dex=build/'dex';classes.mkdir();dex.mkdir()
  cert=build/'signer.der';keytool=shutil.which('keytool') or str(Path(java).resolve().parent/'keytool')
  run([keytool,'-exportcert','-keystore',key,'-alias','lab','-storepass:env','FISKERWARM_SIGNING_PASSWORD','-file',cert],env=env)
  if hashlib.sha256(cert.read_bytes()).hexdigest()!=expected:p.error('Owner certificate pin mismatch')
  sources=[]
  for directory in ('common','android',role):sources+=sorted((ROOT/'internet'/directory/'src').rglob('*.java'))
  run([java,'com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-classpath',platform,'-d',classes,*sources])
  jar=build/'classes.jar'
  with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
   for f in sorted(classes.rglob('*.class')):z.write(f,f.relative_to(classes).as_posix())
  run([java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',platform,'--output',dex,jar])
  unsigned=build/'unsigned.apk';aligned=build/'aligned.apk'
  run([bt/'aapt2','link','-I',platform,'--manifest',ROOT/'internet'/role/'AndroidManifest.xml','--min-sdk-version','26','--target-sdk-version','35','-o',unsigned])
  with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
   for f in sorted(dex.glob('*.dex')):z.write(f,f.name)
  run([bt/'zipalign','-f','4',unsigned,aligned])
  apk=out/('Fisker-Warm-'+role.title()+'.apk')
  run([java,'-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','lab','--ks-pass','env:FISKERWARM_SIGNING_PASSWORD','--key-pass','env:FISKERWARM_SIGNING_PASSWORD','--out',apk,aligned],env=env)
  sig=run([java,'-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',apk]).stdout
  if expected not in sig.lower():p.error('Signed APK certificate mismatch')
  badging=run([bt/'aapt2','dump','badging',apk]).stdout;manifest=run([bt/'aapt2','dump','xmltree',apk,'--file','AndroidManifest.xml']).stdout
  if "package: name='com.fiskerwarm."+role+"'" not in badging or "targetSdkVersion:'35'" not in badging:p.error('APK identity mismatch')
  if 'android.permission.INTERNET' not in badging:p.error('Missing internet permission')
  if 'BLUETOOTH_SCAN' in badging or 'ACCESS_FINE_LOCATION' in badging:p.error('Unnecessary sensitive permission')
  if role=='remote' and ('BLUETOOTH' in badging or 'FOREGROUND_SERVICE' in badging):p.error('Remote must not access vehicle/BLE or run a background service')
  for name,value in [('signature.txt',sig),('badging.txt',badging),('manifest.txt',manifest)]: (build/name).write_text(value)
  entry={'file':apk.name,'applicationId':'com.fiskerwarm.'+role,'versionCode':2,'versionName':'0.6.1-test','sizeBytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signerCertificateSha256':expected,'sourceCommit':run(['git','rev-parse','HEAD'],cwd=ROOT).stdout.strip(),'sourceTreeDirty':bool(run(['git','status','--porcelain'],cwd=ROOT).stdout.strip()),'minSdk':26,'targetSdk':35,'internetTestOnly':True,'vehicleCommandsImplemented':False,'installedOnPhysicalDevice':False,'screenOffOnPhysicalDeviceVerified':False}
  evidence.append(entry);print(apk.name+': signed and verified; '+entry['sha256'])
 (ROOT/'build/internet-apk-results.json').write_text(json.dumps(evidence,indent=2)+'\n')
if __name__=='__main__':main()
