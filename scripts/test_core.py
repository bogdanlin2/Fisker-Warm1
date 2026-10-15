#!/usr/bin/env python3
import json
import re
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
build=ROOT/'build/core-tests';build.mkdir(parents=True,exist_ok=True)
sources=sorted((ROOT/'app/src/main/java/com/fiskerwarm/lab/core').glob('*.java'))+sorted((ROOT/'tests').glob('*.java'))
compile_result=subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-d',str(build),*map(str,sources)],text=True,capture_output=True)
if compile_result.returncode:
    print(compile_result.stdout+compile_result.stderr);sys.exit(compile_result.returncode)
summary=[];output=[];failed=0
for suite in ['CoreChecks','CanDiagnosticsChecks','NetworkAuditChecks']:
    run=subprocess.run(['java','-cp',str(build),suite],text=True,capture_output=True,timeout=60)
    log=run.stdout+run.stderr;print(log,end='');output.append(log)
    total=re.search(r'^TOTAL (\d+) (?:CAN regression )?checks passed',log,re.MULTILINE)
    passed=run.returncode==0 and total is not None and int(total.group(1))>0
    summary.append({'suite':suite,'checksPassed':int(total.group(1)) if total else 0,'passed':passed,'exitCode':run.returncode})
    if not passed:failed=1
run=subprocess.run([sys.executable,'-m','unittest','discover','-s',str(ROOT/'tests'),'-p','test_network_audit.py'],text=True,capture_output=True,timeout=30,cwd=ROOT)
log=run.stdout+run.stderr;print(log,end='');output.append(log)
total=re.search(r'Ran (\d+) tests?',log)
passed=run.returncode==0 and total is not None and int(total.group(1))>0
summary.append({'suite':'NetworkAuditLogChecks','checksPassed':int(total.group(1)) if passed else 0,'passed':passed,'exitCode':run.returncode})
if not passed:failed=1
(ROOT/'build/core-test-results.txt').write_text(''.join(output),encoding='utf-8')
(ROOT/'build/offline-test-summary.json').write_text(json.dumps({'suites':summary,'allPassed':failed==0,'vehicleConnected':False},indent=2)+'\n')
sys.exit(failed)
