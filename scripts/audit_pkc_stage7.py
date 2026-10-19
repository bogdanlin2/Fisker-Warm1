#!/usr/bin/env python3
"""Read-only, offline binding of retained PKC research to the original APK.

No Bluetooth, OBD, network, key extraction, SDK execution, or vehicle sender.
Requires the owner's retained XAPK/evidence archives and Apktool 3.0.3 output.
The evidence output records static facts; it never certifies vehicle operation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

EXPECTED = {
    'xapk': 'b695a7d1db40164253b35c8cb0c8d53fc7a56703b74bca025fa6a36b419c28fa',
    'base': 'ae66488792217baffde9b8a5c2657d64f3935aaf58e185734e9dcccf83167af5',
    'libapp': '752f12b12ee9c6bcfd45f846c004b9979962194b6c6fbfb3ba1f600f7e022883',
    'native_archive': '235738b8e328078090ba2f863e1dd2138fbbf6178c7bcd771a5286242fc45c40',
    'key_archive': '400c0907cc8545b7e2e33a66e4edd171e96ff4a244c8a0ab00bec1b9f94b3ef6',
}
PROTECTED = {
    'ReadOnlyPolicy.java': '888c20d810f5076b2f9435ad31e768d47edeb3176b81a4108e691032617d3935',
    'HeatSafety.java': '83662b0bb388f9e4cc486978cfbd5d53fd0dbb73620cc183cb06b5178b35d1d1',
}
CALLS = (
    (0xa87fbc, 0xa88b98, 'UI callback -> key reader'),
    (0xa88bec, 0xa88ebc, 'key reader -> protected-access wrapper'),
    (0xa88ef8, 0xa854ec, 'wrapper -> generic protected diagnostic session'),
    (0xa85cd4, 0xa79c2c, 'protected session -> authenticated HTTP helper'),
    (0xa79d84, 0xa7ab80, 'HTTP helper -> account authorization headers'),
    (0xa88098, 0xa88218, 'successful read callback -> server record update'),
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(ok, label):
    if not ok:
        raise ValueError('Evidence mismatch: ' + label)


def arm_bl(binary, address):
    require(binary[:6] == b'\x7fELF\x01\x01', 'ELF32 little endian')
    elf = struct.unpack_from('<16sHHIIIIIHHHHHH', binary, 0)
    require(elf[2] == 40, 'ARM machine')
    for index in range(elf[10]):
        p = struct.unpack_from('<IIIIIIII', binary, elf[5] + index * elf[9])
        kind, offset, va, _, file_size, *_ = p
        if kind == 1 and va <= address and address + 4 <= va + file_size:
            word = struct.unpack_from('<I', binary, offset + address - va)[0]
            require(word >> 24 == 0xeb, 'unconditional ARM BL')
            imm = word & 0xffffff
            if imm & 0x800000:
                imm -= 0x1000000
            return word, address + 8 + (imm << 2)
    raise ValueError('Address outside file-backed PT_LOAD')


def audit(args):
    inputs = {k: getattr(args, k).read_bytes()
              for k in ('xapk', 'native_archive', 'key_archive')}
    for name, data in inputs.items():
        require(digest(data) == EXPECTED[name], name + ' SHA256')
    with zipfile.ZipFile(args.xapk) as x:
        base = x.read('com.undercurrent.oceanlinkpro.apk')
        split = x.read('config.armeabi_v7a.apk')
    import io
    with zipfile.ZipFile(io.BytesIO(split)) as z:
        libapp = z.read('lib/armeabi-v7a/libapp.so')
    for name, data in (('base', base), ('libapp', libapp)):
        require(digest(data) == EXPECTED[name], name + ' SHA256')
    with zipfile.ZipFile(args.native_archive) as z:
        methods = json.loads(z.read('evidence/method-evidence.json'))
    checked = []
    for record in methods:
        data = (args.decoded_root / 'smali' / record['smali_file']).read_bytes()
        lines = data.decode('utf-8').splitlines(keepends=True)
        method = ''.join(lines[record['start_line'] - 1:record['end_line']]).encode()
        require(digest(data) == record['file_sha256'], record['smali_file'])
        require(digest(method) == record['method_sha256'], record['method'])
        checked.append({k: record[k] for k in ('class', 'method', 'smali_file',
                        'start_line', 'end_line', 'file_sha256', 'method_sha256')})
    manifest_checks = []
    with zipfile.ZipFile(args.key_archive) as z:
        for item in json.loads(z.read('SHA256SUMS.json')):
            data = z.read(item['path'])
            require(len(data) == item['bytes'] and digest(data) == item['sha256'],
                    'retained archive member ' + item['path'])
            manifest_checks.append(item['path'])
    edges = []
    for address, target, role in CALLS:
        word, actual = arm_bl(libapp, address)
        require(actual == target, role)
        edges.append({'address': hex(address), 'instruction': hex(word),
                      'target': hex(target), 'roleFromRetainedAnalysis': role})
    root = args.decoded_root / 'smali'
    receiver_path = 'com/chevalier/scusdk/sdk/util/BleGattReceiveDataHandler.smali'
    receiver = (root / receiver_path).read_text()
    fan_block = receiver[receiver.index('    :goto_5'):receiver.index('    :pswitch_b')]
    require('FanSpeedStateEnum' in fan_block and '->_ClimateControlState' in fan_block,
            'climate state derived from fan enum')
    require('->On:' in fan_block and '->Off:' in fan_block, 'derived boolean branches')
    mapping = root / 'com/chevalier/scusdk/sdk/util/BleGattReceiveDataHandler$WhenMappings.smali'
    mapping_text = mapping.read_text()
    require(all('->FanSpeed%d:' % n in mapping_text for n in range(1, 8)), 'fan mapping 1..7')
    getter_path = 'com/example/oceanlink_scusdk_android/OceanlinkScusdkAndroidPlugin$getClimateState$1.smali'
    getter = (root / getter_path).read_text()
    require('StateFlow;->getValue()' in getter and 'System;->currentTimeMillis()' in getter,
            'cache read with local current timestamp')
    hashes = {}
    for name, expected in PROTECTED.items():
        data = (args.repo / 'app/src/main/java/com/fiskerwarm/lab/core' / name).read_bytes()
        require(digest(data) == expected, 'protected source ' + name)
        hashes[name] = expected
    return {
        'schema': 'fisker-warm-pkc-stage7-static-audit/v1',
        'asOfLocalDate': '2026-10-20', 'timezone': 'Europe/Kyiv',
        'result': 'PASS_STATIC_BINDINGS_ONLY',
        'sourceSha256': EXPECTED, 'nativeMethodsRebound': checked,
        'retainedKeyArchiveMembersVerified': len(manifest_checks),
        'armDirectCallsRebound': edges,
        'feedbackFinding': {
            'receiverPath': receiver_path, 'receiverSha256': digest((root / receiver_path).read_bytes()),
            'enumMapSha256': digest(mapping.read_bytes()),
            'getterPath': getter_path,
            'climateStateDerivedFromFanSpeed': True,
            'getterTimestampIsLocalCacheReadTime': True,
            'independentHvacActuationProof': False,
        },
        'protectedSourceSha256': hashes,
        'limits': ['Retained AOT interpretation is rebound by original ELF branch instructions; '
                   'the patched full Dart snapshot parser was not rerun.',
                   'No ECU firmware, owner VA-key, live BLE, or vehicle measurements.',
                   'APK signature provenance remains the retained mirror sample, not a new official reference.'],
        'decision': {
            'realClimateImplemented': False, 'vehicleCommandsSent': 0,
            'independentOwnerCredentialAcquired': False,
            'independentOwnerProvisioningConfirmed': False,
            'pkc21_3VehicleStartVerified': False,
            'lastSettingsVerified': False, 'vehicleTimeoutVerified': False,
            'activeVehicleTestReady': False, 'newApkJustified': False,
        },
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('xapk', 'native-archive', 'key-archive', 'decoded-root', 'repo', 'output'):
        p.add_argument('--' + name, required=True, type=Path)
    args = p.parse_args()
    report = audit(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'result': report['result'], 'nativeMethods': len(report['nativeMethodsRebound']),
                      'archiveMembers': report['retainedKeyArchiveMembersVerified'],
                      'armCalls': len(report['armDirectCallsRebound']),
                      'vehicleCommandsSent': 0, 'realClimateImplemented': False}))


if __name__ == '__main__':
    main()
