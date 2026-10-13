"""One signed document, durable group transitions. Synthetic data and fake CHZ only."""
import copy
import json
import tempfile
import unittest
import uuid
from pathlib import Path
from test_core import job, cis, infos, FakeSigner
from core import Store, Engine, ApiError, ValidationError, batch_product_bytes

def pair():
    a=job();b=job()
    b['setCode']=cis(b['template']['setGtin'],101)
    b['children']=[c[:18]+str(101+i).zfill(13) for i,c in enumerate(b['children'])]
    return [a,b]

class BatchApi:
    def __init__(self,jobs,store):
        self.info={k:v for j in jobs for k,v in infos(j).items()}
        self.jobs=jobs;self.store=store;self.sent=[];self.signals=[]
        self.doc_calls=0;self.code_calls=[];self.doc_status='IN_PROGRESS';self.fail=False
        self.document=str(uuid.uuid4())
    def codes(self,codes):
        self.code_calls.append(list(codes));return {c:self.info[c] for c in codes}
    def call(self,method,path,envelope):
        assert method=='POST' and path=='/lk/documents/create?pg=grocery'
        assert {self.store.get(j['id'])['status'] for j in self.jobs}=={'SUBMITTING'}
        self.sent.append(copy.deepcopy(envelope))
        if self.fail:raise ApiError('lost response after transmitted POST')
        return self.document
    def document_info(self,ident):
        assert ident==self.document
        self.doc_calls+=1;return {'status':self.doc_status}

class BatchTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'queue.sqlite3'
        self.store=Store(self.path);self.jobs=pair()
        for j in self.jobs:self.store.enqueue(j)
        self.ids=[j['id'] for j in self.jobs];self.api=BatchApi(self.jobs,self.store)
        self.engine=Engine(self.store);self.signer=FakeSigner()
    def tearDown(self):self.temp.cleanup()
    def test_two_sets_are_one_signature_one_post_and_one_document(self):
        result=self.engine.submit_many(self.ids,self.api,self.signer)
        self.assertEqual(len(self.signer.calls),1);self.assertEqual(len(self.api.sent),1)
        document=json.loads(self.signer.calls[0][0])
        self.assertEqual(len(document['aggregationUnits']),2)
        self.assertEqual([u['unitSerialNumber'] for u in document['aggregationUnits']],[j['setCode'] for j in self.jobs])
        self.assertEqual({j['documentId'] for j in result},{self.api.document})
        self.assertEqual({j['status'] for j in result},{'PROCESSING'})
        self.assertEqual(self.api.doc_calls,1)
    def test_mixed_group_and_cross_set_duplicate_rejected_before_signing(self):
        mixed=copy.deepcopy(self.jobs);mixed[1]['environment']='production'
        with self.assertRaises(ValidationError):batch_product_bytes(mixed)
        repeated=copy.deepcopy(self.jobs);repeated[1]['children']=repeated[0]['children'][:]
        with self.assertRaises(ValidationError):batch_product_bytes(repeated)
        with self.assertRaises(ValidationError):self.engine.submit_many(self.ids[:1]*2,self.api,self.signer)
        self.assertEqual(self.signer.calls,[]);self.assertEqual(self.api.sent,[])
    def test_preflight_failure_blocks_entire_document_without_post(self):
        self.api.info[self.jobs[1]['children'][0]]['status']='RETIRED'
        with self.assertRaises(ValidationError):self.engine.submit_many(self.ids,self.api,self.signer)
        self.assertEqual({j['status'] for j in self.store.all()},{'BLOCKED'})
        self.assertEqual(self.signer.calls,[]);self.assertEqual(self.api.sent,[])
    def test_lost_post_response_marks_every_member_unknown_and_never_retries(self):
        self.api.fail=True
        with self.assertRaises(ApiError):self.engine.submit_many(self.ids,self.api,self.signer)
        self.assertEqual({j['status'] for j in self.store.all()},{'UNKNOWN'})
        with self.assertRaises(ValidationError):self.engine.submit_many(self.ids,self.api,self.signer)
        self.assertEqual(len(self.api.sent),1);self.assertEqual(len(self.signer.calls),1)
    def test_batch_state_transition_is_atomic_if_one_member_canceled(self):
        jobs=self.store.get_many(self.ids)
        self.store.cancel_unsent(jobs[1]['id'],jobs[1]['payloadHash'])
        with self.assertRaises(ValidationError):
            self.store.change_many(jobs,'SUBMITTING',allowed={'WAITING_SIGNATURE','BLOCKED'})
        self.assertEqual(self.store.get(self.ids[0])['status'],'WAITING_SIGNATURE')
        self.assertEqual(self.store.get(self.ids[1])['status'],'CANCELLED_LOCAL')
    def test_restart_during_batch_post_recovers_every_member_as_unknown(self):
        self.store.change_many(self.store.get_many(self.ids),'SUBMITTING',allowed={'WAITING_SIGNATURE'})
        recovered=Store(self.path)
        self.assertEqual({j['status'] for j in recovered.get_many(self.ids)},{'UNKNOWN'})
    def test_shared_document_does_not_hide_an_individual_composition_mismatch(self):
        self.engine.submit_many(self.ids,self.api,self.signer)
        self.api.info={k:v for j in self.jobs for k,v in infos(j,True).items()}
        self.api.info[self.jobs[1]['setCode']]['child']=self.jobs[1]['children'][:-1]
        self.api.doc_status='CHECKED_OK';self.api.doc_calls=0;self.api.code_calls=[]
        result=self.engine.refresh_many(self.ids,self.api)
        self.assertEqual([j['status'] for j in result],['VERIFIED','CHECK_REQUIRED'])
        self.assertEqual(self.api.doc_calls,1);self.assertEqual(len(self.api.code_calls),1)
        self.assertEqual({j['documentId'] for j in result},{self.api.document})
    def test_unknown_batch_reconciles_read_only_without_a_document_id(self):
        self.store.change_many(self.store.get_many(self.ids),'UNKNOWN')
        self.api.info={k:v for j in self.jobs for k,v in infos(j,True).items()}
        result=self.engine.refresh_many(self.ids,self.api)
        self.assertEqual({j['status'] for j in result},{'VERIFIED'})
        self.assertTrue(all(j['documentId'] is None for j in result));self.assertEqual(self.api.sent,[])
    def test_existing_database_backup_preserves_jobs_and_reservations(self):
        self.engine.submit_many(self.ids,self.api,self.signer)
        copy_path=Path(self.temp.name)/'backup.sqlite3';self.store.backup(copy_path)
        restored=Store(copy_path)
        self.assertEqual(restored.get_many(self.ids),self.store.get_many(self.ids))
        duplicated=copy.deepcopy(self.jobs[0]);duplicated['id']=str(uuid.uuid4())
        with self.assertRaises(ValidationError):restored.enqueue(duplicated)

if __name__=='__main__':unittest.main()
