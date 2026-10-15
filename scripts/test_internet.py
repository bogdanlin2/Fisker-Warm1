#!/usr/bin/env python3
"""No Android device or vehicle required. Does not transmit automotive traffic."""
from pathlib import Path
import hashlib,json,subprocess,sys,shutil
ROOT=Path(__file__).resolve().parents[1]
BUILD=ROOT/'build/internet-tests';BUILD.mkdir(parents=True,exist_ok=True)
protected={'ReadOnlyPolicy.java':'888c20d810f5076b2f9435ad31e768d47edeb3176b81a4108e691032617d3935','HeatSafety.java':'83662b0bb388f9e4cc486978cfbd5d53fd0dbb73620cc183cb06b5178b35d1d1'}
for name,expected in protected.items():
 assert hashlib.sha256((ROOT/'app/src/main/java/com/fiskerwarm/lab/core'/name).read_bytes()).hexdigest()==expected,name+' changed'
sources=list((ROOT/'internet/common/src').rglob('*.java'))+[ROOT/'internet/tests/InternetChecks.java']
subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-d',str(BUILD),*[str(p) for p in sources]],check=True)
r=subprocess.run(['java','-cp',str(BUILD),'InternetChecks'],capture_output=True,text=True,check=True);print(r.stdout,end='')
node=subprocess.run(['node','--test','internet/relay/tests/relay.test.mjs'],cwd=ROOT,capture_output=True,text=True)
print(node.stdout,end='');print(node.stderr,end='',file=sys.stderr);node.check_returncode()
java_vector=subprocess.check_output(['java','-cp',str(BUILD),'InternetChecks','vector'],text=True).strip()
script="import {createHmac} from 'node:crypto'; console.log(createHmac('sha256',Buffer.from('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f','hex')).update('FW6\\nCOMMAND\\n0123456789abcdef0123456789abcdef\\n11111111111111111111111111111111\\n1700000000\\n1700000060\\nCLIMATE_ON_TEST\\nTEST_ONLY').digest('hex'));"
node_vector=subprocess.check_output(['node','--input-type=module','-e',script],text=True).strip();assert java_vector==node_vector,'Java/JS wire mismatch'
print('Cross-language HMAC vector: PASS; original safety files: unchanged')
(BUILD/'summary.json').write_text(json.dumps({'javaChecks':int(r.stdout.strip().split(':')[-1]),'nodeTestsPassed':True,'crossLanguageMac':True,'protectedSafetyFilesUnchanged':True,'physicalAndroidScreenOffTest':False,'vehicleCommandsSent':0},indent=2)+'\n')
