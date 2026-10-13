package com.tsd.czsetcollector

import org.junit.Assert.*
import org.junit.Test

class CollectionScanTests {
    private val setGtin="04607010740016"
    private val unitGtin="04607010740023"
    private val template=Template(setGtin,mapOf(unitGtin to 2))
    private val profile=OrganizationProfile("7707083893",template=template)
    private fun code(gtin:String,serial:Int)="01${gtin}21"+serial.toString().padStart(13,'0')
    private val set=code(setGtin,1)
    private val unit=code(unitGtin,2)
    private val unit2=code(unitGtin,3)
    private fun current(children:MutableList<String> = mutableListOf())=Job(inn=profile.inn,template=template,setCode=set,children=children)

    @Test fun openingIsReportedOnlyAfterSuccessfulSaveAndInputRemainsUntouched() {
        val before=Snapshot();var writes=0
        val result=CollectionScan.accept(before,profile,set){next->
            writes++;assertNull(before.current);assertEquals(set,next.current?.setCode);true
        }!!
        assertEquals(1,writes);assertNull(before.current)
        assertEquals(ScanSignal.OPENED,result.signal);assertEquals(set,result.snapshot.current?.setCode)
        assertNull(CollectionScan.accept(before,profile,set){false})
        assertNull(before.current)
    }
    @Test fun completedResultRequiresSaveAndPreservesIdsAndDeliveryState() {
        val job=current(mutableListOf(unit));val sent=current().copy(setCode=code(setGtin,9),status="VERIFIED",uploaded=true,documentId="document-9")
        val before=Snapshot(job,mutableListOf(sent))
        assertNull(CollectionScan.accept(before,profile,unit2){false})
        assertEquals(listOf(unit),job.children);assertEquals("COLLECTING",job.status);assertSame(job,before.current)
        var saved:Snapshot?=null
        val result=CollectionScan.accept(before,profile,unit2){saved=it;true}!!
        assertSame(saved,result.snapshot);assertEquals(ScanSignal.COMPLETED,result.signal)
        assertNull(result.snapshot.current);assertEquals(2,result.snapshot.jobs.size)
        val completed=result.snapshot.jobs.last()
        assertEquals(job.id,completed.id);assertEquals("LOCAL_READY",completed.status)
        assertFalse(completed.uploaded);assertEquals(listOf(unit,unit2),completed.children)
        assertSame(sent,result.snapshot.jobs.first());assertTrue(sent.uploaded);assertEquals("document-9",sent.documentId);assertEquals("VERIFIED",sent.status)
        assertEquals("Набор собран",result.signal?.spoken)
    }
    @Test fun intermediateUnitIsSavedWithoutCompletionVoiceOrInputMutation() {
        val job=current();val before=Snapshot(job)
        val result=CollectionScan.accept(before,profile,unit){true}!!
        assertNull(result.signal);assertTrue(job.children.isEmpty())
        assertEquals(listOf(unit),result.snapshot.current?.children);assertTrue(result.snapshot.jobs.isEmpty())
    }
    @Test fun thrownWriteErrorCannotCompleteOrMutateTheCurrentSet() {
        val job=current(mutableListOf(unit));val before=Snapshot(job)
        try {CollectionScan.accept(before,profile,unit2){throw java.io.IOException("Disk error")};fail("Write error ignored")}
        catch(expected:java.io.IOException){}
        assertSame(job,before.current);assertEquals("COLLECTING",job.status)
        assertEquals(listOf(unit),job.children);assertTrue(before.jobs.isEmpty())
    }
    @Test fun currentSetAndCurrentUnitDuplicatesNeverWriteOrChangeCounts() {
        val before=Snapshot(current(mutableListOf(unit)))
        for(value in listOf(set,unit)) {
            val result=CollectionScan.accept(before,profile,value){throw AssertionError("Duplicate attempted a write")}!!
            assertEquals(ScanSignal.DUPLICATE,result.signal);assertSame(before,result.snapshot)
            assertEquals(listOf(unit),before.current?.children);assertTrue(before.jobs.isEmpty())
        }
    }
    @Test fun completedSetAndUnitDuplicatesAreRejectedAcrossOrganizationProfiles() {
        val queued=current(mutableListOf(unit,unit2)).copy(inn="500100732259",status="WAITING_SIGNATURE",uploaded=true,documentId="d1")
        val before=Snapshot(null,mutableListOf(queued))
        for(value in listOf(set,unit)) {
            val result=CollectionScan.accept(before,profile,value){throw AssertionError("Queued duplicate attempted a write")}!!
            assertEquals(ScanSignal.DUPLICATE,result.signal);assertSame(before,result.snapshot)
            assertEquals("WAITING_SIGNATURE",queued.status);assertTrue(queued.uploaded);assertEquals("d1",queued.documentId)
        }
        assertTrue(CollectionScan.accept(before,profile,set){false}!!.message.contains("уже собран"))
    }
    @Test fun hardwareEchoBurstIsSilentButIntentionalRescanAfterGapIsAccepted() {
        val echo=ScanEchoGuard()
        assertTrue(echo.accept(set,1000))
        assertFalse(echo.accept(set,1300));assertFalse(echo.accept(set,1900));assertFalse(echo.accept(set,2500))
        assertTrue(echo.accept(set,3200));assertTrue(echo.accept(unit,3201))
        assertFalse(echo.accept(set,3500)) // A delayed broadcast stays silent even after another code's keyboard event.
        assertFalse(echo.accept(unit,3900));assertTrue(echo.accept(unit,4600))
    }
    @Test fun wrongGtinAndExcessQuantityNeverWriteOrMutate() {
        val before=Snapshot(current(mutableListOf(unit,unit2)))
        fun rejected(value:String) {
            try {CollectionScan.accept(before,profile,value){throw AssertionError("Invalid scan attempted a write")};fail("Accepted invalid scan")}
            catch(expected:IllegalArgumentException){}
            assertEquals(listOf(unit,unit2),before.current?.children);assertTrue(before.jobs.isEmpty())
        }
        rejected(code(unitGtin,4));rejected(code("04607010740030",5))
    }
    @Test fun priorCancelExceptionAndEnvironmentSeparationRemainUnchanged() {
        val cancelled=current().copy(status="CANCELLED_LOCAL")
        val test=current().copy(environment="sandbox")
        for(job in listOf(cancelled,test)) {
            val result=CollectionScan.accept(Snapshot(null,mutableListOf(job)),profile,set){true}!!
            assertEquals(ScanSignal.OPENED,result.signal);assertSame(job,result.snapshot.jobs.single())
        }
    }
}
