"""Local TLS with certificate pinning. No public cloud, no remote signing endpoint."""
from __future__ import annotations
import hashlib
import ipaddress
import json
import os
import secrets
import ssl
from datetime import datetime, timedelta, timezone
from pathlib import Path


def atomic_file(path: Path, content: bytes):
    tmp = path.with_suffix(path.suffix+'.tmp')
    with open(tmp, 'wb') as f:
        f.write(content); f.flush(); os.fsync(f.fileno())
    os.replace(tmp, path)
    try: path.chmod(0o600)
    except OSError: pass


def setup(data_dir: Path) -> dict:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    data_dir.mkdir(parents=True, exist_ok=True)
    conf = data_dir/'connection.json'
    if conf.exists():
        settings=json.loads(conf.read_text('utf-8'))
    else:
        settings={'deviceKey':secrets.token_urlsafe(32), 'lanPort':18765, 'adminPort':18766}
        atomic_file(conf, json.dumps(settings).encode())
    cert_path=data_dir/'tls.crt'; key_path=data_dir/'tls.key'
    if not cert_path.exists() or not key_path.exists():
        key=rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'CHZ Local Set Collector')])
        now=datetime.now(timezone.utc)
        cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=1825))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'),
                  x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), critical=False)
            .sign(key, hashes.SHA256()))
        atomic_file(key_path, key.private_bytes(serialization.Encoding.PEM,
                        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        atomic_file(cert_path, cert.public_bytes(serialization.Encoding.PEM))
    cert=x509.load_pem_x509_certificate(cert_path.read_bytes())
    settings['certificateSha256']=cert.fingerprint(hashes.SHA256()).hex()
    ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version=ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert_path, key_path)
    settings['sslContext']=ctx
    return settings
