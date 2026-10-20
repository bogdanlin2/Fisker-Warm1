"""Independent round-trip + analysis of the sanitized real measurement summary."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT=Path(__file__).resolve().parents[1]

class CanRxExportChecks(unittest.TestCase):
    def test_raw_write_callback_and_nonrouted_rx_survive_export(self):
        d=json.loads(subprocess.check_output(['java','-cp',str(ROOT/'build/core-tests'),'CanRxExportFixture'],text=True))
        self.assertEqual(d['dataOrigin'],'SYNTHETIC_TEST')
        self.assertFalse(d['vehicleConnected'])
        events=d['bleIo']['events']
        self.assertEqual([e['kind'] for e in events[:4]],['TX_REQUEST','TX_QUEUE_RESULT','TX_CALLBACK','TX_COMPLETE'])
        for e in events[:4]: self.assertEqual(bytes.fromhex(e['hex']),b'ATMA\r')
        self.assertEqual(bytes.fromhex(events[4]['hex']),b'0E9 8 2D F0 FF FF 9E 57 FF FF\rSTOPPED\r\r>')
        self.assertEqual(events[5]['route'],'OTHER_ENDPOINT')
        self.assertEqual(bytes.fromhex(events[5]['hex']),b'ignored\r>')
        self.assertTrue(d['bleIo']['complete'])
        c=d['capture'];self.assertTrue(c['startWriteConfirmed']);self.assertEqual(c['startWriteMillis'],4)
        self.assertEqual(c['frames'][0]['id'],'0E9');self.assertEqual(c['frames'][0]['source'],'SYNTHETIC_TEST')
        self.assertNotIn('decoded',c['frames'][0])
        self.assertTrue(c['containsSyntheticInput'])

    def test_real_exports_have_no_hidden_can_bytes_and_are_deduplicated(self):
        d=json.loads((ROOT/'verification/stage7-4/measured-summary.json').read_text())
        self.assertEqual(d['dataOrigin'],'OWNER_REAL_EXPORT_DERIVED_SUMMARY')
        self.assertEqual(len(d['captures']),3);self.assertEqual(d['uniquePreflights'],2)
        self.assertEqual(len({c['startedAt'] for c in d['captures']}),3)
        for c in d['captures']:
            self.assertEqual([bytes.fromhex(s) for s in c['rawHex']],[b'STOPPED\r\r>'])
            self.assertEqual((c['frameCount'],c['notificationCount'],c['receivedBytes']),(0,1,10))
            self.assertTrue(30000<c['firstNotificationOffsetMillis']<30100)
            self.assertFalse(c['rawTxExported']);self.assertFalse(c['startWriteMillisExported'])
            self.assertFalse(c['containsSyntheticInput']);self.assertTrue(c['stopConfirmed'])
            cmds=[e['command'] for e in c['commandReplies']]
            self.assertEqual(cmds[-4:-1],['ATAR','ATCM7FF','ATCF'+c['mode'][-3:]])
            self.assertEqual(cmds[-1],'ATZ')
        self.assertAlmostEqual(d['captures'][2]['marks'][1]['secondsAfterMonitorStart'],15.102693,places=5)

    def test_android_uses_same_gate_and_logs_rx_before_routing(self):
        source=(ROOT/'app/src/main/java/com/fiskerwarm/lab/BleProbe.java').read_text()
        self.assertIn('writeGate.write(bytes,3000',source)
        self.assertIn('writeGate.complete(status)',source)
        block=source.split('onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value)')[1].split('@Override')[0]
        self.assertLess(block.index('recordIo("RX_NOTIFICATION"'),block.index('ch.receiveAdapterNotification'))
        self.assertIn('hvacWireBytesAllowed(bytes,mode==Mode.CAN_RX_CONTROL)',source)
        ui=(ROOT/'app/src/main/java/com/fiskerwarm/lab/MainActivity.java').read_text()
        self.assertIn('parked.setButtonTintList',ui)
        self.assertIn('CAN_RX_CONTROL',ui)
        self.assertNotIn('EditText',ui)

    def test_protected_sources_are_byte_identical(self):
        for name,sha in [('ReadOnlyPolicy.java','888c20d810f5076b2f9435ad31e768d47edeb3176b81a4108e691032617d3935'),('HeatSafety.java','83662b0bb388f9e4cc486978cfbd5d53fd0dbb73620cc183cb06b5178b35d1d1')]:
            self.assertEqual(hashlib.sha256((ROOT/'app/src/main/java/com/fiskerwarm/lab/core'/name).read_bytes()).hexdigest(),sha)

if __name__=='__main__':unittest.main()
