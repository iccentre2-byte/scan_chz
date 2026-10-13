"""Read-only reconciliation must never sign or submit an already formed set."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from test_core import job, infos, FakeApi, FakeSigner
from core import Store, Engine, ApiError, ValidationError

class ReconcileTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.store=Store(Path(self.temp.name)/'queue.sqlite3')
        self.job=self.store.enqueue(job())
        self.engine=Engine(self.store);self.api=FakeApi(self.job)
    def tearDown(self): self.temp.cleanup()
    def test_waiting_job_formed_elsewhere_is_reconciled_without_document_post(self):
        self.api.complete=True
        result=self.engine.refresh(self.job['id'],self.api)
        self.assertEqual(result['status'],'VERIFIED');self.assertIsNone(result['documentId'])
        self.assertEqual(self.api.sent,[])
        with self.assertRaises(ValidationError): self.engine.submit(result['id'],self.api,FakeSigner())
    def test_not_formed_waiting_and_blocked_keep_their_previous_state_and_reason(self):
        for state in ('WAITING_SIGNATURE','BLOCKED'):
            self.store.change(self.job['id'],state,'original reason')
            before=self.store.get(self.job['id'])
            self.assertEqual(self.engine.refresh(self.job['id'],self.api),before)
        self.assertEqual(self.api.sent,[])
    def test_formed_mismatched_composition_is_protected_from_resubmission(self):
        information=infos(self.job,True)
        information[self.job['setCode']]['child']=self.job['children'][:-1]
        with patch.object(self.api,'codes',return_value=information):
            result=self.engine.refresh(self.job['id'],self.api)
        self.assertEqual(result['status'],'CHECK_REQUIRED')
        with self.assertRaises(ValidationError):self.engine.submit(result['id'],self.api,FakeSigner())
        self.assertEqual(self.api.sent,[])
    def test_authorization_error_is_reported_without_changing_waiting_job(self):
        before=self.store.get(self.job['id'])
        with patch.object(self.api,'codes',side_effect=ApiError('expired',401)):
            with self.assertRaises(ApiError): self.engine.refresh(self.job['id'],self.api)
        self.assertEqual(self.store.get(self.job['id']),before)
    def test_expired_session_during_composition_check_preserves_submitted_record(self):
        self.engine.submit(self.job['id'],self.api,FakeSigner())
        self.api.doc_status='CHECKED_OK'
        before=self.store.get(self.job['id'])
        with patch.object(self.api,'codes',side_effect=ApiError('expired',403)):
            with self.assertRaises(ApiError):self.engine.refresh(self.job['id'],self.api)
        self.assertEqual(self.store.get(self.job['id']),before)
    def test_canceled_jobs_are_not_reconciled(self):
        self.store.cancel_unsent(self.job['id'],self.job['payloadHash'])
        with patch.object(self.api,'codes',side_effect=AssertionError('No request expected')):
            self.assertEqual(self.engine.refresh(self.job['id'],self.api)['status'],'CANCELLED_LOCAL')

if __name__=='__main__':unittest.main()
