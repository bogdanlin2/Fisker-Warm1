#!/usr/bin/env python3
"""Offline audit of an exported CAN_NETWORK_AUDIT log; never opens a transport.

The acceptance oracle compares individual bits, independently of the Android
plan generator and Group.accepts(). An empty capture never verifies inclusion
by an adapter filter. This distinction is preserved in the output.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path


def accepts(identifier, mask, pattern):
    return all(not (mask >> bit) & 1 or
               ((identifier >> bit) & 1) == ((pattern >> bit) & 1)
               for bit in range(11))


def audit(data):
    network = data['networkAudit']
    groups = network['groups']
    if network['requestedIdBits'] != 11 or len(groups) != 11:
        raise ValueError('Expected eleven 11-bit receive groups')
    excluded = int(network['observedFloodId'], 16)
    if excluded != 0x0E9:
        raise ValueError('This audit is pinned to the observed excluded ID')
    hits = [0] * 2048
    ranges = []
    filter_commands = ['ATCM000', 'ATCF000']
    positive_traffic = False
    for group in groups:
        encoded = (group['mask'], group['filter'])
        if not all(re.fullmatch(r'[0-7][0-9A-F]{2}', value) for value in encoded):
            raise ValueError('Noncanonical 11-bit mask/filter')
        mask, pattern = map(lambda value: int(value, 16), encoded)
        if pattern & ~mask:
            raise ValueError('Pattern must be canonical outside the mask')
        identifiers = [identifier for identifier in range(2048)
                       if accepts(identifier, mask, pattern)]
        for identifier in identifiers:
            hits[identifier] += 1
        capture = group['capture']
        raw = capture.get('rawAsciiPrefix', '')
        # An untruncated all-zero group must not conceal bytes from the parser.
        if capture['frameCount'] == 0 and not capture.get('rawTraceTruncated'):
            if raw != 'STOPPED\r\r>' or len(raw.encode('ascii')) != capture['receivedBytes']:
                raise ValueError('Zero-frame capture differs from the measured raw stop-only trace')
        for item in capture['ids']:
            if not accepts(int(item['id'], 16), mask, pattern):
                raise ValueError('An observed ID is outside its requested filter')
        positive_traffic |= capture['frameCount'] > 0
        ranges.append({'mask': encoded[0], 'filter': encoded[1],
                       'firstId': f'{min(identifiers):03X}',
                       'lastId': f'{max(identifiers):03X}', 'idCount': len(identifiers),
                       'frameCount': capture['frameCount'],
                       'captureElapsedMillis': capture['captureElapsedMillis'],
                       'startWriteMillis': capture['startWriteMillis'],
                       'endReason': capture['endReason']})
        filter_commands.extend(['ATCM' + encoded[0], 'ATCF' + encoded[1]])
    if any(count != (0 if identifier == excluded else 1)
           for identifier, count in enumerate(hits)):
        raise ValueError('Groups must cover every other ID exactly once')
    filter_commands.append('ATCRA')
    events = data['events']
    commands = [event['command'] for event in events]
    actual_filters = [command for command in commands
                      if re.fullmatch(r'ATC[MF][0-9A-F]{3}', command)
                      or command.startswith('ATCRA')]
    if actual_filters != filter_commands:
        raise ValueError('Filter/reset command sequence does not match the measured plan')
    if commands.count('ATZ') != 1 or commands[0] != 'ATZ':
        raise ValueError('A single initial adapter reset is required')
    if [command for command in commands if command.startswith('ATSP')] != ['ATSP6']:
        raise ValueError('Unexpected protocol change between receive groups')
    for required in ['ATCAF0', 'ATCFC0', 'ATCSM1', 'ATD1', 'ATDPN']:
        if required not in commands or commands.index(required) > commands.index('ATCM000'):
            raise ValueError('Passive startup must precede the first receive filter')
    if any(not command.startswith('AT') for command in commands):
        raise ValueError('The export contains a non-adapter command')
    return {'coveredIds': sum(count > 0 for count in hits),
            'missingIds': [f'{identifier:03X}' for identifier, count in enumerate(hits) if not count],
            'maxMultiplicity': max(hits), 'groups': ranges,
            'filterCommandSequenceMatches': True,
            'resetOnlyAtEnd': actual_filters[-1] == 'ATCRA',
            'protocolChangesDuringGroups': False,
            'positiveFilteredTrafficObserved': positive_traffic,
            'hardwareInclusionVerified': False,
            'hardwareInclusionMeaning': 'Offline math and host replay cannot verify real adapter acceptance',
            'sampleCoverageIsSimultaneous': False,
            'passive29BitExecuted': False,
            'hvacSignalsConfirmed': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', type=Path)
    arguments = parser.parse_args()
    raw = arguments.input.read_bytes()
    result = audit(json.loads(raw))
    result['inputSha256'] = hashlib.sha256(raw).hexdigest()
    text = json.dumps(result, indent=2, ensure_ascii=False) + '\n'
    if arguments.output:
        arguments.output.write_text(text, encoding='utf-8')
    else:
        print(text, end='')


if __name__ == '__main__':
    main()
