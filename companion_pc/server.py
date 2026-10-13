"""CHZ PC companion: LAN inbox + loopback-only approval UI. Run with Start_PC.cmd.
The LAN service cannot authenticate to CHZ, sign, or submit. User approval is local.
"""
from __future__ import annotations
import argparse
import base64
import concurrent.futures
import collections
import hmac
import io
import ipaddress
import json
import os
import secrets
import socket
import ssl
import sqlite3
import threading
import time
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from core import Store, Engine, TrueApi, ValidationError, ApiError, VERSION, MAX_BYTES, MAX_SELECTION, dumps, STATUS_RU
from signer import WindowsSigner, certificates
from transport import setup

ROOT=Path(__file__).resolve().parent
ACTIVE={'SUBMITTED','PROCESSING','CHECK_REQUIRED','UNKNOWN'}
PENDING=ACTIVE|{'WAITING_SIGNATURE','BLOCKED'}
SESSION_TTL=8*3600

def backup_existing_queue(data_dir):
    """Read-only copy before startup recovery. Never copy TLS or pairing secrets."""
    source=Path(data_dir)/'queue.sqlite3'
    if not source.is_file(): return None
    dest=Path(data_dir)/('backup-before-'+VERSION+'-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]+'.sqlite3')
    src=sqlite3.connect(source.resolve().as_uri()+'?mode=ro',uri=True,timeout=15)
    target=sqlite3.connect(dest)
    try:
        src.backup(target)
        if target.execute('PRAGMA quick_check').fetchone()[0]!='ok':
            raise ValidationError('Резервная копия очереди повреждена. Запуск остановлен; исходные данные сохранены.')
        # Read every archived payload too, so invalid UTF-8/JSON is found before startup writes.
        for (payload,) in target.execute('SELECT payload FROM jobs'): json.loads(payload)
    finally: target.close();src.close()
    return dest

def is_lan_v4(host):
    try:
        ip=ipaddress.ip_address(host)
        return ip.version==4 and any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'))
    except ValueError: return False

class State:
    def __init__(self, data_dir, settings, advertise=None):
        self.settings=settings; self.admin_key=secrets.token_urlsafe(32)
        self.data_dir=data_dir; self.store=Store(data_dir/'queue.sqlite3'); self.engine=Engine(self.store)
        self.sessions={}; self.operations={}; self.op_lock=threading.RLock(); self.worker=concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.api_lock=threading.RLock(); self.stop=threading.Event(); self.advertise=advertise
        self.sync_lock=threading.RLock(); self.sync_records={}
    def addresses(self):
        result=[]
        if self.advertise: result=[self.advertise]
        else:
            try:
                result=[x[4][0] for x in socket.getaddrinfo(socket.gethostname(),None,socket.AF_INET)]
                with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
                    s.connect(('192.0.2.1',9)); result.append(s.getsockname()[0]) # Route lookup only, no packet is sent.
            except OSError: pass
        return sorted(set(x for x in result if is_lan_v4(x)))
    def connection(self, host=None):
        addresses=self.addresses()
        if host is not None:
            if host not in addresses: raise ValidationError('Выберите один из локальных адресов ПК')
        else: host=addresses[0] if addresses else None
        result={'addresses':addresses,'lanPort':self.settings['lanPort'],
                'deviceKey':self.settings['deviceKey'], 'certificateSha256':self.settings['certificateSha256']}
        if host:
            result['url']=f"https://{host}:{self.settings['lanPort']}"
            pair={'url':result['url'],'key':result['deviceKey'],'pin':result['certificateSha256']}
            result['pairing']='CZPAIR1:'+base64.urlsafe_b64encode(dumps(pair).encode()).decode().rstrip('=')
        return result
    def schedule(self, action, payload):
        if action not in {'submit','refresh','login-check'}: raise ValidationError('Неизвестная операция')
        if payload.get('confirmed') is not True: raise ValidationError('Нужно явное подтверждение на ПК')
        if action=='login-check':
            group=payload.get('group')
            if not isinstance(group,dict) or set(group)!={'inn','environment','pg'}:
                raise ValidationError('Выберите один ИНН, контур и товарную группу')
            snapshot=[j for j in self.store.all() if j['status'] in PENDING and
                      (j['inn'],j['environment'],j['pg'])==(group['inn'],group['environment'],group['pg'])]
            if not snapshot: raise ValidationError('В выбранной организации нет неподтверждённых наборов')
            return self.enqueue_operation(action,snapshot,payload)
        items=payload.get('items')
        if not isinstance(items,list) or not 1<=len(items)<=MAX_SELECTION: raise ValidationError(f'Выберите от 1 до {MAX_SELECTION} наборов')
        snapshot=[]; seen=set()
        rows=self.store.get_many([str(uuid.UUID(item['id'])) for item in items])
        for item,j in zip(items,rows):
            if j['id'] in seen: raise ValidationError('Повтор задания')
            if not hmac.compare_digest(j['payloadHash'], str(item.get('hash',''))):
                raise ValidationError('Состав изменился. Обновите окно и проверьте ещё раз')
            if action=='submit' and j['status'] not in {'WAITING_SIGNATURE','BLOCKED'}:
                raise ValidationError('В выборе есть уже отправленный набор; повторная отправка запрещена')
            seen.add(j['id']); snapshot.append(j)
        if len({(j['inn'],j['environment'],j['pg']) for j in snapshot})!=1:
            raise ValidationError('За один раз выбирайте наборы одного ИНН, контура и товарной группы')
        return self.enqueue_operation(action,snapshot,payload)
    def enqueue_operation(self,action,snapshot,payload):
        signer=WindowsSigner(payload.get('thumbprint',''))
        ident=str(uuid.uuid4())
        with self.op_lock:
            if any(x['state'] in ('QUEUED','RUNNING') for x in self.operations.values()):
                raise ValidationError('Дождитесь завершения предыдущей операции')
            self.operations[ident]={'state':'QUEUED','action':action,'total':len(snapshot),'done':0,'error':'',
                                    'stage':'login','startedAt':time.time()}
        try:self.worker.submit(self._run,ident,action,snapshot,signer)
        except Exception:
            with self.op_lock:self.operations[ident].update(state='ERROR',error='Не удалось запустить операцию. Откройте помощник заново.')
            raise
        return {'operationId':ident}
    @staticmethod
    def group_key(job):return job['environment'],job['inn'],job['pg']
    def safe_error(self,error,api=None):
        detail=str(error)
        for secret in (self.admin_key,self.settings.get('deviceKey'),getattr(api,'token',None)):
            if isinstance(secret,str) and secret:detail=detail.replace(secret,'[скрыто]')
        return detail[:3000]
    def record_sync(self,group,**values):
        with self.sync_lock:self.sync_records.setdefault(group,{'lastAttempt':None,'lastSuccess':None,'error':''}).update(values)
    def session_for(self,group):
        with self.sync_lock:
            matches=[v for k,v in self.sessions.items() if k[:2]==group[:2]]
        if not matches:return None,'missing'
        session=max(matches,key=lambda item:item[1])
        return (None,'expired') if time.time()-session[1]>SESSION_TTL else (session[0],'active')
    def sync_failure(self,group,error,api=None):
        expired=isinstance(error,ApiError) and error.http_status in (401,403)
        with self.sync_lock:
            if expired:
                for key,value in list(self.sessions.items()):
                    if key[:2]==group[:2] and (api is None or value[0] is api):self.sessions.pop(key,None)
            self.sync_records.setdefault(group,{'lastAttempt':None,'lastSuccess':None}).update(
                error=self.safe_error(error,api),sessionExpired=expired)
    def _run(self, ident, action, jobs, signer):
        with self.op_lock:
            op=self.operations[ident]; op['state']='RUNNING'
        group=self.group_key(jobs[0]);api=None
        self.record_sync(group,lastAttempt=time.time(),error='')
        try:
            with self.api_lock:
                first=jobs[0]; key=(first['environment'],first['inn'],signer.thumbprint)
                # Authentication signature is deliberately initiated only by a local user's action.
                api=TrueApi(first['environment']); api.authenticate(signer,first['inn'])
                with self.sync_lock:self.sessions[key]=(api,time.time())
                self.record_sync(group,error='',sessionExpired=False)
                ids=[j['id'] for j in jobs]
                with self.op_lock:op['stage']='submit' if action=='submit' else 'check'
                if action=='submit':
                    result=self.engine.submit_many(ids,api,signer)
                    with self.op_lock:op['done']=len(result)
                else:
                    for start in range(0,len(ids),MAX_SELECTION):
                        self.record_sync(group,lastAttempt=time.time())
                        result=self.engine.refresh_many(ids[start:start+MAX_SELECTION],api)
                        with self.op_lock:op['done']+=len(result)
                self.record_sync(group,lastSuccess=time.time(),error='',sessionExpired=False)
            with self.op_lock:op['state']='DONE';op['finishedAt']=time.time()
        except Exception as e:
            self.sync_failure(group,e,api)
            with self.op_lock:op.update(state='ERROR',error=self.safe_error(e,api),finishedAt=time.time())
    def poll(self):
        while not self.stop.wait(20):
            self.poll_once()
    def poll_once(self):
        if not self.api_lock.acquire(blocking=False):return
        try:
            groups=collections.defaultdict(list)
            for j in self.store.all():
                if j['status'] in PENDING:groups[self.group_key(j)].append(j['id'])
            for group,ids in groups.items():
                api,_=self.session_for(group)
                if api is None:continue # A timer never invokes authentication or a new УКЭП signature.
                try:
                    for start in range(0,len(ids),MAX_SELECTION):
                        self.record_sync(group,lastAttempt=time.time())
                        self.engine.refresh_many(ids[start:start+MAX_SELECTION],api)
                    self.record_sync(group,lastSuccess=time.time(),error='',sessionExpired=False)
                except Exception as error:self.sync_failure(group,error,api)
        finally:self.api_lock.release()
    def public_sync(self,jobs=None):
        groups=collections.defaultdict(collections.Counter)
        for j in self.store.all() if jobs is None else jobs:
            group=self.group_key(j)
            groups[group]['total']+=1;groups[group]['pending']+=int(j['status'] in PENDING)
            groups[group]['verified']+=int(j['status']=='VERIFIED')
            groups[group]['failed']+=int(j['status']=='REJECTED')
            groups[group]['cancelled']+=int(j['status']=='CANCELLED_LOCAL')
            groups[group]['submitting']+=int(j['status']=='SUBMITTING')
        result=[]
        for group,counts in sorted(groups.items()):
            pending=counts['pending']
            _,session=self.session_for(group)
            with self.sync_lock:record=dict(self.sync_records.get(group,{}))
            if record.get('sessionExpired') and session=='missing':session='expired'
            result.append(dict(environment=group[0],inn=group[1],pg=group[2],**counts,
                allVerified=counts['total']>0 and counts['total']==counts['verified'],
                session=session,requiresLogin=pending>0 and session!='active',
                lastAttempt=record.get('lastAttempt'),lastSuccess=record.get('lastSuccess'),error=record.get('error','')))
        return result
    def public_jobs(self):
        return [dict(j,statusRu=STATUS_RU.get(j['status'],j['status'])) for j in self.store.all()]
    def public_operations(self):
        with self.op_lock: return {k:dict(v) for k,v in self.operations.items()}

class Server(ThreadingHTTPServer):
    daemon_threads=True
    allow_reuse_address=False
    tls_context=None
    def process_request_thread(self, connection, address):
        # TLS handshake in a worker: an idle peer cannot block every TSD request in accept().
        if self.tls_context is not None:
            try:
                connection.settimeout(15)
                connection=self.tls_context.wrap_socket(connection,server_side=True)
            except (OSError,ssl.SSLError):
                self.shutdown_request(connection);return
        super().process_request_thread(connection,address)

class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    server_version='CHZ-Local/1.3'
    def log_message(self, *args): pass # No pairing keys, authorization headers, or individual marking codes in access logs.
    def setup(self):
        super().setup(); self.connection.settimeout(15)
    @property
    def state(self): return self.server.state
    @property
    def lane(self): return self.server.lane
    def response(self, code, body, mime='application/json; charset=utf-8'):
        if not isinstance(body,bytes): body=dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type',mime); self.send_header('Content-Length',str(len(body)))
        self.send_header('Connection','close');self.close_connection=True
        self.send_header('Cache-Control','no-store'); self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Referrer-Policy','no-referrer')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
        self.end_headers(); self.wfile.write(body)
    def read_json(self):
        if self.headers.get('Transfer-Encoding'): raise ValidationError('Chunked bodies are not supported')
        length=int(self.headers.get('Content-Length','0'))
        if length<=0 or length>MAX_BYTES: raise ValidationError('Неверный размер сообщения')
        if self.headers.get('Content-Type','').split(';',1)[0].lower()!='application/json':
            raise ValidationError('Требуется application/json')
        content=self.rfile.read(length)
        if len(content)!=length: raise ValidationError('Сообщение получено не полностью')
        return json.loads(content.decode('utf-8'))
    def authorize(self):
        if self.lane=='admin':
            expected=f"127.0.0.1:{self.server.server_port}"
            if self.headers.get('Host')!=expected or self.client_address[0]!='127.0.0.1':
                self.response(403,{'error':'Локальная панель доступна только на этом ПК'}); return False
            origin=self.headers.get('Origin')
            if origin and origin!=f'http://{expected}':
                self.response(403,{'error':'Чужой Origin'}); return False
            key=self.headers.get('X-Admin-Key','')
            if not hmac.compare_digest(key,self.state.admin_key):
                self.response(401,{'error':'Откройте панель по ссылке из окна Start_PC.cmd'}); return False
        else:
            if self.client_address[0]!='127.0.0.1' and not is_lan_v4(self.client_address[0]):
                self.response(403,{'error':'Только локальная сеть'}); return False
            key=self.headers.get('Authorization','')
            if not hmac.compare_digest(key,'Bearer '+self.state.settings['deviceKey']):
                self.response(401,{'error':'Неверный ключ подключения к ПК'}); return False
            if self.headers.get('Origin'):
                self.response(403,{'error':'LAN API предназначен для ТСД, не браузера'}); return False
        return True
    def do_GET(self): self.handle_safe('GET')
    def do_POST(self): self.handle_safe('POST')
    def handle_safe(self, method):
        try: self.route(method)
        except (ValidationError, ValueError, KeyError, TypeError) as e:
            self.response(400,{'error':str(e)[:2000]})
        except (ConnectionError,TimeoutError): pass
        except Exception as e:
            self.response(500,{'error':'Операция не завершена: '+str(e)[:1500]})
    def route(self,method):
        url=urlsplit(self.path); path=url.path
        if method=='GET' and self.lane=='admin' and path in ('/','/app.js','/style.css'):
            # Page shell carries no data or credentials. Its API still requires the local secret.
            if self.headers.get('Host')!=f'127.0.0.1:{self.server.server_port}':
                self.response(403,{'error':'Неверный Host'}); return
            name={'/':'index.html','/app.js':'app.js','/style.css':'style.css'}[path]
            mime={'/':'text/html; charset=utf-8','/app.js':'text/javascript; charset=utf-8','/style.css':'text/css; charset=utf-8'}[path]
            self.response(200,(ROOT/'web'/name).read_bytes(),mime); return
        if not self.authorize(): return
        if self.lane=='lan':
            if path=='/v1/ping' and method=='GET': self.response(200,{'version':VERSION,'signing':'PC confirmation required'}); return
            if path=='/v1/jobs' and method=='POST':
                self.response(200,self.state.store.enqueue(self.read_json())); return
            if path.startswith('/v1/jobs/') and method=='GET':
                ident=str(uuid.UUID(path.split('/')[-1]))
                try: job=self.state.store.get(ident)
                except KeyError: self.response(404,{'error':'Набор с этим UUID ещё не принят на ПК'});return
                self.response(200,job); return
            self.response(404,{'error':'ТСД может только передать набор и получить его статус'}); return
        if path=='/api/state' and method=='GET':
            jobs=self.state.public_jobs()
            self.response(200,{'version':VERSION,'jobs':jobs,'syncGroups':self.state.public_sync(jobs),
                       'operations':self.state.public_operations(),'selectionLimit':MAX_SELECTION,'dataDir':str(self.state.data_dir)}); return
        if path=='/api/connection' and method=='GET': self.response(200,self.state.connection()); return
        if path=='/api/pair.svg' and method=='GET':
            import qrcode
            import qrcode.image.svg
            from urllib.parse import parse_qs
            host=parse_qs(url.query).get('host',[None])[0]
            pair=self.state.connection(host).get('pairing')
            if not pair: raise ValidationError('Не найден локальный IP. Запустите с --advertise 192.168.x.x')
            image=qrcode.make(pair,image_factory=qrcode.image.svg.SvgPathImage,box_size=8,border=4)
            stream=io.BytesIO(); image.save(stream)
            self.response(200,stream.getvalue(),'image/svg+xml'); return
        if path=='/api/certificates' and method=='POST':
            self.read_json(); self.response(200,{'certificates':certificates()}); return
        if path in ('/api/submit','/api/refresh','/api/login-check') and method=='POST':
            self.response(202,self.state.schedule(path.split('/')[-1],self.read_json())); return
        if path=='/api/cancel' and method=='POST':
            body=self.read_json()
            if body.get('confirmed') is not True: raise ValidationError('Нужно подтверждение отмены')
            items=body.get('items')
            if not isinstance(items,list) or not 1<=len(items)<=MAX_SELECTION: raise ValidationError(f'Выберите 1–{MAX_SELECTION} заданий')
            with self.state.op_lock:
                if any(x['state'] in ('QUEUED','RUNNING') for x in self.state.operations.values()):
                    raise ValidationError('Дождитесь завершения операции подписи/проверки')
                # Validate ALL entries before making any cancellation.
                for item in items:
                    j=self.state.store.get(str(uuid.UUID(item['id'])))
                    if j['payloadHash']!=item.get('hash') or j['status'] not in {'WAITING_SIGNATURE','BLOCKED'} or j['documentId']:
                        raise ValidationError('В выборе есть уже отправленный/изменившийся набор')
                result=[self.state.store.cancel_unsent(x['id'],x['hash']) for x in items]
            self.response(200,{'jobs':result}); return
        if path=='/api/backup' and method=='POST':
            self.read_json(); dest=self.state.data_dir/f"backup-{time.strftime('%Y%m%d-%H%M%S')}.sqlite3"
            self.state.store.backup(dest); self.response(200,{'path':str(dest)}); return
        self.response(404,{'error':'Нет такого действия'})

def main():
    parser=argparse.ArgumentParser(description='ТСД Честный ЗНАК — помощник подписи на ПК')
    parser.add_argument('--data-dir',type=Path,default=Path(os.environ.get('LOCALAPPDATA',str(Path.home())))/'CHZSetCollector')
    parser.add_argument('--advertise',help='Локальный IPv4 ПК для подключения ТСД')
    parser.add_argument('--no-browser',action='store_true')
    args=parser.parse_args()
    if args.advertise and (not is_lan_v4(args.advertise)):
        parser.error('--advertise должен быть локальным IPv4')
    settings=setup(args.data_dir)
    # Bind first: a second instance must not reopen/reset the live queue.
    admin=Server(('127.0.0.1',settings['adminPort']),Handler)
    try: lan=Server(('0.0.0.0',settings['lanPort']),Handler)
    except Exception: admin.server_close(); raise
    try:
        backup_path=backup_existing_queue(args.data_dir)
        state=State(args.data_dir,settings,args.advertise)
    except Exception:
        admin.server_close();lan.server_close();raise
    for server,lane in ((admin,'admin'),(lan,'lan')): server.state=state; server.lane=lane
    lan.tls_context=settings['sslContext']
    for target in (admin.serve_forever,lan.serve_forever,state.poll): threading.Thread(target=target,daemon=True).start()
    link=f"http://127.0.0.1:{settings['adminPort']}/#"+state.admin_key
    print('\nЧЗ: Наборы '+VERSION+' — окно не закрывать во время работы.')
    print('Панель только на этом ПК:',link)
    print('Папка данных:',args.data_dir)
    if backup_path: print('Проверенная копия очереди до запуска:',backup_path)
    print('ТСД: одна локальная сеть. Коды подключения находятся в панели.')
    print('Отправка в ЧЗ ТОЛЬКО после кнопки «Подписать и отправить» на ПК.')
    if not args.no_browser: webbrowser.open(link)
    try:
        while True: time.sleep(1)
    except KeyboardInterrupt:
        state.stop.set(); admin.shutdown(); lan.shutdown(); state.worker.shutdown(wait=False,cancel_futures=True)
        print('Остановлено. Очередь сохранена.')

if __name__=='__main__': main()
