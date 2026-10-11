"""Offline tests: synthetic codes only, no real CHZ calls or real signatures."""
import base64
import copy
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'companion_pc'))
from core import *


def gtin(body):
    assert len(body)==13
    return body+str((-sum(int(c)*(3 if i%2==0 else 1) for i,c in enumerate(body)))%10)
SET_GTIN=gtin('0460000000001')
A=gtin('0460000000002'); B=gtin('0460000000003')
INN='7707083893' # Public organization INN used only to test checksum; never submitted.
def cis(g,n): return '01'+g+'21'+str(n).zfill(13)
def job():
    return dict(schemaVersion=1,id=str(uuid.uuid4()),environment='sandbox',inn=INN,pg='grocery',
      template={'setGtin':SET_GTIN,'expected':{A:3,B:3}},setCode=cis(SET_GTIN,1),
      children=[cis(A,n) for n in (1,2,3)]+[cis(B,n) for n in (4,5,6)])

def infos(j,complete=False):
    res={j['setCode']:{'cis':j['setCode'],'ownerInn':j['inn'],'status':'INTRODUCED' if complete else 'APPLIED',
                         'packageType':'SET','emissionType':'LOCAL','child':j['children'] if complete else []}}
    # Different owner for every unit is intentional OSU scenario.
    res.update({c:{'cis':c,'ownerInn':'some other supplier','status':'INTRODUCED','packageType':'UNIT'} for c in j['children']})
    return res

class FakeSigner:
    def __init__(self): self.calls=[]
    def sign(self,content,detached):
        self.calls.append((content,detached))
        return base64.b64encode(b'MOCK-SIGNATURE-NOT-FOR-PRODUCTION'+content).decode()

class FakeApi:
    def __init__(self,j):
        self.j=j;self.sent=[];self.complete=False;self.doc_status='IN_PROGRESS';self.raise_on_post=False
    def codes(self,cs):
        available=infos(self.j,self.complete);return {c:available[c] for c in cs}
    def call(self,method,path,payload):
        assert method=='POST' and path=='/lk/documents/create?pg=grocery'
        self.sent.append(copy.deepcopy(payload))
        if self.raise_on_post: raise ApiError('timeout after request was transmitted')
        return str(uuid.uuid4())
    def document_info(self,*args): return {'status':self.doc_status}

class ContractTests(unittest.TestCase):
    def test_gtin_and_inn(self):
        self.assertTrue(valid_gtin(A));self.assertFalse(valid_gtin(A[:-1]+str((int(A[-1])+1)%10)))
        self.assertTrue(valid_inn(INN));self.assertFalse(valid_inn('7700000000'))
    def test_code_normalization(self):
        c=cis(A,1)
        for raw in (c,']d2'+c,']Q3'+c,c+'\x1d91TEST\x1d92CRYPT',c+'93ABCD','(01)'+A+'(21)'+'1'.zfill(13)):
            self.assertEqual(normalize_code(raw),c)
    def test_91_inside_serial_not_truncated(self):
        c='01'+A+'21'+'ab91cdEF?%123'
        c=c+'4' if len(c)==30 else c
        self.assertEqual(len(c),31);self.assertEqual(normalize_code(c+'\x1d91SIGN'),c)
    def test_quotes_and_special_chars_preserved(self):
        c='01'+A+'21'+'a"b/c%&=<>123'
        self.assertEqual(len(c),31);self.assertEqual(normalize_code(c),c)
        self.assertEqual(json.loads(dumps({'c':c}))['c'],c)
    def test_bad_codes(self):
        for raw in (None,42,'1234567890123','00'+'0'*18, '01'+A+'21short',cis(A,1)+'WRONG','01'+A+'21'+'я'*13):
            with self.subTest(raw=raw),self.assertRaises(ValidationError): normalize_code(raw)
    def test_exact_payload_and_signature(self):
        j=job();content=product_bytes(j);doc=json.loads(content)
        self.assertEqual(doc,{'participantId':INN,'aggregationUnits':[{'unitSerialNumber':j['setCode'],'sntins':j['children']}]})
        sig=FakeSigner().sign(content,True);e=make_envelope(content,sig)
        self.assertEqual(e['type'],'SETS_AGGREGATION');self.assertEqual(e['document_format'],'MANUAL')
        self.assertEqual(base64.b64decode(e['product_document']),content)
        self.assertNotIn('aggregationType',doc['aggregationUnits'][0])
    def test_no_empty_signature(self):
        for sig in ('','plain text','!!!!',None):
            with self.assertRaises(ValidationError):make_envelope(b'{}',sig)
    def test_incomplete_wrong_duplicate_rejected(self):
        for mutate in (lambda j:j['children'].pop(),lambda j:j['children'].__setitem__(0,j['children'][1]),
           lambda j:j.update(pg='milk'),lambda j:j.update(environment='https://attacker'),
           lambda j:j['template']['expected'].update({A:True}),lambda j:j.update(setCode=cis(A,9)),
           lambda j:j.update(inn='7700000000')):
            j=job();mutate(j)
            with self.assertRaises(ValidationError):validate_job(j)
    def test_osu_different_owner_allowed(self):
        j=job();preflight(j,infos(j))
    def test_parent_must_belong_to_signer(self):
        j=job();i=infos(j);i[j['setCode']]['ownerInn']='not user'
        with self.assertRaises(ValidationError):preflight(j,i)
    def test_nested_and_retired_blocked(self):
        for key,value in [('parent','other'),('status','RETIRED'),('packageType','BOX')]:
            j=job();i=infos(j);i[j['children'][0]][key]=value
            with self.assertRaises(ValidationError):preflight(j,i)
    def test_verification_exact_children(self):
        j=job();i=infos(j,True);verify_set(j,i)
        i[j['setCode']]['child']=j['children'][:-1]
        with self.assertRaises(ValidationError):verify_set(j,i)
        i[j['setCode']].pop('child')
        with self.assertRaises(ValidationError):verify_set(j,i)
    def test_document_unknown_not_success(self):
        self.assertEqual(document_status({})[0],'UNRECOGNIZED')
    def test_authentication_uses_random_challenge_attached(self):
        for field in ('token','uuidToken'):
            api=TrueApi('sandbox');signer=FakeSigner();u=str(uuid.uuid4())
            with patch.object(api,'call',side_effect=[{'uuid':u,'data':'challenge-Привет'},{field:'returned-token'}]) as calls:
                api.authenticate(signer,INN)
            self.assertEqual(signer.calls,[('challenge-Привет'.encode(),False)])
            args=calls.call_args_list[1].args
            self.assertEqual(args[2]['uuid'],u);self.assertTrue(args[2]['unitedToken']);self.assertEqual(api.token,'returned-token')
    def test_document_info_v4(self):
        a=TrueApi('sandbox');u=str(uuid.uuid4())
        with patch.object(a,'call',return_value={}) as call:a.document_info(u)
        self.assertEqual(call.call_args.kwargs['version'],4)
    def test_host_allowlist(self):
        with self.assertRaises(KeyError):TrueApi('https://example.com')

class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'queue.sqlite3';self.store=Store(self.path);self.j=job()
    def tearDown(self):self.temp.cleanup()
    def test_enqueue_no_sign_no_submit(self):
        got=self.store.enqueue(self.j);self.assertEqual(got['status'],'WAITING_SIGNATURE');self.assertIsNone(got['documentId'])
    def test_upload_idempotency(self):
        a=self.store.enqueue(self.j);b=self.store.enqueue(self.j);self.assertEqual(a['payloadHash'],b['payloadHash']);self.assertEqual(len(self.store.all()),1)
    def test_same_id_changed_payload_rejected(self):
        self.store.enqueue(self.j);other=copy.deepcopy(self.j);other['children'][0]=cis(A,99)
        with self.assertRaises(ValidationError):self.store.enqueue(other)
    def test_code_reserved_across_jobs(self):
        self.store.enqueue(self.j);other=copy.deepcopy(self.j);other['id']=str(uuid.uuid4());other['setCode']=cis(SET_GTIN,99)
        with self.assertRaises(ValidationError):self.store.enqueue(other)
    def test_restart_keeps_data(self):
        self.store.enqueue(self.j);other=Store(self.path);self.assertEqual(other.get(self.j['id'])['children'],self.j['children'])
    def test_crash_during_submission_becomes_unknown(self):
        self.store.enqueue(self.j);self.store.change(self.j['id'],'SUBMITTING');other=Store(self.path)
        self.assertEqual(other.get(self.j['id'])['status'],'UNKNOWN')
    def test_local_cancel_releases_codes(self):
        a=self.store.enqueue(self.j);self.store.cancel_unsent(a['id'],a['payloadHash']);other=copy.deepcopy(self.j);other['id']=str(uuid.uuid4())
        self.assertEqual(self.store.enqueue(other)['status'],'WAITING_SIGNATURE')
    def test_cannot_cancel_sent_or_unknown(self):
        a=self.store.enqueue(self.j);self.store.change(a['id'],'UNKNOWN')
        with self.assertRaises(ValidationError):self.store.cancel_unsent(a['id'],a['payloadHash'])
    def test_backup_restores(self):
        self.store.enqueue(self.j);dest=Path(self.temp.name)/'backup.db';self.store.backup(dest)
        self.assertEqual(Store(dest).get(self.j['id'])['children'],self.j['children'])
    def test_http_success_is_not_formation_success(self):
        a=self.store.enqueue(self.j);api=FakeApi(self.j);signer=FakeSigner();engine=Engine(self.store)
        result=engine.submit(a['id'],api,signer)
        self.assertEqual(result['status'],'PROCESSING');self.assertEqual(len(api.sent),1);self.assertTrue(signer.calls[0][1])
        with self.assertRaises(ValidationError):engine.submit(a['id'],api,signer)
        self.assertEqual(len(api.sent),1)
    def test_full_success_requires_document_and_composition(self):
        a=self.store.enqueue(self.j);api=FakeApi(self.j);engine=Engine(self.store);engine.submit(a['id'],api,FakeSigner())
        api.doc_status='CHECKED_OK'
        self.assertEqual(engine.refresh(a['id'],api)['status'],'CHECK_REQUIRED')
        api.complete=True
        self.assertEqual(engine.refresh(a['id'],api)['status'],'VERIFIED')
    def test_timeout_never_auto_retries(self):
        a=self.store.enqueue(self.j);api=FakeApi(self.j);api.raise_on_post=True;engine=Engine(self.store)
        with self.assertRaises(ApiError):engine.submit(a['id'],api,FakeSigner())
        self.assertEqual(self.store.get(a['id'])['status'],'UNKNOWN')
        with self.assertRaises(ValidationError):engine.submit(a['id'],api,FakeSigner())
        self.assertEqual(len(api.sent),1)
    def test_unknown_reconciled_read_only(self):
        a=self.store.enqueue(self.j);self.store.change(a['id'],'UNKNOWN');api=FakeApi(self.j);api.complete=True
        self.assertEqual(Engine(self.store).refresh(a['id'],api)['status'],'VERIFIED');self.assertEqual(api.sent,[])
    def test_rejected_cannot_resubmit(self):
        a=self.store.enqueue(self.j);api=FakeApi(self.j);api.doc_status='CHECKED_NOT_OK';e=Engine(self.store)
        self.assertEqual(e.submit(a['id'],api,FakeSigner())['status'],'REJECTED')
        with self.assertRaises(ValidationError):e.submit(a['id'],api,FakeSigner())
    def test_preflight_failure_no_post(self):
        a=self.store.enqueue(self.j);api=FakeApi(self.j)
        bad=infos(self.j);bad[self.j['children'][0]]['status']='RETIRED'
        with patch.object(api,'codes',return_value=bad),self.assertRaises(ValidationError):Engine(self.store).submit(a['id'],api,FakeSigner())
        self.assertEqual(api.sent,[]);self.assertEqual(self.store.get(a['id'])['status'],'BLOCKED')
    def test_signer_cancel_keeps_queue(self):
        a=self.store.enqueue(self.j);api=FakeApi(self.j);s=FakeSigner()
        with patch.object(s,'sign',side_effect=RuntimeError('PIN cancelled')),self.assertRaises(RuntimeError):Engine(self.store).submit(a['id'],api,s)
        self.assertEqual(self.store.get(a['id'])['children'],self.j['children']);self.assertEqual(api.sent,[])

if __name__=='__main__':unittest.main()
