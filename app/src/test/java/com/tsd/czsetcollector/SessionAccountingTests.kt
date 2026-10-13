package com.tsd.czsetcollector

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SessionAccountingTests {
    private val gson=Gson()
    private val gtin="04607010740016"
    private val childGtin="04607010740023"
    private val profile=OrganizationProfile("7707083893",title="Первый профиль",template=Template(gtin,mapOf(childGtin to 1)))
    private val other=profile.copy(inn="500100732259",title="Другой профиль")
    private val now=1700000000000L
    private fun code(gtin:String,serial:Int)="01${gtin}21"+serial.toString().padStart(13,'0')
    private fun job(index:Int,p:OrganizationProfile=profile)=Job(inn=p.inn,environment=p.environment,template=p.template,
        setCode=code(gtin,index),children=mutableListOf(code(childGtin,100+index)),status="VERIFIED",uploaded=true,documentId="doc-$index")
    private fun legacy()=Snapshot(jobs=(1..7).map{job(it,if(it%2==0)other else profile).let{j->if(it==7)j.copy(status="SUBMITTED")else j}}.toMutableList())
    private fun refused(block:()->Unit) {try{block();fail("Expected refusal")}catch(_:IllegalArgumentException){}}
    private fun collected(before:Snapshot,p:OrganizationProfile=profile,index:Int=20):Snapshot {
        val opened=CollectionScan.accept(before,p,code(gtin,index)){true}!!.snapshot
        return CollectionScan.accept(opened,p,code(childGtin,100+index)){true}!!.snapshot
    }

    @Test fun legacyBackupRoundtripDoesNotCreateSessionsDatesOrChangeAnyOfSevenJobs() {
        val original=legacy();val payload=gson.toJson(original)
        val restored=QueueBackup.prepareImport(Snapshot(),QueueBackup.encode(original))
        assertEquals(original,restored);assertEquals(payload,gson.toJson(restored));assertFalse(payload.contains("sessions"))
        assertNull(restored.sessions);assertEquals(0,QueueViews.select(restored,QueueView.CURRENT).jobs.size)
        assertEquals(7,QueueViews.select(restored,QueueView.ALL).jobs.size)
        val archive=QueueViews.select(restored,QueueView.HISTORY,SessionAccounting.LEGACY)
        assertEquals(7,archive.jobs.size);assertEquals(2,archive.jobs.map{it.inn}.distinct().size)
        assertTrue(archive.title.contains("Даты сборки неизвестны"));assertTrue(QueueViews.options(restored).single().label.contains("даты неизвестны"))
        assertEquals(6,archive.jobs.count{it.status=="VERIFIED"});assertEquals("SUBMITTED",archive.jobs.last().status)
        assertEquals(payload,gson.toJson(original))
    }
    @Test fun newStartLeavesAllSevenInArchiveAndCurrentSessionStartsAtZero() {
        val before=legacy();val jobsJson=gson.toJson(before.jobs)
        val next=SessionAccounting.begin(before,profile,now);val ledger=next.sessions!!
        assertNull(before.sessions);assertEquals(jobsJson,gson.toJson(next.jobs))
        assertEquals(now,SessionAccounting.active(next)?.startedAtMs)
        assertEquals(setOf(SessionAccounting.LEGACY),ledger.jobSessions.values.toSet())
        assertEquals(0,QueueViews.select(next,QueueView.CURRENT).jobs.size)
        assertEquals(7,QueueViews.select(next,QueueView.ALL).jobs.size)
        assertEquals(7,QueueViews.select(next,QueueView.HISTORY,SessionAccounting.LEGACY).jobs.size)
        assertEquals(next,QueueBackup.decode(QueueBackup.encode(next)))
    }
    @Test fun jobAndSessionMappingAreSavedTogetherAndSurviveOpeningAndCompletionCopies() {
        val before=SessionAccounting.begin(legacy(),profile,now);val beforeJson=gson.toJson(before);var writes=0
        val opened=CollectionScan.accept(before,profile,code(gtin,20)){next->
            writes++;assertEquals(SessionAccounting.active(next)?.id,next.sessions?.jobSessions?.get(next.current?.id))
            assertEquals(next,QueueBackup.decode(QueueBackup.encode(next)));true
        }!!.snapshot
        assertEquals(1,writes);assertEquals(beforeJson,gson.toJson(before))
        val id=opened.current!!.id;val sessionId=opened.sessions!!.activeSessionId
        val completed=CollectionScan.accept(opened,profile,code(childGtin,120)){next->
            writes++;assertNull(next.current);assertEquals(sessionId,next.sessions?.jobSessions?.get(id));true
        }!!.snapshot
        assertEquals(2,writes);assertEquals(1,QueueViews.select(completed,QueueView.CURRENT).jobs.size)
        assertEquals(8,QueueViews.select(completed,QueueView.ALL).jobs.size)
        assertEquals(before.jobs,completed.jobs.take(7));assertEquals("SUBMITTED",completed.jobs[6].status)
        assertEquals(completed,QueueBackup.decode(QueueBackup.encode(completed)))
    }
    @Test fun failedSaveAndDuplicateCannotAssignArchivedJobsToNewSession() {
        val before=SessionAccounting.begin(legacy(),profile,now);val beforeJson=gson.toJson(before)
        assertNull(CollectionScan.accept(before,profile,code(gtin,20)){false});assertEquals(beforeJson,gson.toJson(before))
        val duplicate=CollectionScan.accept(before,profile,before.jobs.first().setCode){throw AssertionError("Duplicate wrote metadata")}!!
        assertSame(before,duplicate.snapshot);assertEquals(ScanSignal.DUPLICATE,duplicate.signal);assertEquals(beforeJson,gson.toJson(before))
    }
    @Test fun restartResumesExactIdProfileAndTimestampWithoutCreatingAnotherSession() {
        val opened=CollectionScan.accept(SessionAccounting.begin(legacy(),other,now),other,code(gtin,20)){true}!!.snapshot
        val loaded=QueueBackup.decode(QueueBackup.encode(opened));val active=SessionAccounting.active(loaded)!!
        val work=WorkSession();work.enter(active.profile,loaded.current)
        assertEquals(other,work.profile);assertEquals(now,active.startedAtMs)
        assertEquals(opened.sessions!!.activeSessionId,active.id);assertEquals(1,loaded.sessions!!.entries.size);assertEquals(opened,loaded)
    }
    @Test fun exitAndAnotherExplicitStartProduceDifferentIdsAndRetainOldSessionHistory() {
        val first=collected(SessionAccounting.begin(legacy(),profile,now));val id=first.sessions!!.activeSessionId!!
        val closed=SessionAccounting.finish(first,now+60000)
        assertNull(SessionAccounting.active(closed));assertEquals(now+60000,closed.sessions!!.entries.single().endedAtMs)
        val second=SessionAccounting.begin(closed,other,now+120000)
        assertNotEquals(id,SessionAccounting.active(second)?.id);assertEquals(2,second.sessions!!.entries.size)
        assertEquals(0,QueueViews.select(second,QueueView.CURRENT).jobs.size)
        assertEquals(1,QueueViews.select(second,QueueView.HISTORY,id).jobs.size);assertEquals(8,QueueViews.select(second,QueueView.ALL).jobs.size)
        assertEquals(second,QueueBackup.decode(QueueBackup.encode(second)))
    }
    @Test fun viewsAcrossProfilesNeverRetargetWorkingProfileOrMutateQueue() {
        val first=collected(SessionAccounting.begin(Snapshot(),profile,now));val closed=SessionAccounting.finish(first,now+100)
        val second=collected(SessionAccounting.begin(closed,other,now+200),other,21)
        val work=WorkSession();work.enter(other,null);val frozen=work.profile;val raw=gson.toJson(second)
        assertEquals(2,QueueViews.select(second,QueueView.ALL).jobs.size)
        assertEquals(other.inn,QueueViews.select(second,QueueView.CURRENT).jobs.single().inn)
        assertEquals(profile.inn,QueueViews.select(second,QueueView.HISTORY,first.sessions!!.activeSessionId).jobs.single().inn)
        assertEquals(frozen,work.profile);assertEquals(raw,gson.toJson(second))
    }
    @Test fun emptyClosedSessionsAreHistoryAndCannotBeOverwrittenByImportOrStaging() {
        val existing=SessionAccounting.finish(SessionAccounting.begin(Snapshot(),profile,now),now+100);val incoming=QueueBackup.encode(legacy())
        refused{QueueBackup.prepareImport(existing,incoming)}
        val profiles=ProfileData(listOf(profile),0)
        refused{StagedMigration.prepare(profiles,existing,ProfileBackup.encode(profiles),incoming)}
        assertEquals(1,existing.sessions!!.entries.size)
    }
    @Test fun strictMetadataRefusesUnknownKeysBadDatesMissingMappingsAndWrongOwners() {
        val good=collected(SessionAccounting.begin(legacy(),profile,now));val payload=BackupJson.unwrap(QueueBackup.encode(good),QueueBackup.FORMAT)
        val cases=listOf<(JsonObject)->Unit>(
            {it.addProperty("pcKey","secret")},
            {it["entries"].asJsonArray[0].asJsonObject["profile"].asJsonObject.addProperty("pcKey","secret")},
            {it["entries"].asJsonArray[0].asJsonObject.addProperty("startedAtMs","$now")},
            {it["entries"].asJsonArray[0].asJsonObject.addProperty("startedAtMs",1.5)},
            {it["entries"].asJsonArray[0].asJsonObject.addProperty("endedAtMs",now)},
            {it["entries"].asJsonArray.add(it["entries"].asJsonArray[0].deepCopy())},
            {it["jobSessions"].asJsonObject.remove(good.jobs.first().id)},
            {it["jobSessions"].asJsonObject.addProperty(good.jobs.last().id,java.util.UUID.randomUUID().toString())},
            {it["entries"].asJsonArray[0].asJsonObject["profile"].asJsonObject.addProperty("inn",other.inn)}
        )
        for(change in cases){val root=JsonParser.parseString(payload).asJsonObject;change(root["sessions"].asJsonObject);refused{QueueBackup.decode(BackupJson.wrap(QueueBackup.FORMAT,root.toString()))}}
        assertEquals(8,good.jobs.size);assertEquals("SUBMITTED",good.jobs[6].status)
    }
    @Test fun legacyCurrentCanFinishButNewJobRequiresExplicitSessionStart() {
        val old=job(20).copy(children=mutableListOf(),status="COLLECTING",uploaded=false,documentId=null);val before=Snapshot(old,legacy().jobs)
        refused{SessionAccounting.begin(before,profile,now)}
        val completed=CollectionScan.accept(before,profile,code(childGtin,120)){true}!!.snapshot
        assertNull(completed.sessions);assertEquals(8,QueueViews.select(completed,QueueView.HISTORY,SessionAccounting.LEGACY).jobs.size)
        refused{CollectionScan.accept(completed,profile,code(gtin,21)){throw AssertionError("Legacy new job was written")}}
        assertFalse(SessionAccounting.ready(completed));assertEquals(8,completed.jobs.size)
    }
    @Test fun resettingCurrentRemovesOnlyItsMappingAndKeepsHistoryAndActiveSession() {
        val opened=CollectionScan.accept(SessionAccounting.begin(legacy(),profile,now),profile,code(gtin,20)){true}!!.snapshot;val currentId=opened.current!!.id
        val reset=SessionAccounting.discardCurrent(opened)
        assertNull(reset.current);assertFalse(reset.sessions!!.jobSessions.containsKey(currentId))
        assertEquals(opened.jobs,reset.jobs);assertEquals(opened.sessions!!.activeSessionId,reset.sessions.activeSessionId)
        assertEquals(reset,QueueBackup.decode(QueueBackup.encode(reset)))
        val corrupt=JsonParser.parseString(BackupJson.unwrap(QueueBackup.encode(opened),QueueBackup.FORMAT)).asJsonObject
        corrupt["sessions"].asJsonObject["jobSessions"].asJsonObject.addProperty(currentId,SessionAccounting.LEGACY)
        refused{QueueBackup.parsePayload(corrupt.toString())}
    }
    @Test fun sessionMetadataNeverChangesTheJobPayloadSentToPc() {
        val before=legacy();val jobs=before.jobs.associate{it.id to gson.toJson(it)}
        val roundtrip=QueueBackup.decode(QueueBackup.encode(SessionAccounting.begin(before,profile,now)))
        assertEquals(jobs,roundtrip.jobs.associate{it.id to gson.toJson(it)});assertFalse(gson.toJson(roundtrip.jobs.first()).contains("sessions"))
        assertTrue(Labels.status("SUBMITTED").contains("результат ещё не проверен"));assertTrue(Labels.status("PROCESSING").contains("Последняя проверка"))
    }
}
