#!/usr/bin/env python3
"""Bind the stage 7.1 key-path analysis to retained local binaries, offline.

Reads the patched Jadart output and Apktool output only. No network, OBD,
Bluetooth, seed/key calculation, vehicle-key reader, or command transmitter.
The ECU's access policy cannot be established by this client-side audit.
"""
import argparse
import json
from pathlib import Path
import re
import struct

from audit_pkc_stage7 import EXPECTED, PROTECTED, arm_bl, digest, require

FUNCTIONS = (0xa861f0, 0xa86f50, 0xa87670, 0xa862d8, 0xa8736c, 0xa87ab8)
CALLS = (
    (0xa85934, 0xa86f50, 'seed request -> OBD request wrapper'),
    (0xa85dc0, 0xa861f0, 'adapter literal -> CR-terminated transport helper'),
    (0xa85e34, 0xa87670, 'sendKey -> common OBD wrapper'),
    (0xa85ec8, 0xa87670, 'extended session -> common OBD wrapper'),
    (0xa88d24, 0xa87670, 'key routine -> common OBD wrapper'),
    (0xa862c0, 0xa862d8, 'CR-terminated helper -> async delegate'),
    (0xa87118, 0xa8736c, 'seed OBD wrapper -> async delegate'),
    (0xa87864, 0xa87ab8, 'common OBD wrapper -> async delegate'),
)
WORDS = (
    (0xa85660, 0xe2919008, 'response address is base ECU address + 8'),
    (0xa85c5c, 0xe3a0c002, 'security_level stored as tagged Smi 2 = integer 1'),
    (0xa85e5c, 0xe1510002, 'compare sendKey result status'),
    (0xa85e60, 0x1a0000bc, 'non-OK sendKey status exits this client path'),
    (0xa88ce0, 0xe30037aa, 'reader sets expected response address to 0x7AA'),
    (0xa88d6c, 0xe3a02004, 'reader slices response data starting at index 4'),
)


def image_word(binary, address):
    elf = struct.unpack_from('<16sHHIIIIIHHHHHH', binary)
    for index in range(elf[10]):
        kind, offset, va, _, size, *_ = struct.unpack_from(
            '<IIIIIIII', binary, elf[5] + index * elf[9])
        if kind == 1 and va <= address and address + 4 <= va + size:
            return binary[offset + address - va:offset + address - va + 4]
    raise ValueError('Address outside a file-backed ELF segment')


def method(text, declaration):
    start = text.index(declaration)
    end = text.index('.end method', start) + len('.end method')
    return text[start:end]


def audit(args):
    root = args.analysis_root
    binary = (root / 'native/libapp.so').read_bytes()
    require(digest(binary) == EXPECTED['libapp'], 'retained libapp SHA256')
    verification_path = root / 'evidence/verify.json'
    verification = json.loads(verification_path.read_text())
    require(verification['ok'] and not verification['failed'], 'Jadart verification')
    counts = {state: sum(g['status'] == state for g in verification['gates'])
              for state in ('PASS', 'FAIL', 'SKIP')}
    require(counts == {'PASS': 10, 'FAIL': 0, 'SKIP': 5}, 'parser verification profile')
    functions = []
    for address in FUNCTIONS:
        path = root / ('evidence/aot-function-%08x.txt' % address)
        data = path.read_bytes()
        rows = re.findall(r'^0x([0-9a-f]{8}): ([0-9a-f]{8}) ',
                          data.decode(), re.M)
        require(bool(rows), 'function has decoded instructions')
        require(int(rows[0][0], 16) == address, 'exact function start')
        for index, (location, word) in enumerate(rows):
            pc = int(location, 16)
            require(pc == address + index * 4, 'contiguous ARM32 dump')
            require(image_word(binary, pc) == bytes.fromhex(word), 'dump bytes vs ELF')
        functions.append({'address': hex(address), 'armInstructionsBound': len(rows),
                          'analysisSha256': digest(data)})
    edges = []
    for site, target, role in CALLS:
        word, actual = arm_bl(binary, site)
        require(actual == target, role)
        edges.append({'site': hex(site), 'word': hex(word), 'target': hex(target),
                      'roleFromStaticAnalysis': role})
    markers = []
    for site, expected, meaning in WORDS:
        actual = int.from_bytes(image_word(binary, site), 'little')
        require(actual == expected, meaning)
        markers.append({'site': hex(site), 'word': hex(actual), 'interpretation': meaning})
    smali = args.decoded_root / 'smali/com/chevalier/scusdk/sdk'
    interface = (smali / 'ScuSdk.smali').read_text()
    api = re.findall(r'^\.method public abstract (\S+)', interface, re.M)
    require(any(n.startswith('connectByVAKey(') for n in api), 'VA-key connection API')
    enrollment = [n for n in api if re.search(r'provision|enroll|exportKey|createKey|pair', n, re.I)]
    connection = method((smali / 'ScuSdkImpl.smali').read_text(),
                        '.method public final runTheConnection(Ljava/lang/String;Z)V')
    require('const/16 v3, 0x20' in connection and '->length()I' in connection,
            'connection requires 32-character input')
    require('->setVaKeyAsByteArray-2csIQuQ([B)V' in connection
            and '->hmacSha256-uo5YlkA([B[B)[B' in connection,
            'provided credential feeds HMAC and advertising identity')
    sender = method((smali / 'util/BleGattSendDataHandler.smali').read_text(),
                    '.method public final notify-hr4KVGQ(')
    require('->vaKeyAsByteArray:[B' in sender and '->hmacSha256-uo5YlkA([B[B)[B' in sender,
            'notification authentication uses the supplied VA credential')
    protected = {}
    for name, expected in PROTECTED.items():
        path = args.repo / 'app/src/main/java/com/fiskerwarm/lab/core' / name
        require(digest(path.read_bytes()) == expected, 'protected source ' + name)
        protected[name] = expected
    return {
        'schema': 'fisker-warm-pkc-key-path-static-audit/v1',
        'asOfUTC': '2026-10-19', 'result': 'PASS_STATIC_BINDINGS_ONLY',
        'libappSha256': EXPECTED['libapp'],
        'freshJadartVerification': counts,
        'jadartCommit': '9ccfecb2fac0937c5ba483da013dea6c71306064',
        'capstoneVersion': '5.0.9',
        'verificationSha256': digest(verification_path.read_bytes()),
        'newFunctionsReconstructed': functions, 'armDirectCallsBound': edges,
        'instructionMarkers': markers,
        'nativeApi': {'abstractMethodCount': len(api),
                      'namedEnrollmentExportMethodsFound': enrollment,
                      'connectionMethodSha256': digest(connection.encode()),
                      'notificationMethodSha256': digest(sender.encode()),
                      'scope': 'Reviewed public ScuSdk interface in retained 1.2.6 only; '
                               'not proof that all PKC provisioning mechanisms are absent.'},
        'protectedSourceSha256': protected,
        'decision': {
            'serverSignatureRequiredByObservedClientPath': True,
            'serverSignatureUniversallyRequiredByPkcFirmware': 'UNTESTED',
            'routine0110Option10SideEffectsVerified': False,
            'independentOwnerProvisioningConfirmed': False,
            'ownerCredentialAcquired': False,
            'newApkBuilt': False, 'vehicleCommandsSent': 0,
            'realClimateImplemented': False,
        },
        'limits': ['Static analysis of a retained mirror sample, not PKC firmware.',
                   'The fresh parser run retains five skipped checks.',
                   'Matching instructions do not establish vehicle-side routine permissions, '
                   'key lifetime, last-settings behavior, HVAC actuation, or timeout.'],
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--analysis-root', type=Path, required=True)
    p.add_argument('--decoded-root', type=Path, required=True)
    p.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    result = audit(args)
    args.output.parent.mkdir(exist_ok=True, parents=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'result': result['result'], 'newFunctions': len(FUNCTIONS),
                      'armInstructions': sum(f['armInstructionsBound'] for f in
                                             result['newFunctionsReconstructed']),
                      'directCalls': len(CALLS), 'markers': len(WORDS),
                      'vehicleCommandsSent': 0}))


if __name__ == '__main__':
    main()
