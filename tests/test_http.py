"""Loopback integration tests, real HTTP/TLS and SQLite, NO connection to CHZ."""
import http.client
import json
from pathlib import Path
import socket
import ssl
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'companion_pc'))
from server import Server,Handler,State,is_lan_v4
from transport import setup
from test_core import job

class HttpTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();d=Path(self.tmp.name);self.config=setup(d)
        self.state=State(d,self.config,'192.168.1.10')
        self.admin=Server(('127.0.0.1',0),Handler);self.admin.state=self.state;self.admin.lane='admin'
        self.lan=Server(('127.0.0.1',0),Handler);self.lan.state=self.state;self.lan.lane='lan'
        self.lan.socket=self.config['sslContext'].wrap_socket(self.lan.socket,server_side=True)
        self.config['adminPort']=self.admin.server_port;self.config['lanPort']=self.lan.server_port
        for s in (self.admin,self.lan):threading.Thread(target=s.serve_forever,daemon=True).start()
    def tearDown(self):
        for s in (self.admin,self.lan):s.shutdown();s.server_close()
        self.state.worker.shutdown(wait=True);self.tmp.cleanup()
    def request(self,lan,path,body=None,auth=True,extra=None):
        s=self.lan if lan else self.admin
        if lan:
            ctx=ssl.create_default_context(cafile=str(Path(self.tmp.name)/'tls.crt'))
            c=http.client.HTTPSConnection('127.0.0.1',s.server_port,context=ctx,timeout=3)
        else:c=http.client.HTTPConnection('127.0.0.1',s.server_port,timeout=3)
        headers={'Content-Type':'application/json'}
        if auth:headers.update({'Authorization':'Bearer '+self.config['deviceKey']} if lan else {'X-Admin-Key':self.state.admin_key})
        if extra:headers.update(extra)
        c.request('GET' if body is None else 'POST',path,body=None if body is None else json.dumps(body),headers=headers)
        r=c.getresponse();data=r.read();status=r.status;c.close();return status,data
    def test_tls_inbox_accepts_but_never_signs(self):
        j=job()
        with patch('server.WindowsSigner') as signer,patch('server.TrueApi') as api:
            code,raw=self.request(True,'/v1/jobs',j)
            self.assertEqual(code,200);self.assertEqual(json.loads(raw)['status'],'WAITING_SIGNATURE')
            signer.assert_not_called();api.assert_not_called()
    def test_unauthorized_inbox(self):
        self.assertEqual(self.request(True,'/v1/jobs',job(),False)[0],401);self.assertEqual(self.state.store.all(),[])
    def test_lan_has_no_signing_endpoint(self):
        self.assertEqual(self.request(True,'/api/submit',{'confirmed':True})[0],404)
        self.assertEqual(self.request(True,'/api/certificates',{})[0],404)
    def test_admin_requires_secret(self):
        self.assertEqual(self.request(False,'/api/state',auth=False)[0],401)
    def test_admin_rejects_cross_origin(self):
        self.assertEqual(self.request(False,'/api/state',extra={'Origin':'https://evil.example'})[0],403)
    def test_admin_dns_rebinding_blocked(self):
        self.assertEqual(self.request(False,'/api/state',extra={'Host':'evil.example'})[0],403)
    def test_admin_requires_explicit_confirmation(self):
        self.assertEqual(self.request(False,'/api/submit',{'confirmed':False})[0],400)
    def test_qr_and_pairing_persist(self):
        status,raw=self.request(False,'/api/connection');self.assertEqual(status,200);pair=json.loads(raw)
        self.assertTrue(pair['pairing'].startswith('CZPAIR1:'));self.assertEqual(len(pair['certificateSha256']),64)
        c2=setup(Path(self.tmp.name));self.assertEqual(c2['deviceKey'],self.config['deviceKey']);self.assertEqual(c2['certificateSha256'],self.config['certificateSha256'])
        code,svg=self.request(False,'/api/pair.svg');self.assertEqual(code,200);self.assertIn(b'<svg',svg)
    def test_upload_idempotency_over_tls(self):
        j=job();self.request(True,'/v1/jobs',j);self.request(True,'/v1/jobs',j)
        self.assertEqual(len(self.state.store.all()),1)
        self.assertEqual(self.request(True,'/v1/jobs/'+j['id'])[0],200)
    def test_lan_addresses(self):
        for ip in ('192.168.0.1','172.16.0.1','10.0.0.1'):self.assertTrue(is_lan_v4(ip))
        for ip in ('127.0.0.1','0.0.0.0','203.0.113.1','8.8.8.8','::1','169.254.1.1'):self.assertFalse(is_lan_v4(ip))

if __name__=='__main__':unittest.main()
