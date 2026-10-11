"""Windows CAdES COM adapter. The private key and PIN never enter Python/Android."""
from __future__ import annotations
import base64
import json
import os
import re
import subprocess
from pathlib import Path
from core import ValidationError

SCRIPT = Path(__file__).with_name('signer.ps1')

def invoke(payload: dict) -> dict:
    if os.name != 'nt':
        raise ValidationError('УКЭП: нужен Windows с КриптоПро CSP и компонентом CAdESCOM (КриптоПро ЭЦП).')
    # Use the system executable, not a potentially shadowed program from PATH.
    ps = Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
    try:
        result = subprocess.run([str(ps), '-NoLogo', '-NoProfile', '-STA',
              '-ExecutionPolicy', 'Bypass', '-File', str(SCRIPT)],
              input=json.dumps(payload).encode('utf-8'), capture_output=True, timeout=180,
              creationflags=subprocess.CREATE_NO_WINDOW, check=False)
    except subprocess.TimeoutExpired as e:
        raise ValidationError('Истекло время ожидания подписи. Документ не отправлен. Проверьте окно КриптоПро/PIN.') from e
    try:
        response = json.loads(result.stdout.decode('utf-8-sig'))
    except (ValueError, UnicodeError) as e:
        raise ValidationError('Не удалось запустить CAdESCOM: '+result.stderr.decode('utf-8', errors='replace')[:1200]) from e
    if result.returncode or response.get('error'):
        raise ValidationError(response.get('error', 'Ошибка КриптоПро'))
    return response


def certificates() -> list[dict]:
    response = invoke({'action': 'certificates'})
    result = response.get('certificates', [])
    return result if isinstance(result, list) else [result]


class WindowsSigner:
    def __init__(self, thumbprint: str):
        self.thumbprint = re.sub(r'\s', '', str(thumbprint)).upper()
        if not re.fullmatch(r'[0-9A-F]{40}', self.thumbprint):
            raise ValidationError('Выберите сертификат УКЭП из списка на ПК')
    def sign(self, content: bytes, detached: bool) -> str:
        result = invoke({'action': 'sign', 'thumbprint': self.thumbprint,
                    'contentBase64': base64.b64encode(content).decode('ascii'),
                    'detached': bool(detached)})
        signature = result.get('signature')
        if not isinstance(signature, str) or not signature:
            raise ValidationError('КриптоПро не вернул подпись')
        return ''.join(signature.split())
