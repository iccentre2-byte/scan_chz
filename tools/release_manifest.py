"""Fail closed: verify signatures, IDs and version before publishing APKs."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

def checked(*args):
    return subprocess.check_output([str(x) for x in args], text=True, stderr=subprocess.STDOUT)

def main():
    dest, commit, sdk = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    expected = Path(__file__).with_name('signing-certificate-sha256.txt').read_text().strip()
    records = []
    for name, app_id in [('CHZ-1.3.2-safe.apk', 'com.tsd.czsetcollector.safe'),
                         ('CHZ-1.3.2-pilot-update.apk', 'com.tsd.czsetcollector.pilot')]:
        apk = dest / name
        certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})',
                           checked(sdk / 'apksigner', 'verify', '--verbose', '--print-certs', apk))
        if certs != [expected]:
            raise RuntimeError('Unexpected signer; refusing release')
        tree = checked(sdk / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
        package = re.search(r'^    A: package="([^"]+)"', tree, re.M)
        code = re.search(r'^    A: android:versionCode\([^)]*\)=\(type 0x10\)0x([a-fA-F0-9]+)', tree, re.M)
        version = re.search(r'^    A: android:versionName\([^)]*\)="([^"]+)"', tree, re.M)
        if not package or not code or not version or (package[1], int(code[1], 16), version[1]) != (app_id, 10, '1.3.2'):
            raise RuntimeError('Unexpected APK identity/version; refusing release')
        checked(sdk / 'zipalign', '-c', '4', apk)
        records.append(dict(file=name, applicationId=app_id, versionCode=10, versionName='1.3.2',
                            sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                            signerSha256=certs[0], signatureVerified=True))
    (dest / 'release-manifest.json').write_text(json.dumps(dict(format='cz-apk-release', version=1,
        commit=commit, apks=records), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (dest / 'SHA256SUMS.txt').write_text(''.join(f"{r['sha256']}  {r['file']}\n" for r in records), encoding='ascii')
    print('Verified two APKs: signatures, package IDs, version and alignment.')

if __name__ == '__main__':
    main()
