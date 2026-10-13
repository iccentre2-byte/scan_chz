"""Session diagnostics and explicit read-only re-login. Synthetic queue; no CHZ/CryptoPro."""
import json
from pathlib import Path
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'companion_pc'))
from core import ApiError, ValidationError
from server import State, SESSION_TTL
from test_core import job, cis


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.state=State(Path(self.tmp.name),{'deviceKey':'private-device-key'},'192.168.1.10')
        self.group={'inn':'7707083893','environment':'sandbox','pg':'grocery'}
        self.key=('sandbox',self.group['inn'],'A'*40)
    def tearDown(self):
        self.state.worker.shutdown(wait=True);self.tmp.cleanup()
    def enqueue(self,n,status='WAITING_SIGNATURE'):
        payload=job();payload['setCode']=cis(payload['template']['setGtin'],n)
        payload['children']=[cis(code[2:16],n*100+i) for i,code in enumerate(payload['children'])]
        result=self.state.store.enqueue(payload)
        if status!='WAITING_SIGNATURE':result=self.state.store.change(result['id'],status,document_id='doc-'+str(n) if status in {'SUBMITTED','VERIFIED'} else None)
        return result
    def run_immediately(self,action,payload):
        with patch.object(self.state.worker,'submit',side_effect=lambda fn,*args:fn(*args)):
            result=self.state.schedule(action,payload)
        return self.state.public_operations()[result['operationId']]

    def test_restart_and_expired_session_are_visible_and_never_auto_authenticate(self):
        pending=self.enqueue(1,'SUBMITTED')
        with patch('server.TrueApi') as api,patch('server.WindowsSigner') as signer:
            self.state.poll_once()
            missing=self.state.public_sync()[0]
            self.assertEqual(missing['session'],'missing');self.assertTrue(missing['requiresLogin'])
            self.assertIsNone(missing['lastAttempt']);self.assertEqual(missing['pending'],1)
            self.state.sessions[self.key]=(Mock(),time.time()-SESSION_TTL-1)
            self.state.poll_once()
            self.assertEqual(self.state.public_sync()[0]['session'],'expired')
            self.assertEqual(self.state.store.get(pending['id'])['status'],'SUBMITTED')
            api.assert_not_called();signer.assert_not_called()

    def test_login_check_requires_confirmation_and_only_reads_all_pending_group_jobs(self):
        pending=[self.enqueue(1),self.enqueue(2,'BLOCKED'),self.enqueue(3,'SUBMITTED')]
        self.enqueue(4,'VERIFIED');self.enqueue(5,'CANCELLED_LOCAL')
        self.state.engine=Mock();self.state.engine.refresh_many.side_effect=lambda ids,api:self.state.store.get_many(ids)
        fake=Mock();fake.token='secret-session-token'
        payload={'confirmed':True,'thumbprint':'A'*40,'group':self.group}
        with patch('server.TrueApi',return_value=fake) as factory,patch('server.WindowsSigner',return_value=SimpleNamespace(thumbprint='A'*40)) as signer:
            with self.assertRaises(ValidationError):self.state.schedule('login-check',dict(payload,confirmed=False))
            factory.assert_not_called();signer.assert_not_called()
            operation=self.run_immediately('login-check',payload)
            self.assertEqual(operation['state'],'DONE');self.assertEqual(operation['done'],3)
            fake.authenticate.assert_called_once()
            ids=self.state.engine.refresh_many.call_args.args[0]
            self.assertEqual(set(ids),{j['id'] for j in pending})
            self.state.engine.submit_many.assert_not_called();fake.call.assert_not_called()
            sync=self.state.public_sync()[0]
            self.assertEqual(sync['session'],'active');self.assertFalse(sync['requiresLogin'])
            self.assertIsNotNone(sync['lastSuccess'])
            self.state.poll_once()
            fake.authenticate.assert_called_once();factory.assert_called_once()
            self.assertEqual(self.state.engine.refresh_many.call_count,2)
            self.assertNotIn(fake.token,json.dumps(self.state.public_sync()))

    def test_authorization_expiry_surfaces_requires_login_without_changing_queue(self):
        pending=self.enqueue(1,'SUBMITTED');api=Mock();api.token='private-session-token'
        self.state.sessions[self.key]=(api,time.time())
        self.state.engine=Mock();self.state.engine.refresh_many.side_effect=ApiError('expired '+api.token,401)
        self.state.poll_once()
        sync=self.state.public_sync()[0]
        self.assertEqual(sync['session'],'expired');self.assertTrue(sync['requiresLogin'])
        self.assertIsNotNone(sync['lastAttempt']);self.assertIn('expired',sync['error'])
        self.assertNotIn(api.token,sync['error']);self.assertEqual(self.state.store.get(pending['id'])['status'],'SUBMITTED')
        self.state.poll_once();self.assertEqual(self.state.engine.refresh_many.call_count,1)

    def test_read_failure_is_visible_and_next_poll_can_recover_without_authentication(self):
        self.enqueue(1);api=Mock();api.token='private-session-token';self.state.sessions[self.key]=(api,time.time())
        self.state.engine=Mock();self.state.engine.refresh_many.side_effect=[ApiError('temporary read failure',504),[]]
        self.state.poll_once();sync=self.state.public_sync()[0]
        self.assertEqual(sync['session'],'active');self.assertFalse(sync['requiresLogin'])
        self.assertIn('temporary',sync['error']);self.assertIsNone(sync['lastSuccess'])
        self.state.poll_once();sync=self.state.public_sync()[0]
        self.assertEqual(sync['error'],'');self.assertIsNotNone(sync['lastSuccess'])
        api.authenticate.assert_not_called();self.state.engine.submit_many.assert_not_called()

    def test_failed_manual_login_releases_operation_and_reports_error(self):
        pending=self.enqueue(1);fake=Mock();fake.token='';fake.authenticate.side_effect=ApiError('login denied',403)
        with patch('server.TrueApi',return_value=fake),patch('server.WindowsSigner',return_value=SimpleNamespace(thumbprint='A'*40)):
            op=self.run_immediately('login-check',{'confirmed':True,'thumbprint':'A'*40,'group':self.group})
        self.assertEqual(op['state'],'ERROR');self.assertIn('login denied',op['error'])
        self.assertTrue(self.state.public_sync()[0]['requiresLogin'])
        self.assertEqual(self.state.store.get(pending['id'])['status'],'WAITING_SIGNATURE')
        self.assertFalse(any(o['state'] in ('RUNNING','QUEUED') for o in self.state.public_operations().values()))

    def test_confirmed_group_check_chunks_over_limit_without_signature_or_submission(self):
        self.enqueue(1);self.enqueue(2);self.enqueue(3)
        self.state.engine=Mock();self.state.engine.refresh_many.side_effect=lambda ids,api:self.state.store.get_many(ids)
        fake=Mock();fake.token='session-token'
        with patch('server.MAX_SELECTION',2),patch('server.TrueApi',return_value=fake),patch('server.WindowsSigner',return_value=SimpleNamespace(thumbprint='A'*40)):
            op=self.run_immediately('login-check',{'confirmed':True,'thumbprint':'A'*40,'group':self.group})
        self.assertEqual(op['done'],3);self.assertEqual(op['state'],'DONE')
        self.assertEqual([len(call.args[0]) for call in self.state.engine.refresh_many.call_args_list],[2,1])
        self.state.engine.submit_many.assert_not_called();fake.call.assert_not_called()

    def test_terminal_errors_are_not_reported_as_all_verified(self):
        self.enqueue(1,'REJECTED');self.enqueue(2,'CANCELLED_LOCAL');self.enqueue(3,'VERIFIED')
        sync=self.state.public_sync()[0]
        self.assertEqual(sync['pending'],0);self.assertEqual(sync['total'],3)
        self.assertEqual(sync['verified'],1);self.assertEqual(sync['failed'],1);self.assertEqual(sync['cancelled'],1)
        self.assertFalse(sync['allVerified']);self.assertFalse(sync['requiresLogin'])

    def test_successful_batch_submission_also_reports_completed_initial_status_read(self):
        pending=self.enqueue(1)
        self.state.engine=Mock();self.state.engine.submit_many.return_value=[pending]
        fake=Mock();fake.token='session-token'
        payload={'confirmed':True,'thumbprint':'A'*40,'items':[{'id':pending['id'],'hash':pending['payloadHash']}]}
        with patch('server.TrueApi',return_value=fake),patch('server.WindowsSigner',return_value=SimpleNamespace(thumbprint='A'*40)):
            op=self.run_immediately('submit',payload)
        self.assertEqual(op['state'],'DONE');self.assertIsNotNone(self.state.public_sync()[0]['lastSuccess'])
        self.state.engine.submit_many.assert_called_once()


if __name__=='__main__':unittest.main()
