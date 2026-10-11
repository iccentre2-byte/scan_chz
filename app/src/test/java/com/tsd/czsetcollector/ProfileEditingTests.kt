package com.tsd.czsetcollector

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProfileEditingTests {
    private val gtin="04607010740016"
    private val unit="01${gtin}21ABCDEFGHIJKLM"
    private val profile=OrganizationProfile("7707083893",title="Кириешки",template=Template(gtin,mapOf(gtin to 6)))
    private fun rejects(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}

    @Test fun gtinOnlyIsExtractedFromManualDataMatrixHriAndGs1Qr() {
        assertEquals(gtin,Codes.profileGtin(gtin))
        assertEquals(gtin,Codes.profileGtin("]d2"+unit+"\u001d93SIGNATURE"))
        assertEquals(gtin,Codes.profileGtin("(01)$gtin(21)ABCDEFGHIJKLM(93)SIGNATURE"))
        assertEquals(gtin,Codes.profileGtin("]Q3https://id.gs1.org/01/$gtin/21/ABCDEFGHIJKLM?17=271231"))
        assertEquals(gtin,Codes.profileGtin("01$gtin"))
        assertEquals(unit,Codes.normalize(unit+"\u001d93SIGNATURE")) // Collection contract is unchanged.
        rejects{Codes.normalize("01$gtin")}
    }
    @Test fun ambiguousWrongChecksumSsccAndNonGs1QrCannotBecomeGtin() {
        listOf("04607010740017","00"+"0".repeat(18),"GTIN=$gtin","https://example.test/?gtin=$gtin",
            "https://id.gs1.org/01/$gtin/01/$gtin","(01)${gtin}extra","01${gtin}arbitrary").forEach{raw->rejects{Codes.profileGtin(raw)}}
    }
    @Test fun rowsKeepQuantitiesAndCompositionValidationRemainsStrict() {
        assertEquals(mapOf(gtin to 6),ProfileEditing.composition(listOf(CompositionRow(unit,"6"))))
        rejects{ProfileEditing.composition(listOf(CompositionRow(gtin,"0")))}
        rejects{ProfileEditing.composition(listOf(CompositionRow(gtin,"1001")))}
        rejects{ProfileEditing.composition(listOf(CompositionRow(gtin,"1.5")))}
        rejects{ProfileEditing.composition(listOf(CompositionRow(gtin,"1"),CompositionRow(unit,"2")))}
        rejects{ProfileEditing.composition(listOf(CompositionRow(gtin,"999"),CompositionRow("04609144660000","2")))}
        rejects{ProfileEditing.composition(listOf(CompositionRow("","1")))}
    }
    @Test fun plusMinusRespectBoundsAndRejectUnclearNumericInput() {
        assertEquals(1,ProfileEditing.step("1",-1));assertEquals(1000,ProfileEditing.step("1000",1))
        assertEquals(7,ProfileEditing.step("6",1));assertEquals(5,ProfileEditing.step("6",-1))
        listOf("","0","1001","1.5",unit).forEach{raw->rejects{ProfileEditing.step(raw,1)}}
    }
    @Test fun newAndRenamedProfilesNeverReplaceAnotherSavedProfileByName() {
        val original=listOf(profile,profile.copy(title="Майонез"))
        rejects{ProfileEditing.saved(original,null,profile.copy(template=Template(gtin,mapOf(gtin to 2))))}
        rejects{ProfileEditing.saved(original,1,profile.copy(title="киРИЕШКИ"))}
        assertEquals(6,original[0].template.expected[gtin])
        val renamed=ProfileEditing.saved(original,0,profile.copy(title="Сухарики"))
        assertEquals("Сухарики",renamed.profiles[0].title);assertEquals(original[1],renamed.profiles[1])
        val title=ProfileEditing.availableTitle("Кириешки",original)
        val added=ProfileEditing.saved(original,null,profile.copy(title=title))
        assertEquals(3,added.profiles.size);assertEquals(original,added.profiles.take(2));assertEquals(2,added.selectedIndex)
    }
    @Test fun fullBackupResultRequiresBothVerifiedReadBacksAndPreservesQueueState() {
        val data=ProfileData(listOf(profile),0)
        val sent=Job(inn=profile.inn,template=Template(gtin,mapOf(gtin to 1)),setCode=unit,
            children=mutableListOf("01${gtin}21CHILD00000001"),status="VERIFIED",uploaded=true,documentId="doc-1")
        val queue=Snapshot(jobs=mutableListOf(sent))
        val profileJson=ProfileBackup.encode(data);val queueJson=QueueBackup.encode(queue)
        val requestId="backup-request-123"
        val result=JsonParser.parseString(CompleteBackup.verifiedResult(data,queue,profileJson,queueJson,requestId)).asJsonObject
        assertTrue(result["verified"].asBoolean);assertEquals(1,result["jobCount"].asInt)
        assertEquals(BackupJson.sha256(BackupJson.unwrap(queueJson,QueueBackup.FORMAT)),result["queueSha256"].asString)
        assertEquals(requestId,result["requestId"].asString)
        assertTrue(sent.uploaded);assertEquals("doc-1",sent.documentId);assertEquals("VERIFIED",sent.status)
        rejects{CompleteBackup.verifiedResult(data,Snapshot(),profileJson,queueJson,requestId)}
        rejects{CompleteBackup.verifiedResult(data,queue,profileJson,queueJson.replace("sha256","damaged"),requestId)}
        rejects{CompleteBackup.verifiedResult(data,queue,profileJson,queueJson,"../invalid")}
        assertTrue(JsonParser.parseString(CompleteBackup.verifiedResult(ProfileData(emptyList(),0),Snapshot(),
            ProfileBackup.encode(ProfileData(emptyList(),0)),QueueBackup.encode(Snapshot()),requestId)).asJsonObject["verified"].asBoolean)
    }
}
