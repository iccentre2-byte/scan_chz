package com.tsd.czsetcollector

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class PcStatusSyncTests {
    private val gson=Gson()
    private val gtin="04607010740016"
    private val profile=OrganizationProfile("7707083893",template=Template(gtin,mapOf(gtin to 1)))
    private fun code(serial:Int)="01${gtin}21"+serial.toString().padStart(13,'0')
    private fun job(index:Int,other:Boolean=false)=Job(inn=if(other)"500100732259" else profile.inn,
        template=profile.template,setCode=code(index+1),children=mutableListOf(code(index+10001)),
        status="PROCESSING",uploaded=true,documentId="document-$index")
    private fun verified(job:Job)=job.copy(status="VERIFIED",detail="Состав проверен")
    private fun <T> run(block:suspend()->T):T {
        var result:Result<T>?=null
        block.startCoroutine(object:Continuation<T>{
            override val context=EmptyCoroutineContext
            override fun resumeWith(value:Result<T>){result=value}
        })
        return requireNotNull(result){"Test coroutine suspended unexpectedly"}.getOrThrow()
    }

    @Test fun persistentFirstHundredAcrossOtherProfileCannotStarveNext180Jobs() {
        val prefix=(0 until 100).map{job(it,true)}
        val target=(100 until 280).map{job(it)}
        var saved=Snapshot(jobs=(prefix+target).toMutableList())
        val reader=StatusReadRotation();val visited=mutableSetOf<String>()
        repeat(3){
            val batch=reader.batch(saved.jobs);assertTrue(batch.size<=100)
            val report=run{reader.readBatch(batch,read={original->
                visited.add(original.id)
                if(original.inn==profile.inn)verified(original)else original
            },apply={original,reply->
                saved=PcReplyCommit.apply(saved,original,reply){true}!!;true
            },shouldStop={false})}
            assertNull(report.blockingFailure);assertEquals(0,report.skipped)
        }
        assertEquals(280,visited.size)
        assertEquals(180,saved.jobs.count{it.inn==profile.inn && it.status=="VERIFIED"})
        assertTrue(saved.jobs.take(100).all{it.status=="PROCESSING"})
        assertTrue(target.all{it.status=="PROCESSING"}) // Successful writes never mutate the original request objects.
    }
    @Test fun cursorStillAdvancesWhenAll180ResponsesRemainPending() {
        val jobs=(0 until 180).map{job(it,it%2==0)};val reader=StatusReadRotation();val visited=mutableSetOf<String>()
        repeat(2){run{reader.readBatch(reader.batch(jobs),read={visited.add(it.id);it},apply={_,_->true},shouldStop={false})}}
        assertEquals(180,visited.size)
        assertTrue(jobs.all{it.status=="PROCESSING"})
    }
    @Test fun missingAndMalformedJobsDoNotStopOtherJobsOrEraseEarlierSuccess() {
        var saved=Snapshot(jobs=(0 until 180).map{job(it)}.toMutableList())
        val original=saved.jobs.toList();val reader=StatusReadRotation();var checked=0;var skipped=0
        repeat(2){
            val report=run{reader.readBatch(reader.batch(saved.jobs),read={job->when(job.id){
                original[1].id->throw IllegalStateException("not found",PcHttpException(404,"not found"))
                original[2].id->throw PcResponseException("invalid JSON")
                original[3].id->verified(job).copy(setCode=code(999))
                else->verified(job)
            }},apply={job,reply->saved=PcReplyCommit.apply(saved,job,reply){true}!!;true},shouldStop={false})}
            assertNull(report.blockingFailure);checked+=report.checked;skipped+=report.skipped
        }
        assertEquals(177,saved.jobs.count{it.status=="VERIFIED"})
        assertEquals(177,checked);assertTrue(skipped>=3)
        assertEquals("VERIFIED",saved.jobs.first().status)
        for(index in 1..3)assertEquals("PROCESSING",saved.jobs[index].status)
        assertTrue(original.all{it.status=="PROCESSING"})
    }
    @Test fun connectionTlsAndAuthorizationFailuresStopAfterOneRead() {
        val jobs=(0 until 180).map{job(it)}
        val failures=listOf(
            IllegalStateException("offline",IOException("connection reset")),
            IllegalStateException("timeout",SocketTimeoutException("read timeout")),
            IllegalStateException("certificate",SSLHandshakeException("pin mismatch")),
            IllegalStateException("key",PcHttpException(401,"wrong key")),
            IllegalStateException("key",PcHttpException(403,"wrong key")),
            IllegalStateException("helper failure",PcHttpException(500,"server failure")))
        for(error in failures){
            val reader=StatusReadRotation();var attempts=0;var applies=0
            val report=run{reader.readBatch(reader.batch(jobs),read={attempts++;throw error},apply={_,_->applies++;true},shouldStop={false})}
            assertSame(error,report.blockingFailure);assertEquals(1,attempts);assertEquals(0,applies);assertEquals(0,report.skipped)
        }
    }
    @Test fun cancellationPropagatesAndKeepsAlreadySavedReplies() {
        var saved=Snapshot(jobs=(0 until 10).map{job(it)}.toMutableList());val reader=StatusReadRotation();var attempts=0
        val error=CancellationException("cancelled")
        try{run{reader.readBatch(reader.batch(saved.jobs),read={
            attempts++;if(attempts==3)throw error;verified(it)
        },apply={job,reply->saved=PcReplyCommit.apply(saved,job,reply){true}!!;true},shouldStop={false})};fail("Expected cancellation")}
        catch(actual:CancellationException){assertSame(error,actual)}
        assertEquals(3,attempts);assertEquals(2,saved.jobs.count{it.status=="VERIFIED"})
    }
    @Test fun failedStorageStopsBatchAndDoesNotPublishFailedReply() {
        var saved=Snapshot(jobs=(0 until 10).map{job(it)}.toMutableList());val original=saved.jobs.toList()
        val reader=StatusReadRotation();var attempts=0;var writes=0
        val report=run{reader.readBatch(reader.batch(saved.jobs),read={attempts++;verified(it)},apply={job,reply->
            val next=PcReplyCommit.apply(saved,job,reply){++writes<3}
            if(next==null)false else{saved=next;true}
        },shouldStop={false})}
        assertTrue(report.stopped);assertEquals(3,attempts);assertEquals(2,report.checked)
        assertEquals(2,saved.jobs.count{it.status=="VERIFIED"});assertEquals("PROCESSING",saved.jobs[2].status)
        assertTrue(original.all{it.status=="PROCESSING"})
    }
    @Test fun maintenanceStopBeforeReadOrBeforeApplyDoesNotWriteAReply() {
        val jobs=(0 until 10).map{job(it)};val reader=StatusReadRotation();var attempts=0;var applies=0
        var stop=true
        val before=run{reader.readBatch(reader.batch(jobs),read={attempts++;it},apply={_,_->applies++;true},shouldStop={stop})}
        assertTrue(before.stopped);assertEquals(0,attempts)
        stop=false
        val after=run{reader.readBatch(reader.batch(jobs),read={attempts++;stop=true;verified(it)},apply={_,_->applies++;true},shouldStop={stop})}
        assertTrue(after.stopped);assertEquals(1,attempts);assertEquals(0,applies)
    }
    @Test fun replyCommitPreservesLatestSessionCurrentJobAndEveryImmutableJobField() {
        val requested=job(0)
        val begun=SessionAccounting.begin(Snapshot(jobs=mutableListOf(requested)),profile,1700000000000)
        val latest=CollectionScan.accept(begun,profile,code(900)){true}!!.snapshot
        val oldJson=gson.toJson(latest);var written:Snapshot?=null
        val result=PcReplyCommit.apply(latest,requested,verified(requested)){written=it;true}!!
        assertSame(written,result);assertSame(latest.current,result.current);assertSame(latest.sessions,result.sessions)
        assertEquals(oldJson,gson.toJson(latest))
        assertEquals(requested.copy(status="VERIFIED",detail="Состав проверен"),result.jobs.single())
        assertEquals(result,QueueBackup.decode(QueueBackup.encode(result)))
    }
    @Test fun unchangedReplyDoesNotWriteAndWrongPayloadCannotMutateQueue() {
        val original=job(0);val snapshot=Snapshot(jobs=mutableListOf(original));val before=gson.toJson(snapshot)
        assertSame(snapshot,PcReplyCommit.apply(snapshot,original,original){throw AssertionError("Unchanged reply wrote")})
        val bad=listOf(verified(original).copy(inn="500100732259"),verified(original).copy(children=mutableListOf(code(2000))),
            verified(original).copy(status="LOCAL_READY"))
        for(reply in bad){
            try{PcReplyCommit.apply(snapshot,original,reply){throw AssertionError("Invalid reply wrote")};fail("Expected refusal")}
            catch(_:IllegalArgumentException){}
        }
        assertEquals(before,gson.toJson(snapshot))
    }
    @Test fun terminalAndNeverUploadedJobsAreNotPolledAndCursorHandlesTheirRemovalFromCandidates() {
        val old=job(0);val jobs=mutableListOf(old,job(1),job(2))
        val reader=StatusReadRotation(1)
        run{reader.readBatch(reader.batch(jobs),read={it},apply={_,_->true},shouldStop={false})}
        jobs[0]=old.copy(status="VERIFIED")
        assertEquals(jobs[1].id,reader.batch(jobs).single().id)
        jobs[1]=jobs[1].copy(status="CANCELLED_LOCAL")
        jobs[2]=jobs[2].copy(uploaded=false,status="LOCAL_READY")
        assertTrue(reader.batch(jobs).isEmpty())
    }
}
