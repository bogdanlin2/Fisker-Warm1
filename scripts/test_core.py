#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
build=ROOT/'build/core-tests';build.mkdir(parents=True,exist_ok=True)
sources=sorted((ROOT/'app/src/main/java/com/fiskerwarm/lab/core').glob('*.java'))+[ROOT/'tests/CoreChecks.java']
compile_result=subprocess.run(['java','com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-d',str(build),*map(str,sources)],text=True,capture_output=True)
if compile_result.returncode:
    print(compile_result.stdout+compile_result.stderr);sys.exit(compile_result.returncode)
run=subprocess.run(['java','-cp',str(build),'CoreChecks'],text=True,capture_output=True)
print(run.stdout+run.stderr,end='')
(ROOT/'build/core-test-results.txt').write_text(run.stdout+run.stderr,encoding='utf-8')
sys.exit(run.returncode)
