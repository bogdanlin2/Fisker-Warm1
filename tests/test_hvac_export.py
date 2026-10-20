"""Independent JSON export round-trip and command-surface regression checks."""
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class HvacExportChecks(unittest.TestCase):
    def test_full_payload_packets_marks_and_provenance(self):
        raw = subprocess.check_output(['java', '-cp', str(ROOT/'build/core-tests'), 'HvacExportFixture'], text=True)
        fixture = json.loads(raw)
        capture = fixture['capture']
        self.assertEqual(fixture['dataOrigin'], 'SYNTHETIC_TEST')
        self.assertFalse(fixture['vehicleClimateStartedByApp'])
        self.assertTrue(capture['containsSyntheticInput'])
        self.assertEqual(capture['retainedFrames'], 2)
        for frame in capture['frames']:
            self.assertEqual(frame['id'], '534')
            self.assertEqual(frame['dlc'], 8)
            self.assertEqual(len(bytes.fromhex(frame['payload'])), 8)
            self.assertEqual(frame['source'], 'SYNTHETIC_TEST')
            self.assertEqual(frame['receivedNanos'], 5_000_000)
        self.assertEqual([f['decoded']['ICC_ECCSysSwtCmd'] for f in capture['frames']], [1, 2])
        wire = b''.join(bytes.fromhex(p['hex']) for p in capture['notifications'])
        self.assertEqual(wire, b'534 8 A5 5A FF 00 FE 40 08 11\r534 8 A5 5A FF 00 FE 40 08 12\rSTOPPED\r>')
        self.assertEqual(fixture['ownerMarks'][0]['source'], 'OWNER_REPORTED')
        self.assertFalse(capture['notificationLossIndependentlyDetectable'])
        self.assertFalse(capture['physicalAdapterSilentModeVerified'])

    def test_launcher_has_no_legacy_or_arbitrary_command_entry(self):
        source = (ROOT/'app/src/main/java/com/fiskerwarm/lab/MainActivity.java').read_text()
        self.assertNotIn('EditText', source)
        self.assertNotIn('Mode.IDENTITIES', source)
        self.assertNotIn('Mode.CAN_NETWORK_AUDIT', source)
        self.assertNotIn('Mode.GATT_MAP', source)
        self.assertIn('Mode.HVAC_534', source)
        self.assertIn('Mode.HVAC_373', source)
        self.assertIn('Intent.ACTION_CREATE_DOCUMENT', source)
        self.assertIn('new AtomicFile', source)


if __name__ == '__main__':
    unittest.main()
