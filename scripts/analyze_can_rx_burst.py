#!/usr/bin/env python3
"""Independent raw-byte stage7.5 analysis; never calls the Android parser or a vehicle."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re


def raw_lines(packets):
    """Each line gets the host timestamp of its terminating byte's notification."""
    partial=bytearray();lines=[];offset=0
    for p in packets:
        for value in bytes.fromhex(p['hex']):
            offset+=1
            if value in (10,13):
                text=bytes(partial).strip();partial.clear()
                if text:lines.append({'text':text.decode('ascii'),'receivedNanos':p['receivedNanos'],'byteEnd':offset})
            else:partial.append(value)
    if partial:lines.append({'text':bytes(partial).decode('ascii').strip(),'receivedNanos':packets[-1]['receivedNanos'],'byteEnd':offset})
    return lines


def valid_can(text):
    parts=text.split()
    return (len(parts)>=2 and re.fullmatch(r'[0-7][0-9A-F]{2}',parts[0]) is not None
            and re.fullmatch(r'[0-8]',parts[1]) is not None and len(parts)==2+int(parts[1])
            and all(re.fullmatch(r'[0-9A-F]{2}',p) for p in parts[2:]))


def analyze(data,source_name,source_sha):
    candidates=[r for r in data['runs'] if r.get('mode')=='CAN_RX_CONTROL' and r.get('appVersion')=='0.3.5-lab']
    if len(candidates)!=1:raise ValueError('Expected exactly one NEW 0.3.5 control run')
    r=candidates[0];c=r['canRxAudit'][0]['capture'];start=c['startedNanos']
    assert c==r['hvacCapture'], 'Two serialized copies must not be counted twice'
    assert len(r['canRxAudit'])==1 and r['canRxAudit'][0]['phase']=='CONTROL_BEFORE'
    events=r['bleIo']['events'];ma=next(e for e in events if e['kind']=='TX_REQUEST' and bytes.fromhex(e['hex'])==b'ATMA\r')
    stop=next(e for e in events if e['kind']=='TX_REQUEST' and bytes.fromhex(e['hex'])==b' ' and e['receivedNanos']>ma['receivedNanos'])
    reset=next(e for e in events if e['kind']=='TX_REQUEST' and bytes.fromhex(e['hex'])==b'ATZ\r' and e['receivedNanos']>stop['receivedNanos'])
    packets=[e for e in events if e['kind']=='RX_NOTIFICATION' and ma['receivedNanos']<e['receivedNanos']<reset['receivedNanos']]
    assert [(p['hex'].replace(' ',''),p['receivedNanos']) for p in packets]==[(p['hex'].replace(' ',''),p['receivedNanos']) for p in c['notifications']]
    lines=raw_lines(packets);frames=[l for l in lines if valid_can(l['text'])]
    invalid=[l for l in lines if l['text'].startswith('0E9') and not valid_can(l['text'])]
    assert len(frames)==c['frameCount']==len(c['frames'])==40
    assert Counter(' '.join(f['text'].split()) for f in frames)==Counter(' '.join([f['id'],str(f['dlc']),bytes.fromhex(f['payload']).hex(' ').upper()]) for f in c['frames'])
    assert all(f['id']=='0E9' for f in c['frames'])
    selected=[e for e in events if ma['receivedNanos']<=e['receivedNanos']<reset['receivedNanos']]
    timeline=[];previous=None
    for e in selected:
        b=bytes.fromhex(e['hex']);is_rx=e['kind']=='RX_NOTIFICATION'
        row={'event':e['kind'],'timestampNanos':e['receivedNanos'],'relativeToCaptureMs':round((e['receivedNanos']-start)/1e6,6),
             'delayFromPreviousEventMs':None if previous is None else round((e['receivedNanos']-previous)/1e6,6),
             'bytes':len(b),'txId':e['txId'],'status':e['status']}
        if is_rx:
            row.update({'sha256':hashlib.sha256(b).hexdigest(),'validLinesCompletedInNotification':sum(f['receivedNanos']==e['receivedNanos'] for f in frames),
                        'cumulativeValidLines':sum(f['receivedNanos']<=e['receivedNanos'] for f in frames),'bufferFullText':b'BUFFER FULL' in b,'stoppedText':b'STOPPED' in b,'prompt':b'>' in b})
        else:row['ascii']=b.decode('ascii')
        timeline.append(row);previous=e['receivedNanos']
    first_limit=frames[2]['receivedNanos'];last_rx=packets[-1]['receivedNanos'];overflow=next(l for l in lines if l['text']=='BUFFER FULL')
    summary={'format':'fisker-warm-can-burst-analysis-1','dataOrigin':'OWNER_REAL_EXPORT_DERIVED_ANALYSIS','analysisIsNewVehicleTest':False,
             'sourceFile':source_name,'sourceSha256':source_sha,'selectedRunStartedAt':r['startedAt'],'selectedRunAppVersion':r['appVersion'],
             'historicalRunsExcluded':sum(x.get('appVersion')!='0.3.5-lab' for x in data['runs']),
             'counts':{'rawNotifications':len(packets),'rawBytes':sum(len(bytes.fromhex(p['hex'])) for p in packets),'independentlyValidCanLines':len(frames),
                       'identicalCompleteLineVariants':dict(Counter(' '.join(f['text'].split()) for f in frames)),
                       'malformedCanLines':invalid,'validLinesBeforeSpaceRequest':sum(f['receivedNanos']<stop['receivedNanos'] for f in frames),
                       'validLinesAfterSpaceRequest':sum(f['receivedNanos']>stop['receivedNanos'] for f in frames),
                       'distinctNotificationPayloads':len({p['hex'] for p in packets})},
             'timing':{'captureStartNanos':start,'captureElapsedMillisUntilStopDecision':c['captureElapsedMillis'],'stopDrainMillis':c['stopDrainMillis'],
                       'lastCaptureNotificationRelativeMs':round((last_rx-start)/1e6,6),'firstToLastNotificationMs':round((last_rx-packets[0]['receivedNanos'])/1e6,6),
                       'limitReachedNotificationNanos':first_limit,'limitExactCpuParseTimeWasRecorded':False,
                       'thresholdNotificationToSpaceRequestMs':round((stop['receivedNanos']-first_limit)/1e6,6),
                       'bufferFullNotificationAfterSpaceRequestMs':round((overflow['receivedNanos']-stop['receivedNanos'])/1e6,6),
                       'physicalOverflowTimeKnown':False,'physicalCanFrameTimesKnown':False,'originalApplicationAdapterResetConfirmed':r['adapterResetConfirmed'],
                       'postCaptureResetEvents':[{'kind':e['kind'],'timestampNanos':e['receivedNanos'],'relativeToCaptureMs':round((e['receivedNanos']-start)/1e6,6),'ascii':bytes.fromhex(e['hex']).decode('ascii')} for e in events if e['receivedNanos']>=reset['receivedNanos']]},
             'assertions':{'rawCaptureMatchesBoundaryJournal':True,'androidCountMatchesIndependentCount':True,'twoCaptureCopiesNotDoubleCounted':True,
                           'onlyOneAtmaRequest':sum(e['kind']=='TX_REQUEST' and bytes.fromhex(e['hex'])==b'ATMA\r' for e in events)==1,
                           'onlyOneStopSpace':sum(e['kind']=='TX_REQUEST' and bytes.fromhex(e['hex'])==b' ' for e in events)==1,
                           'noControl373PhasePerformed':True,'bufferFullReceivedAfterStopRequest':overflow['receivedNanos']>stop['receivedNanos']},'timeline':timeline}
    fixture={'format':'fisker-warm-recorded-burst-fixture-1','dataOrigin':'OWNER_RECORDED_BYTES_FOR_SYNTHETIC_REPLAY','sourceFile':source_name,'sourceSha256':source_sha,
             'captureStartNanos':start,'runStartedAt':r['startedAt'],'replayIsNewPhysicalCanRx':False,'monitorEvents':selected,
             'androidCaptureScalars':{k:v for k,v in c.items() if k not in ('frames','notifications')},'expectedValidFrames':40,'expectedMalformedLines':2}
    return summary,fixture


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('export',type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--fixture',type=Path)
    a=p.parse_args();raw=a.export.read_bytes();summary,fixture=analyze(json.loads(raw),a.export.name,hashlib.sha256(raw).hexdigest())
    a.output.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    if a.fixture:a.fixture.write_text(json.dumps(fixture,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'newControlRuns':1,'frames':summary['counts']['independentlyValidCanLines'],'malformedLines':len(summary['counts']['malformedCanLines']),'rawBytes':summary['counts']['rawBytes'],'physicalFrameRateMeasured':False}))


if __name__=='__main__':main()
