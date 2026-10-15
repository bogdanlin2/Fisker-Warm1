"""Measured-log audit and deliberate counterexamples; no vehicle connection."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('audit_can_network', ROOT/'scripts/audit_can_network.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NetworkAuditLogChecks(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT/'verification/can-stage3-fixture.json').read_text())

    def result(self):
        return module.audit(self.data)

    def test_original_export_identity(self):
        self.assertEqual(self.data['source']['sha256'], '116dd5da095ca6e3e95e034c1af507bbfa1f991100e632019b8e45a34302ef99')

    def test_measured_table_has_exact_union(self):
        result = self.result()
        self.assertEqual(result['coveredIds'], 2047)
        self.assertEqual(result['missingIds'], ['0E9'])
        self.assertEqual(result['maxMultiplicity'], 1)

    def test_hand_calculated_ranges_and_cardinalities(self):
        expected = [('400','7FF',1024),('200','3FF',512),('100','1FF',256),
                    ('000','07F',128),('080','0BF',64),('0C0','0DF',32),
                    ('0F0','0FF',16),('0E0','0E7',8),('0EC','0EF',4),
                    ('0EA','0EB',2),('0E8','0E8',1)]
        self.assertEqual([(g['firstId'],g['lastId'],g['idCount']) for g in self.result()['groups']], expected)

    def test_real_command_sequence_and_late_reset(self):
        result = self.result()
        self.assertTrue(result['filterCommandSequenceMatches'])
        self.assertTrue(result['resetOnlyAtEnd'])
        self.assertFalse(result['protocolChangesDuringGroups'])

    def test_zero_windows_do_not_verify_hardware_inclusion(self):
        result = self.result()
        self.assertFalse(result['positiveFilteredTrafficObserved'])
        self.assertFalse(result['hardwareInclusionVerified'])
        self.assertFalse(result['passive29BitExecuted'])
        self.assertFalse(result['sampleCoverageIsSimultaneous'])

    def test_zero_windows_raw_bytes_are_exhaustive_stop_only(self):
        for group in self.data['networkAudit']['groups']:
            capture = group['capture']
            self.assertEqual(capture['receivedBytes'], 10)
            self.assertEqual(capture['rawAsciiPrefix'], 'STOPPED\r\r>')
            self.assertFalse(capture['rawTraceTruncated'])
            self.assertEqual(capture['invalidFrameLines'], 0)

    def test_reference_has_a_visible_malformed_tail(self):
        capture = self.data['networkAudit']['reference']
        self.assertEqual(capture['frameCount'], 41)
        self.assertEqual(capture['invalidFrameLines'], 1)
        self.assertEqual(capture['invalidLineSamples'], ['0E9 8 2D F0 FF F'])

    def test_host_windows_and_write_latency_are_not_can_timestamps(self):
        for group in self.result()['groups']:
            self.assertEqual(group['captureElapsedMillis'], 500)
            self.assertTrue(28 <= group['startWriteMillis'] <= 30)
            self.assertEqual(group['endReason'], 'WINDOW_ELAPSED')

    def test_missing_range_is_rejected(self):
        self.data['networkAudit']['groups'].pop()
        with self.assertRaises(ValueError): self.result()

    def test_overlapping_range_is_rejected(self):
        groups = self.data['networkAudit']['groups']
        groups[1] = copy.deepcopy(groups[0])
        with self.assertRaises(ValueError): self.result()

    def test_altered_mask_is_rejected(self):
        self.data['networkAudit']['groups'][0]['mask'] = '600'
        with self.assertRaises(ValueError): self.result()

    def test_noncanonical_pattern_is_rejected(self):
        self.data['networkAudit']['groups'][0]['filter'] = '401'
        with self.assertRaises(ValueError): self.result()

    def test_reset_between_ranges_is_rejected(self):
        self.data['events'].insert(20, {'command':'ATCRA','reply':'OK'})
        with self.assertRaises(ValueError): self.result()

    def test_swapped_filter_commands_are_detected(self):
        events = self.data['events']
        index = next(i for i,e in enumerate(events) if e['command'] == 'ATCM400')
        events[index],events[index+1] = events[index+1],events[index]
        with self.assertRaises(ValueError): self.result()

    def test_protocol_change_between_ranges_is_rejected(self):
        self.data['events'].insert(20, {'command':'ATSP7','reply':'OK'})
        with self.assertRaises(ValueError): self.result()

    def test_hidden_raw_frame_in_zero_window_is_rejected(self):
        self.data['networkAudit']['groups'][0]['capture']['rawAsciiPrefix'] = '400 1 A5\rSTOPPED\r\r>'
        with self.assertRaises(ValueError): self.result()


if __name__ == '__main__':
    unittest.main()
