#!/usr/bin/env python3
"""Offline evidence for PKC credential names in the retained OLP sample.

Reads Apktool text only. Does not execute the SDK, recover a key, alter an APK,
connect to a server/vehicle, or infer firmware permissions from API names.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path


def block(text, prefix):
    match = re.search(r'^\.method[^\n]*' + re.escape(prefix) + r'[^\n]*\n', text, re.M)
    if not match:
        raise ValueError('Missing method: ' + prefix)
    end = text.index('.end method', match.end()) + len('.end method')
    return text[match.start():end], text[:match.start()].count('\n') + 1


def audit(root, prior):
    files = {
        'plugin': 'com/example/oceanlink_scusdk_android/OceanlinkScusdkAndroidPlugin.smali',
        'connection': 'com/example/oceanlink_scusdk_android/OceanlinkScusdkAndroidPlugin$performConnection$1.smali',
        'sdk': 'com/chevalier/scusdk/sdk/ScuSdkImpl.smali',
        'interface': 'com/chevalier/scusdk/sdk/ScuSdk.smali',
    }
    text = {name: (root / path).read_text() for name, path in files.items()}
    known = {item['smali_file']: item['file_sha256']
             for item in prior['nativeMethodsRebound']}
    hashes = {name: hashlib.sha256((root / path).read_bytes()).hexdigest()
              for name, path in files.items()}
    for name in ('plugin', 'sdk'):
        if hashes[name] != known[files[name]]:
            raise ValueError('Retained original-source binding mismatch: ' + name)

    p = text['plugin']
    connect = p[p.index('    :sswitch_8\n'):p.index('    :sswitch_9\n')]
    closure = text['connection']
    sdk_connect, sdk_line = block(text['sdk'], ' connectByVAKey(')
    run, run_line = block(text['sdk'], ' runTheConnection(')
    abstract = re.findall(r'^\.method public abstract ([^(]+)\(', text['interface'], re.M)
    checks = {
        'plugin_declares_bleKey_and_vin_argument_names':
            'const-string v8, "bleKey"' in p and 'const-string v13, "vin"' in p,
        'connect_reads_explicit_key_and_bleKey_arguments':
            'const-string v2, "key"' in connect and
            '{v0, v8}' in connect and 'move-object v2, v8' in connect,
        'both_argument_forms_use_same_connection_coroutine':
            connect.count('new-instance v0, Lcom/example/oceanlink_scusdk_android/'
                          'OceanlinkScusdkAndroidPlugin$performConnection$1;') == 2,
        'no_missing_credential_fallback_in_this_connect_branch':
            'Missing key or vin+bleKey' in connect,
        'coroutine_preserves_key_argument_and_calls_VA_API':
            'iput-object p2, p0,' in closure and '->m:Ljava/lang/String;' in closure and
            'iget-object v4, p0,' in closure and
            'ScuSdk;->connectByVAKey' in closure and '{p1, v4, v3, p0}' in closure,
        'VA_API_checks_32_characters_before_runTheConnection':
            'const/16 v0, 0x20' in sdk_connect and 'InvalidKey' in sdk_connect and
            sdk_connect.index('const/16 v0, 0x20') < sdk_connect.index('->runTheConnection'),
        'run_decodes_key_and_stores_same_bytes_as_VA_key':
            'const/16 v3, 0x20' in run and
            '->hexStringToUByteArray' in run and '->setVaKeyAsByteArray' in run and
            run.index('->hexStringToUByteArray') < run.index('->setVaKeyAsByteArray'),
        'key_is_set_before_old_service_choice_in_this_path':
            'ServiceType;->OLD_SERVICE' in run and
            run.index('->setVaKeyAsByteArray') < run.index('ServiceType;->OLD_SERVICE'),
        'interface_inventory_has_57_abstract_methods': len(abstract) == 57,
    }
    if not all(checks.values()):
        raise ValueError('Evidence mismatch: ' + ', '.join(k for k, v in checks.items() if not v))
    return {
        'schema': 'fisker-warm-pkc-authorization-static-evidence/v1',
        'source': 'Retained OceanLink Pro 1.2.6 / ScuSdk sample; see stage 7 APK provenance',
        'result': 'STATIC_CREDENTIAL_ALIAS_CONFIRMED_OWNER_NOT_PROVISIONED',
        'files': [{'path': path, 'sha256': hashes[name],
                   'boundToPriorOriginalApkAudit': path in known}
                  for name, path in files.items()],
        'checks': checks,
        'locations': {
            'pluginConnectStart': p[:p.index('    :sswitch_8\n')].count('\n') + 1,
            'sdkConnectByVAKeyStart': sdk_line,
            'sdkRunTheConnectionStart': run_line,
        },
        'conclusion': {
            'bleKeyAndVAKeyAreSameInputInExaminedClientPath': True,
            'examinedSdkEntryAcceptsOneKeyStringRatherThanSeparateVAObject': True,
            'allFirmwareModesRequireThisKey': None,
            'ownerEnrollmentExistsInFirmware': None,
            'credentialAcquired': False,
            'ownerAuthorizationEstablished': False,
            'climateOperationVerified': False,
        },
        'publicInterfaceMethods': abstract,
        'limits': [
            'Fixed-register text trace is valid only for this pinned sample; not a general Smali analyzer.',
            'No separate named owner enrollment API in this interface is not proof about generic diagnostics or firmware.',
            'Only SDK and plugin hashes were already bound in the prior APK audit; the coroutine/interface are retained companion texts.',
            'No server entitlement rules, firmware ACL, key lifetime or vehicle execution inferred.',
            'No credentials, backend secrets, MAC, VIN, account addresses or raw packets recorded.'
        ],
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--smali-root', required=True, type=Path)
    p.add_argument('--prior-audit', type=Path,
                   default=Path('verification/pkc-stage7-static-audit.json'))
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    result = audit(a.smali_root, json.loads(a.prior_audit.read_text()))
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(result['result'])


if __name__ == '__main__':
    main()
