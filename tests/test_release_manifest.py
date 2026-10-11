import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1] / 'tools'
spec = importlib.util.spec_from_file_location('release_manifest', TOOLS / 'release_manifest.py')
release_manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_manifest)

class ReleaseManifestTests(unittest.TestCase):
    def execute(self, folder, signer=None, wrong_package=False, wrong_version=False):
        expected = (TOOLS / 'signing-certificate-sha256.txt').read_text().strip()
        def tool(*args):
            apk = str(args[-1])
            if 'verify' in args:
                return f'Signer #1 certificate SHA-256 digest: {signer or expected}\n'
            if 'xmltree' in args:
                app = 'com.tsd.czsetcollector.safe' if 'safe.apk' in str(args[-2]) else 'com.tsd.czsetcollector.pilot'
                if wrong_package: app = 'wrong.package'
                version = '1.3.0' if wrong_version else '1.3.2'
                return f'    A: package="{app}"\n    A: android:versionCode(0x0101021b)=(type 0x10)0xa\n    A: android:versionName(0x0101021c)="{version}"\n'
            return ''
        for name in ('CHZ-1.3.2-safe.apk', 'CHZ-1.3.2-pilot-update.apk'):
            (folder / name).write_bytes(b'test-fixture')
        with patch.object(sys, 'argv', ['release_manifest.py', str(folder), 'abc123', 'sdk']), patch.object(release_manifest, 'checked', side_effect=tool):
            release_manifest.main()

    def test_both_verified_apks_have_hashes_and_commit(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d); self.execute(folder)
            manifest = json.loads((folder / 'release-manifest.json').read_text())
            self.assertEqual(manifest['commit'], 'abc123')
            self.assertEqual(len(manifest['apks']), 2)
            self.assertTrue(all(x['signatureVerified'] and len(x['sha256']) == 64 for x in manifest['apks']))

    def test_wrong_signer_never_publishes_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            with self.assertRaises(RuntimeError): self.execute(folder, signer='0' * 64)
            self.assertFalse((folder / 'release-manifest.json').exists())

    def test_wrong_identity_or_version_never_publishes_manifest(self):
        for kwargs in ({'wrong_package': True}, {'wrong_version': True}):
            with self.subTest(kwargs=kwargs), tempfile.TemporaryDirectory() as d:
                folder = Path(d)
                with self.assertRaises(RuntimeError): self.execute(folder, **kwargs)
                self.assertFalse((folder / 'release-manifest.json').exists())
