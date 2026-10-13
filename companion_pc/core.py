"""Set formation for grocery goods. No signing or network action on import/upload.
Contract: True API §§1.5, 4.1.1, 4.1.2.4; read-only doc info v4.
"""
from __future__ import annotations
import base64
import collections
from contextlib import contextmanager, closing
import hashlib
import json
import re
import sqlite3
import threading
import time
import uuid
from pathlib import Path
from typing import Any
from urllib import request, error, parse

VERSION = '1.3.4'
HOSTS = {'production': 'https://markirovka.crpt.ru',
         'sandbox': 'https://markirovka.sandbox.crptech.ru'}
MAX_BYTES = 2_000_000
MAX_SELECTION = 500
STATES = {'WAITING_SIGNATURE','SUBMITTING','SUBMITTED','PROCESSING',
          'VERIFIED','REJECTED','UNKNOWN','BLOCKED','CHECK_REQUIRED','CANCELLED_LOCAL'}
STATUS_RU = {'WAITING_SIGNATURE':'Ожидает подписи на ПК', 'SUBMITTING':'Отправляется',
             'SUBMITTED':'Передан в ЧЗ — результат ещё не проверен', 'PROCESSING':'Последняя проверка: ЧЗ обрабатывает',
             'VERIFIED':'Сформирован: состав проверен', 'REJECTED':'Отклонён ЧЗ',
             'UNKNOWN':'Результат отправки неизвестен — НЕ повторять',
             'BLOCKED':'Не отправлен: требуется исправление',
             'CHECK_REQUIRED':'Требуется проверка результата',
             'CANCELLED_LOCAL':'Отменён на ПК до отправки в ЧЗ'}

class ValidationError(ValueError): pass
class ApiError(RuntimeError):
    def __init__(self, message: str, http_status: int | None = None):
        super().__init__(message)
        self.http_status = http_status


def dumps(x: Any) -> str:
    return json.dumps(x, ensure_ascii=False, separators=(',', ':'), sort_keys=True)


def valid_gtin(s: str) -> bool:
    return (isinstance(s, str) and bool(re.fullmatch(r'[0-9]{14}', s)) and
            sum(int(c) * (3 if i % 2 == 0 else 1) for i,c in enumerate(s[:-1])) % 10
            == (-int(s[-1])) % 10)


def valid_inn(s: str) -> bool:
    if not isinstance(s,str) or not re.fullmatch(r'[0-9]{10}|[0-9]{12}', s): return False
    d=list(map(int,s))
    def check(weights): return sum(a*b for a,b in zip(d,weights))%11%10
    return (check([2,4,10,3,5,9,4,6,8]) == d[9] if len(d)==10 else
            check([7,2,4,10,3,5,9,4,6,8])==d[10] and
            check([3,7,2,4,10,3,5,9,4,6,8])==d[11])


def normalize_code(raw: str) -> str:
    """Grocery only: GS1 AI01 + 14-digit GTIN + AI21 + 13-char serial.
    Do NOT search for '91' inside serials: it is legitimate serial content.
    A full scan without GS is accepted only if suffix starts at known boundary.
    """
    if not isinstance(raw,str) or len(raw)>512: raise ValidationError('Неверный формат кода')
    code=raw.strip(' \r\n\t')
    for prefix in (']d2',']Q3',']C1',']e0'):
        if code.startswith(prefix): code=code[3:]; break
    if code.startswith('(01)'):
        # Only an explicitly delimited human-readable representation.
        m=re.fullmatch(r'\(01\)([0-9]{14})\(21\)(.{13})(?:\((?:91|93)\).*)?',code)
        if not m: raise ValidationError('Неоднозначный код: используйте сканер с GS/FNC1')
        code='01'+m[1]+'21'+m[2]
    head=code.split('\x1d',1)[0]
    if len(head)>31:
        if head[31:33] not in ('91','93'):
            raise ValidationError('Нет разделителя GS после серийного номера')
        head=head[:31]
    if not re.fullmatch(r'01[0-9]{14}21[!-~]{13}',head):
        raise ValidationError('Нужен DataMatrix бакалеи: 01 + GTIN + 21 + 13 символов. SSCC не подходит')
    if not valid_gtin(head[2:16]): raise ValidationError('Ошибочная контрольная цифра GTIN')
    return head


def validate_job(source: Any) -> dict:
    if not isinstance(source,dict) or type(source.get('schemaVersion')) is not int or source.get('schemaVersion')!=1:
        raise ValidationError('Неподдерживаемый формат очереди')
    try: ident=str(uuid.UUID(str(source['id'])))
    except (KeyError,ValueError,TypeError): raise ValidationError('Неверный id задания')
    env=source.get('environment')
    if env not in HOSTS: raise ValidationError('Выберите production или sandbox')
    inn=source.get('inn')
    if not valid_inn(inn): raise ValidationError('Проверьте ИНН и контрольные цифры')
    if source.get('pg')!='grocery':
        raise ValidationError('В этой версии поддерживается только grocery: сухарики и майонезы')
    template=source.get('template') or {}
    if not isinstance(template,dict): raise ValidationError('Неверный шаблон набора')
    gtin=template.get('setGtin')
    expected=template.get('expected')
    if not valid_gtin(gtin) or not isinstance(expected,dict) or not expected:
        raise ValidationError('Заполните GTIN набора и состав из Национального каталога')
    if len(expected)>100 or any(not valid_gtin(k) or type(v)!=int or not 1<=v<=1000
                               for k,v in expected.items()):
        raise ValidationError('Ошибочный состав шаблона')
    if sum(expected.values())>1000: raise ValidationError('Не более 1000 пачек в наборе')
    parent=normalize_code(source.get('setCode'))
    if parent[2:16]!=gtin: raise ValidationError('GTIN отсканированного набора не соответствует шаблону')
    raw=source.get('children')
    if not isinstance(raw,list) or not 1<=len(raw)<=1000: raise ValidationError('Неверный список вложений')
    children=[normalize_code(x) for x in raw]
    if len(set(children))!=len(children) or parent in children:
        raise ValidationError('Повтор кода в наборе')
    if dict(collections.Counter(x[2:16] for x in children))!=expected:
        raise ValidationError('Набор неполный или состав GTIN не совпадает с шаблоном')
    return dict(schemaVersion=1,id=ident,environment=env,inn=inn,pg='grocery',
                template=dict(setGtin=gtin,expected=expected),setCode=parent,children=children)


def product_bytes(job: dict) -> bytes:
    return batch_product_bytes([job])

def validate_batch(jobs: list[dict]) -> list[dict]:
    if not isinstance(jobs,list) or not 1<=len(jobs)<=MAX_SELECTION:
        raise ValidationError(f'Выберите от 1 до {MAX_SELECTION} наборов')
    clean=[validate_job(j) for j in jobs]
    if len({(j['inn'],j['environment'],j['pg'],len(j['setCode'])) for j in clean})!=1:
        raise ValidationError('Один документ: один ИНН, контур и товарная группа, совместимые коды наборов')
    if len({j['id'] for j in clean})!=len(clean): raise ValidationError('Повтор задания в документе')
    seen=set()
    for j in clean:
        codes=[j['setCode']]+j['children']
        if seen.intersection(codes): raise ValidationError('Один код использован в нескольких наборах документа')
        seen.update(codes)
    return clean

def batch_product_bytes(jobs: list[dict]) -> bytes:
    clean=validate_batch(jobs)
    content=dumps({'participantId':clean[0]['inn'],'aggregationUnits':[
        {'unitSerialNumber':j['setCode'],'sntins':j['children']} for j in clean]}).encode('utf-8')
    if len(content)>MAX_BYTES: raise ValidationError('Документ слишком большой; выберите меньше наборов')
    return content


def make_envelope(content: bytes, signature: str) -> dict:
    if not isinstance(signature,str): raise ValidationError('Подпись должна быть строкой Base64')
    signature=''.join(signature.split())
    try:
        if len(base64.b64decode(signature, validate=True))<32: raise ValueError()
    except (ValueError,TypeError): raise ValidationError('Пустая или неверная подпись УКЭП')
    return dict(document_format='MANUAL',type='SETS_AGGREGATION',
                product_document=base64.b64encode(content).decode('ascii'),signature=signature)


class Store:
    """Durable idempotent inbox. A scan/upload never signs or submits anything."""
    def __init__(self, path: Path):
        self.path=Path(path); self.path.parent.mkdir(parents=True,exist_ok=True)
        self.lock=threading.RLock()
        with self.connect() as c:
            c.executescript('''
            CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, payload TEXT NOT NULL,
              digest TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '',
              document_id TEXT, updated REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS reservations(environment TEXT NOT NULL, code TEXT NOT NULL,
              job_id TEXT NOT NULL, PRIMARY KEY(environment,code));
            CREATE TABLE IF NOT EXISTS events(n INTEGER PRIMARY KEY AUTOINCREMENT,
              job_id TEXT, time REAL, state TEXT, detail TEXT);
            ''')
            # A crash during POST is never permission to repeat it.
            c.execute("UPDATE jobs SET state='UNKNOWN',detail='ПК был закрыт во время отправки. Сначала проверка ЧЗ.' WHERE state='SUBMITTING'")
    @contextmanager
    def connect(self):
        # Connection.__exit__ commits/rolls back, but does NOT close the handle.
        # Explicit close is required on Windows too (backups and clean shutdown).
        with closing(sqlite3.connect(self.path,timeout=15)) as c:
            c.row_factory=sqlite3.Row
            c.execute('PRAGMA journal_mode=WAL'); c.execute('PRAGMA synchronous=FULL')
            with c:
                yield c
    def enqueue(self, source: dict) -> dict:
        j=validate_job(source); payload=dumps(j); digest=hashlib.sha256(payload.encode()).hexdigest()
        with self.lock, self.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            old=c.execute('SELECT * FROM jobs WHERE id=?',(j['id'],)).fetchone()
            if old:
                if old['digest']!=digest: raise ValidationError('Тот же id, но другой состав. Изменение отклонено')
                return self._view(old)
            codes=[j['setCode']]+j['children']
            for code in codes:
                other=c.execute('SELECT job_id FROM reservations WHERE environment=? AND code=?',
                                (j['environment'],code)).fetchone()
                if other: raise ValidationError('Код уже зарезервирован заданием '+other[0])
            c.execute('INSERT INTO jobs VALUES(?,?,?,?,?,?,?)',
                      (j['id'],payload,digest,'WAITING_SIGNATURE','Только очередь ПК. В ЧЗ ещё НЕ отправлено.',None,time.time()))
            c.executemany('INSERT INTO reservations VALUES(?,?,?)',
                          [(j['environment'],code,j['id']) for code in codes])
            c.execute('INSERT INTO events(job_id,time,state,detail) VALUES(?,?,?,?)',
                      (j['id'],time.time(),'WAITING_SIGNATURE','Набор принят на ПК'))
        return self.get(j['id'])
    @staticmethod
    def _view(row):
        p=json.loads(row['payload'])
        return {**p,'status':row['state'],'detail':row['detail'],
                'documentId':row['document_id'],'payloadHash':row['digest'],'updated':row['updated']}
    def get(self, ident: str) -> dict:
        with self.connect() as c: row=c.execute('SELECT * FROM jobs WHERE id=?',(ident,)).fetchone()
        if not row: raise KeyError('Задание не найдено')
        return self._view(row)
    def get_many(self, idents) -> list[dict]:
        if not idents or len(idents)>MAX_SELECTION: raise ValidationError('Неверный размер выбора')
        with self.connect() as c:
            rows={r['id']:r for r in c.execute('SELECT * FROM jobs WHERE id IN ('+','.join('?' for _ in idents)+')',idents)}
        if any(i not in rows for i in idents): raise KeyError('Задание не найдено')
        return [self._view(rows[i]) for i in idents]
    def all(self) -> list[dict]:
        with self.connect() as c:
            return [self._view(r) for r in c.execute('SELECT * FROM jobs ORDER BY updated DESC')]
    def change(self, ident, state, detail='', document_id=None, allowed=None):
        if state not in STATES: raise ValidationError('Неверное состояние')
        with self.lock,self.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            row=c.execute('SELECT * FROM jobs WHERE id=?',(ident,)).fetchone()
            if not row: raise KeyError(ident)
            if allowed is not None and row['state'] not in allowed:
                raise ValidationError('Действие запрещено в состоянии '+row['state'])
            doc=document_id or row['document_id']
            c.execute('UPDATE jobs SET state=?,detail=?,document_id=?,updated=? WHERE id=?',
                      (state,str(detail)[:16000],doc,time.time(),ident))
            c.execute('INSERT INTO events(job_id,time,state,detail) VALUES(?,?,?,?)',
                      (ident,time.time(),state,str(detail)[:16000]))
        return self.get(ident)
    def cancel_unsent(self, ident: str, expected_hash: str):
        with self.lock,self.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            row=c.execute('SELECT * FROM jobs WHERE id=?',(ident,)).fetchone()
            if not row or row['digest']!=expected_hash: raise ValidationError('Задание изменилось или не найдено')
            if row['state'] not in {'WAITING_SIGNATURE','BLOCKED'} or row['document_id']:
                raise ValidationError('Можно отменить только набор, который ещё не отправлялся в ЧЗ')
            c.execute("UPDATE jobs SET state='CANCELLED_LOCAL',detail='Отменён пользователем на ПК. В ЧЗ не отправлялся.',updated=? WHERE id=?",(time.time(),ident))
            c.execute('DELETE FROM reservations WHERE job_id=?',(ident,))
            c.execute('INSERT INTO events(job_id,time,state,detail) VALUES(?,?,?,?)',(ident,time.time(),'CANCELLED_LOCAL','Локальная отмена до отправки'))
        return self.get(ident)
    def change_many(self, jobs, state, detail='', document_id=None, allowed=None):
        """Commit every batch member before POST, or change none of them."""
        if state not in STATES or not jobs: raise ValidationError('Неверное состояние или пустой документ')
        if len({j['id'] for j in jobs})!=len(jobs): raise ValidationError('Повтор задания')
        with self.lock,self.connect() as c:
            c.execute('BEGIN IMMEDIATE')
            rows=[]
            for j in jobs:
                row=c.execute('SELECT * FROM jobs WHERE id=?',(j['id'],)).fetchone()
                if not row or row['digest']!=j['payloadHash']: raise ValidationError('Состав изменился; документ не отправлен')
                if allowed is not None and row['state'] not in allowed:
                    raise ValidationError('Действие запрещено в состоянии '+row['state'])
                rows.append(row)
            now=time.time();message=str(detail)[:16000]
            for row in rows:
                doc=document_id or row['document_id']
                c.execute('UPDATE jobs SET state=?,detail=?,document_id=?,updated=? WHERE id=?',
                    (state,message,doc,now,row['id']))
                c.execute('INSERT INTO events(job_id,time,state,detail) VALUES(?,?,?,?)',
                    (row['id'],now,state,message))
        return self.get_many([j['id'] for j in jobs])
    def backup(self, destination: Path):
        with self.lock,self.connect() as src,closing(sqlite3.connect(destination)) as dest:
            src.backup(dest)


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ApiError('Перенаправление API отклонено. Токен не передан другому адресу.', code)


class TrueApi:
    def __init__(self, environment: str, token: str=''):
        self.host=HOSTS[environment]; self.token=token
        self.opener=request.build_opener(NoRedirect())
    def call(self, method, path, payload=None, version=3, auth=True):
        headers={'Accept':'application/json, text/plain, application/octet-stream','Content-Type':'application/json; charset=utf-8'}
        if auth:
            if not self.token: raise ApiError('Не выполнен вход по УКЭП на ПК')
            headers['Authorization']='Bearer '+self.token
        req=request.Request(self.host+f'/api/v{version}/true-api'+path,
                            data=None if payload is None else dumps(payload).encode(),
                            headers=headers,method=method)
        try:
            with self.opener.open(req,timeout=45) as resp:
                content=resp.read(8_000_001)
                if len(content)>8_000_000: raise ApiError('Слишком большой ответ API')
                text=content.decode('utf-8-sig')
                try: return json.loads(text)
                except json.JSONDecodeError:
                    try: return str(uuid.UUID(text.strip()))
                    except ValueError: raise ApiError('API вернул не JSON: '+text[:500])
        except error.HTTPError as e:
            detail=e.read(8000).decode('utf-8',errors='replace')
            raise ApiError(f'HTTP {e.code}: {detail}',e.code) from e
        except (error.URLError,TimeoutError,OSError) as e:
            raise ApiError('Сетевая ошибка: '+str(e)) from e
    def authenticate(self, signer, inn):
        challenge=self.call('GET','/auth/key',auth=False)
        if not isinstance(challenge,dict) or not isinstance(challenge.get('data'),str):
            raise ApiError('Неожиданный ответ /auth/key')
        # Sign UTF-8 bytes of the data string, with ATTACHED signature for login.
        signed=signer.sign(challenge['data'].encode('utf-8'),detached=False)
        result=self.call('POST','/auth/simpleSignIn',dict(uuid=challenge['uuid'],data=signed,
                         inn=inn,unitedToken=True),auth=False)
        token=(result.get('token') or result.get('uuidToken')) if isinstance(result,dict) else None
        if not isinstance(token,str) or not token or '\r' in token or '\n' in token:
            raise ApiError('Не получен токен True API. Проверьте регистрацию, полномочия сертификата и выбранный ИНН.')
        self.token=token
    def codes(self, codes: list[str], pg='grocery') -> dict[str,dict]:
        result={}
        for start in range(0,len(codes),100):
            raw=self.call('POST','/cises/info?'+parse.urlencode({'pg':pg}),codes[start:start+100])
            if not isinstance(raw,list): raise ApiError('Неожиданный формат /cises/info. Проверка остановлена')
            for item in raw:
                if not isinstance(item,dict): raise ApiError('Неверный элемент /cises/info')
                info=item.get('cisInfo')
                if not isinstance(info,dict):
                    raise ApiError('Код недоступен: '+str(item)[:1500])
                cis=info.get('cis') or item.get('requestedCis')
                if not isinstance(cis,str): raise ApiError('В ответе нет идентификатора кода')
                result[normalize_code(cis)]=info
        missing=set(codes)-result.keys()
        if missing: raise ApiError(f'Не получены сведения о {len(missing)} кодах. Отправки нет')
        return result
    def document_info(self, ident: str, pg='grocery'):
        ident=str(uuid.UUID(ident))
        return self.call('GET',f'/doc/{ident}/info?'+parse.urlencode({'pg':pg}),version=4)


def child_codes(info: dict) -> set[str] | None:
    raw=info.get('child')
    if raw is None: raw=info.get('children')
    if raw is None: return None
    if not isinstance(raw,list): raise ApiError('Неожиданный формат вложений в ответе ЧЗ')
    result=[]
    for x in raw:
        code=x if isinstance(x,str) else (x.get('cis') if isinstance(x,dict) else None)
        if not isinstance(code,str): raise ApiError('Неожиданный формат кода вложения')
        result.append(normalize_code(code))
    if len(result)!=len(set(result)): raise ApiError('Повтор вложений в ответе ЧЗ')
    return set(result)


def preflight(job: dict, info: dict[str,dict]):
    """OSU deliberately does NOT reject children's ownerInn mismatch.
    True API still decides whether OSU exception applies to the actual codes.
    This pilot handles purchased, already introduced grocery units only.
    """
    j=validate_job(job); parent=info[j['setCode']]
    if parent.get('ownerInn')!=j['inn']: raise ValidationError('Код НАБОРА не принадлежит вашему ИНН')
    if parent.get('packageType')!='SET' and parent.get('packType')!='SET':
        raise ValidationError('Код верхнего уровня не является набором SET')
    if parent.get('status')!='APPLIED': raise ValidationError('Код набора должен быть «Нанесён» (APPLIED)')
    if parent.get('emissionType')!='LOCAL': raise ValidationError('Для этого сценария тип эмиссии набора должен быть LOCAL')
    if parent.get('parent') or parent.get('parentCis'): raise ValidationError('Набор уже вложен в агрегат')
    if child_codes(parent): raise ValidationError('Набор уже содержит вложения')
    if parent.get('statusEx') not in (None,'','EMPTY','WAIT_TRANSFER_TO_OWNER'):
        raise ValidationError('Особое состояние кода набора: '+str(parent.get('statusEx')))
    for code in j['children']:
        i=info[code]
        if i.get('status')!='INTRODUCED': raise ValidationError('Вложение должно быть «В обороте»: '+code[-6:])
        if i.get('statusEx') not in (None,'','EMPTY','WAIT_TRANSFER_TO_OWNER'):
            raise ValidationError('Проверьте особое состояние пачки: '+str(i.get('statusEx')))
        if i.get('packageType')!='UNIT' and i.get('packType')!='UNIT':
            raise ValidationError('В набор бакалеи допускаются только единицы товара UNIT')
        if i.get('parent') or i.get('parentCis'):
            raise ValidationError('Пачка уже вложена в другой агрегат: '+code[-6:])


def verify_set(job: dict, info: dict[str,dict]) -> None:
    j=validate_job(job); i=info[j['setCode']]
    if i.get('status')!='INTRODUCED' or i.get('ownerInn')!=j['inn']:
        raise ValidationError('Не подтверждены статус/владелец сформированного набора')
    if i.get('packageType')!='SET' and i.get('packType')!='SET':
        raise ValidationError('В ЧЗ не подтверждён тип SET')
    children=child_codes(i)
    if children is None: raise ValidationError('ЧЗ не вернул полный список вложений. Успех не подтверждён')
    if children!=set(j['children']): raise ValidationError('Состав набора в ЧЗ не совпадает со сканированием')


def document_status(raw: Any) -> tuple[str,str]:
    if isinstance(raw,list) and len(raw)==1: raw=raw[0]
    if not isinstance(raw,dict): return ('UNRECOGNIZED',str(raw)[:1500])
    status=raw.get('status')
    if not isinstance(status,str): return ('UNRECOGNIZED',dumps(raw)[:3000])
    detail=raw.get('downloadDesc') or raw.get('errorMessage') or raw.get('errors') or ''
    return status, detail if isinstance(detail,str) else dumps(detail)


class Engine:
    def __init__(self,store: Store): self.store=store
    def submit(self, ident: str, api: TrueApi, signer):
        return self.submit_many([ident],api,signer)[0]
    def submit_many(self, idents: list[str], api: TrueApi, signer):
        jobs=self.store.get_many(idents)
        validate_batch(jobs)
        if any(j['status'] not in {'WAITING_SIGNATURE','BLOCKED'} or j['documentId'] for j in jobs):
            raise ValidationError('Повторная отправка запрещена. Сначала проверка существующего документа')
        try:
            info=api.codes([code for j in jobs for code in [j['setCode']]+j['children']])
            for j in jobs: preflight(j,info)
            content=batch_product_bytes(jobs)
            signature=signer.sign(content,detached=True)
            envelope=make_envelope(content,signature)
            if len(dumps(envelope).encode('utf-8'))>MAX_BYTES:
                raise ValidationError('Подписанный документ слишком большой; выберите меньше наборов')
        except Exception as e:
            self.store.change_many(jobs,'BLOCKED',str(e),allowed={'WAITING_SIGNATURE','BLOCKED'})
            raise
        # Persist BEFORE starting POST. Never auto-retry this operation.
        self.store.change_many(jobs,'SUBMITTING',f'Подписан общий документ: {len(jobs)} наборов. Начата отправка.',allowed={'WAITING_SIGNATURE','BLOCKED'})
        try:
            result=api.call('POST','/lk/documents/create?pg=grocery',envelope)
            if isinstance(result,dict):
                result=result.get('value') or result.get('document_id') or result.get('documentId')
            doc_id=str(uuid.UUID(str(result)))
        except Exception as e:
            self.store.change_many(jobs,'UNKNOWN',str(e)+'\nАвтоповтор всего документа запрещён. Проверьте наборы/документы ЧЗ.',allowed={'SUBMITTING'})
            raise
        self.store.change_many(jobs,'SUBMITTED',f'ЧЗ принял общий документ: {len(jobs)} наборов. Результат ещё не подтверждён.',doc_id,allowed={'SUBMITTING'})
        return self.refresh_many(idents,api)
    def refresh_many(self, idents, api):
        jobs=self.store.get_many(idents)
        validate_batch(jobs)
        groups=collections.defaultdict(list)
        for j in jobs:
            if j['status'] not in {'CANCELLED_LOCAL','VERIFIED'}:
                groups[j['documentId']].append(j)
        for doc,group in groups.items():
            if not doc:
                # Refresh is read-only. Expired authorization/network errors must reach the
                # connection diagnostics without turning unsent jobs into unknown submissions.
                info=api.codes([j['setCode'] for j in group])
                for j in group:
                    try: verify_set(j,info)
                    except (ValidationError,ApiError) as e:
                        if j['status'] in {'WAITING_SIGNATURE','BLOCKED'}:
                            parent=info[j['setCode']]
                            # A code that has not formed yet keeps its original waiting/error
                            # state. Evidence of formation protects it from another submission.
                            if parent.get('status')=='INTRODUCED' or child_codes(parent):
                                self.store.change(j['id'],'CHECK_REQUIRED',str(e)+'\nВ ЧЗ есть признаки сформированного набора; повторная отправка запрещена.')
                        else:
                            self.store.change(j['id'],'UNKNOWN',str(e)+'\nНе отправляйте повторно. Проверьте ЛК ЧЗ.')
                    else:
                        self.store.change(j['id'],'VERIFIED','Восстановлено по ЧЗ: владелец, статус и полный состав совпали. ID документа не восстановлен.')
                continue
            status,detail=document_status(api.document_info(doc))
            if status=='CHECKED_OK':
                try: info=api.codes([j['setCode'] for j in group])
                except ApiError as e:
                    if e.http_status in {401,403}: raise
                    self.store.change_many(group,'CHECK_REQUIRED','Документ обработан, но '+str(e));raise
                for j in group:
                    try: verify_set(j,info)
                    except (ValidationError,ApiError) as e:
                        self.store.change(j['id'],'CHECK_REQUIRED','Документ обработан, но '+str(e))
                    else:
                        self.store.change(j['id'],'VERIFIED','Документ обработан, владелец и полный состав проверены.')
            elif status in {'CHECKED_NOT_OK','PARSE_ERROR','PROCESSING_ERROR','CANCELLED'}:
                self.store.change_many(group,'REJECTED',status+': '+detail)
            elif status=='UNRECOGNIZED':
                self.store.change_many(group,'CHECK_REQUIRED','Неизвестный формат статуса документа: '+detail)
            else:
                self.store.change_many(group,'PROCESSING',status+': '+detail)
        return self.store.get_many(idents)
    def refresh(self,ident: str,api: TrueApi):
        return self.refresh_many([ident],api)[0]
