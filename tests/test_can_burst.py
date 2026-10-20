"""Independent raw-byte recount + Java-export validation; no vehicle connection."""
import json
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from analyze_can_rx_burst import raw_lines, valid_can


class CanBurstLogChecks(unittest.TestCase):
    def setUp(self):
        self.fixture=json.loads((ROOT/'verification/stage7-5/recorded-burst-fixture.json').read_text())
        self.analysis=json.loads((ROOT/'verification/stage7-5/measured-burst-analysis.json').read_text())
        self.packets=[e for e in self.fixture['monitorEvents'] if e['kind']=='RX_NOTIFICATION']

    def test_independent_40_lines_2_malformed_and_no_packet_duplicates(self):
        lines=raw_lines(self.packets);frames=[l for l in lines if valid_can(l['text'])]
        self.assertEqual(len(frames),40)
        self.assertEqual({re.sub(r'\s+',' ',f['text']) for f in frames},{'0E9 8 2D F0 FF FF 9E 57 FF FF'})
        self.assertEqual(sum(len(bytes.fromhex(p['hex'])) for p in self.packets),1305)
        self.assertEqual([len(bytes.fromhex(p['hex'])) for p in self.packets],[200]*6+[105])
        self.assertEqual(len({p['hex'] for p in self.packets}),7)
        self.assertEqual([l['text'] for l in lines if l['text'].startswith('0E9') and not valid_can(l['text'])],
                         ['0E9 8 2D F0    9E  FF FF','0E9 8 2D F0 FF F'])

    def test_6_before_space_34_after_and_overflow_after_gatt_completion(self):
        events=self.fixture['monitorEvents']
        stop=next(e for e in events if e['kind']=='TX_REQUEST' and bytes.fromhex(e['hex'])==b' ')
        ack=next(e for e in events if e['kind']=='TX_CALLBACK' and e['txId']==stop['txId'])
        frames=[l for l in raw_lines(self.packets) if valid_can(l['text'])]
        self.assertEqual(sum(f['receivedNanos']<stop['receivedNanos'] for f in frames),6)
        self.assertEqual(sum(f['receivedNanos']>stop['receivedNanos'] for f in frames),34)
        self.assertEqual(sum(f['receivedNanos']<ack['receivedNanos'] for f in frames),25)
        self.assertIn(b'BUFFER FULL\rSTOPPED\r\r>',bytes.fromhex(self.packets[-1]['hex']))
        self.assertGreater(self.packets[-1]['receivedNanos'],ack['receivedNanos'])
        self.assertAlmostEqual((stop['receivedNanos']-frames[2]['receivedNanos'])/1e6,6.070105,places=6)
        self.assertAlmostEqual((self.packets[-1]['receivedNanos']-stop['receivedNanos'])/1e6,95.737083,places=6)

    def test_analysis_keeps_old_captures_out_and_has_no_physical_frame_rate_claim(self):
        a=self.analysis
        self.assertEqual(a['selectedRunAppVersion'],'0.3.5-lab');self.assertEqual(a['historicalRunsExcluded'],5)
        self.assertFalse(a['analysisIsNewVehicleTest'])
        self.assertFalse(a['timing']['physicalOverflowTimeKnown']);self.assertFalse(a['timing']['physicalCanFrameTimesKnown'])
        self.assertEqual(a['timing']['captureElapsedMillisUntilStopDecision'],53)
        self.assertAlmostEqual(a['timing']['lastCaptureNotificationRelativeMs'],149.615104,places=6)
        self.assertTrue(a['timing']['originalApplicationAdapterResetConfirmed'])
        self.assertEqual(a['timing']['postCaptureResetEvents'][-1]['ascii'],'\r\rELM327 v2.2\r\r>')

    def test_roundtrip_never_turns_lossy_presence_evidence_into_complete_capture(self):
        d=json.loads(subprocess.check_output(['java','-Dfiskerwarm.repoRoot='+str(ROOT),'-cp',str(ROOT/'build/core-tests'),'CanBurstExportFixture'],text=True))
        self.assertFalse(d['newVehicleTest']);self.assertEqual(d['dataOrigin'],'RECORDED_BYTES_REPLAYED_AS_SYNTHETIC')
        self.assertTrue(d['controlPresenceObserved']);self.assertTrue(d['controlRecoveryAllowed'])
        c=d['capture'];self.assertTrue(c['incomplete']);self.assertTrue(c['adapterBufferFull'])
        self.assertEqual(c['invalidFrameLines'],2);self.assertEqual(len(c['invalidLineSamples']),2)
        self.assertEqual((c['frameCountAtAcquisitionEnd'],c['drainFrameCount'],c['frameCount']),(6,34,40))
        self.assertEqual(c['frameCountScope'],'Includes stop drain')
        self.assertTrue(c['containsSyntheticInput']);self.assertEqual({f['source'] for f in c['frames']},{'SYNTHETIC_TEST'})
        self.assertEqual(b''.join(bytes.fromhex(p['hex']) for p in c['notifications']),b''.join(bytes.fromhex(p['hex']) for p in self.packets))

    def test_android_exports_independent_completion_loss_presence_and_recovery_flags(self):
        source=(ROOT/'app/src/main/java/com/fiskerwarm/lab/BleProbe.java').read_text()
        for key in ['allCapturesComplete','controlStreamIncomplete','controlAdapterBufferFull','controlBeforeRxObserved',
                    'controlAfterRxObserved','eccRxObserved','eccCapturesComplete','recoveriesAttempted','recoveriesCompleted','differentialPhasesCompleted']:
            self.assertIn('"'+key+'"',source)
        self.assertIn('result.controlStreamIncomplete()',source)
        self.assertIn('Контрольні потоки НЕПОВНІ',source)
        # Pinned released 0.3.5 sources; works with a shallow CI checkout.
        self.assertEqual(hashlib.sha256((ROOT/'app/src/main/java/com/fiskerwarm/lab/core'/'PassiveNetworkPlan.java').read_bytes()).hexdigest(),'b56def53c2bf7babd2ba0b4f7a4077a2b712a4aa91a32e07c58f7b17b6e50b0a')
        self.assertEqual(hashlib.sha256((ROOT/'app/src/main/java/com/fiskerwarm/lab/core'/'GattWriteGate.java').read_bytes()).hexdigest(),'3661f5efb0de0047663f04b1b8a312529f2bab7eab35cc3542de8c9f9bdb6937')
        self.assertEqual(hashlib.sha256((ROOT/'app/src/main/java/com/fiskerwarm/lab/core'/'BleIoJournal.java').read_bytes()).hexdigest(),'c5fb7f38ae1095f3548f1942fa9a16f78d9a7c9c9171cfe518c0995ae753c885')


if __name__=='__main__':unittest.main()
