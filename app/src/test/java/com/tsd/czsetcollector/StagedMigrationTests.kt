package com.tsd.czsetcollector

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class StagedMigrationTests {
    private val profile=OrganizationProfile("7707083893",title="Профиль",template=Template("04607010740016",mapOf("04607010740016" to 1)))
    private val source=ProfileData(listOf(profile),0)
    private val job=Job(inn=profile.inn,template=profile.template,setCode="010460701074001621SETCODE000001",
        children=mutableListOf("010460701074001621CHILD00000001"),status="VERIFIED",uploaded=true,documentId="verified-doc")
    private val queue=Snapshot(jobs=mutableListOf(job))

    @Test fun bothSourcesMustValidateBeforeProducingAnyTransferPlan() {
        val profiles=ProfileBackup.encode(source)
        val damaged=JsonParser.parseString(QueueBackup.encode(queue)).asJsonObject.apply{addProperty("sha256","0".repeat(64))}.toString()
        val existing=ProfileData(listOf(profile.copy(title="Existing")),0)
        try{StagedMigration.prepare(existing,Snapshot(),profiles,damaged);fail("Expected rejection")}catch(_:IllegalArgumentException){}
        assertEquals("Existing",existing.profiles.single().title)
        assertTrue(job.uploaded);assertEquals("VERIFIED",job.status)
    }

    @Test fun identicalQueueAllowsRetryAndAChangedDeliveryStateRefusesIt() {
        val plan=StagedMigration.prepare(source,queue,ProfileBackup.encode(source),QueueBackup.encode(queue))
        val result=JsonParser.parseString(StagedMigration.verifiedResult(plan,plan.merge.data,queue)).asJsonObject
        assertTrue(result["verified"].asBoolean)
        assertEquals(1,result["profileCount"].asInt);assertEquals(1,result["jobCount"].asInt)
        assertEquals(BackupJson.sha256(BackupJson.unwrap(ProfileBackup.encode(source),ProfileBackup.FORMAT)),result["profilesSha256"].asString)
        val changed=Snapshot(jobs=mutableListOf(job.copy(status="SUBMITTED")))
        try{StagedMigration.prepare(source,changed,ProfileBackup.encode(source),QueueBackup.encode(queue));fail("Expected refusal")}catch(_:IllegalArgumentException){}
    }

    @Test fun resultCannotClaimSuccessUntilBothReadBackValuesMatch() {
        val plan=StagedMigration.prepare(source,Snapshot(),ProfileBackup.encode(source),QueueBackup.encode(queue))
        try{StagedMigration.verifiedResult(plan,source,Snapshot());fail("Expected queue verification failure")}catch(_:IllegalArgumentException){}
        try{StagedMigration.verifiedResult(plan,source.copy(selectedIndex=1),queue);fail("Expected profiles verification failure")}catch(_:IllegalArgumentException){}
    }
}
