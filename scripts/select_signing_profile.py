#!/usr/bin/env python3
"""Select only an established signing identity, exposing public fingerprints only."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile

from release_profiles import PROFILES


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--keystore', default='build/local-lab-signing.p12')
    parser.add_argument('--password-file', default='build/.local-signing-password')
    args = parser.parse_args()
    env = os.environ.copy()
    env['FISKERWARM_SIGNING_PASSWORD'] = Path(args.password_file).read_text().strip()
    with tempfile.TemporaryDirectory() as tmp:
        certificate = Path(tmp) / 'certificate.der'
        result = subprocess.run([
            'keytool', '-exportcert', '-keystore', args.keystore, '-alias', 'lab',
            '-storepass:env', 'FISKERWARM_SIGNING_PASSWORD', '-file', str(certificate),
        ], env=env, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit('Could not open the signing key with alias lab; check the existing key and password')
        actual = hashlib.sha256(certificate.read_bytes()).hexdigest()
    matches = [(name, profile) for name, profile in PROFILES.items()
               if profile['certificateSha256'] == actual]
    if len(matches) != 1:
        raise SystemExit('Unrecognized signing certificate: ' + actual)
    name, profile = matches[0]
    print('Signing certificate SHA-256:', actual)
    print('Compatible applicationId:', profile['applicationId'])
    print('Build profile:', name)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            output.write('profile=' + name + '\n')
            output.write('apk_path=dist/' + profile['apkFilename'] + '\n')
            output.write('artifact_name=' + profile['artifactName'] + '\n')


if __name__ == '__main__':
    main()
