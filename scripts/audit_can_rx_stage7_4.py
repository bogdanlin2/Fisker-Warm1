#!/usr/bin/env python3
"""Reproduce the sanitized stage7.4 measurement summary from original local exports. No network or vehicle writes."""
import argparse
import hashlib
import json
from pathlib import Path


def summarize(paths):
    out={'format':'fisker-warm-stage7-4-measured-summary-1','dataOrigin':'OWNER_REAL_EXPORT_DERIVED_SUMMARY',
         'analysisIsNewVehicleTest':False,'runtimeCommit':'6d6c45a16f9dd1f30a15c1c5755f1b304d6b7995',
         'files':[],'captures':[],'uniquePreflights':0}
    seen={}
    for path in sorted(map(Path,paths)):
        raw=path.read_bytes();d=json.loads(raw)
        if d.get('appVersion')!='0.3.4-lab':raise ValueError('This audit input must be the measured 0.3.4 export')
        out['files'].append({'name':path.name,'sha256':hashlib.sha256(raw).hexdigest(),'exportedRuns':len(d['runs'])})
        for run in d['runs']:
            key=run['startedAt']
            if key in seen:
                if seen[key]!=run:raise ValueError('Conflicting repeated capture: '+key)
                continue
            seen[key]=run
            if run['mode']=='HVAC_PREFLIGHT':out['uniquePreflights']+=1
            c=run.get('hvacCapture')
            if not c:continue
            start=c['startedNanos'];packets=c['notifications']
            out['captures'].append({'mode':run['mode'],'startedAt':key,
                'frameCount':c['frameCount'],'notificationCount':c['notificationCount'],'receivedBytes':c['receivedBytes'],
                'rawHex':[p['hex'] for p in packets],
                'firstNotificationOffsetMillis':round((packets[0]['receivedNanos']-start)/1e6,6) if packets else None,
                'captureElapsedMillis':c['captureElapsedMillis'],'endReason':c['endReason'],
                'stopWriteConfirmed':c['stopWriteConfirmed'],'stopConfirmed':c['stopConfirmed'],
                'invalidFrameLines':c['invalidFrameLines'],'truncated':c['truncated'],'containsSyntheticInput':c['containsSyntheticInput'],
                'startWriteMillisExported':'startWriteMillis' in c,'rawTxExported':'bleIo' in run,
                'marks':[{'kind':m['kind'],'secondsAfterMonitorStart':round((m['receivedNanos']-start)/1e9,6)} for m in run.get('ownerMarks',[])],
                'commandReplies':[{'command':e['command'],'reply':e['reply']} for e in run['events'] if 'command' in e]})
    return out


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('exports',nargs='+',type=Path);p.add_argument('--output',required=True,type=Path)
    a=p.parse_args();d=summarize(a.exports);a.output.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'captures':len(d['captures']),'preflights':d['uniquePreflights'],'output':str(a.output),'newVehicleTest':False}))


if __name__=='__main__':main()
