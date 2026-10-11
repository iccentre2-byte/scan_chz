package com.tsd.czsetcollector

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProfileBackupTests {
    private val complete = OrganizationProfile("7707083893", title = "Кириешки 6 / ИП", template = Template("04607010740016", linkedMapOf("04607010740016" to 6)))
    private val draft = OrganizationProfile(title = "Ненастроенный профиль")
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun roundTripPreservesEveryProfileIncludingDraftAndSelection() {
        val data = ProfileData(listOf(complete, draft, complete.copy(title = "Тест", environment = "sandbox")), 2)
        assertEquals(data, ProfileBackup.decode(ProfileBackup.encode(data)))
        val envelope = JsonParser.parseString(ProfileBackup.encode(data)).asJsonObject
        assertEquals(BackupJson.sha256(envelope["payloadJson"].asString), envelope["sha256"].asString)
        assertFalse(envelope["payloadJson"].asString.contains("pc_connection"))
        assertFalse(envelope["payloadJson"].asString.contains("documentId"))
    }

    @Test fun corruptionUnknownFieldsDuplicateKeysAndFractionalQuantitiesAreRejected() {
        val good = ProfileBackup.encode(ProfileData(listOf(complete), 0))
        val envelope = JsonParser.parseString(good).asJsonObject
        envelope.addProperty("payloadJson", envelope["payloadJson"].asString.replace("Кириешки", "Подмена"))
        rejects { ProfileBackup.decode(envelope.toString()) }
        envelope.addProperty("secret", "connection-key")
        rejects { ProfileBackup.decode(envelope.toString()) }
        val payload = JsonParser.parseString(BackupJson.unwrap(good, ProfileBackup.FORMAT)).asJsonObject
        payload["profiles"].asJsonArray[0].asJsonObject.addProperty("pc_connection", "secret")
        rejects { ProfileBackup.decode(BackupJson.wrap(ProfileBackup.FORMAT, payload.toString())) }
        val duplicate = "{\"profiles\":[],\"selectedIndex\":0,\"selectedIndex\":0}"
        rejects { ProfileBackup.decode(BackupJson.wrap(ProfileBackup.FORMAT, duplicate)) }
        val fractional = BackupJson.unwrap(good, ProfileBackup.FORMAT).replace("\":6", "\":6.5")
        rejects { ProfileBackup.decode(BackupJson.wrap(ProfileBackup.FORMAT, fractional)) }
        rejects { ProfileBackup.decode(good + " {}") }
    }

    @Test fun mergeNeverClobbersAnExistingTemplateAndRepeatImportIsIdempotent() {
        val existing = ProfileData(listOf(complete), 0)
        val other = complete.copy(template = complete.template.copy(expected = mapOf("04607010740016" to 3)))
        val result = ProfileBackup.merge(existing, ProfileData(listOf(other, draft), 0))
        assertEquals(complete, existing.profiles.single())
        assertEquals(complete, result.data.profiles.first())
        assertEquals(other.template, result.data.profiles[1].template)
        assertNotEquals(complete.title, result.data.profiles[1].title)
        assertEquals(2, result.added); assertEquals(1, result.renamed)
        assertEquals(result.data, ProfileBackup.merge(result.data, ProfileData(listOf(other, draft), 0)).data)
    }

    private class Memory(var value: StoredProfiles) : ProfilePreferences {
        var failNext = false
        var corruptNext = false
        var throwAfterWriteNext = false
        var readErrorAfterWriteNext = false
        private var failRead = false
        var writes = 0
        val untouchedQueue = "queue-v1-with-uploaded-jobs"
        val untouchedPcKey = "secret"
        override fun read():StoredProfiles {
            if(failRead){failRead=false;throw IllegalStateException("simulated read IO failure")}
            return value
        }
        override fun write(value: StoredProfiles): Boolean {
            writes++
            this.value = value // Android commit may change memory before returning false.
            if(corruptNext){corruptNext=false;this.value=value.copy(raw="partial JSON")}
            if(throwAfterWriteNext){throwAfterWriteNext=false;throw IllegalStateException("simulated partial IO failure")}
            if(readErrorAfterWriteNext){readErrorAfterWriteNext=false;failRead=true}
            if (failNext) { failNext = false; return false }; return true
        }
    }
    private fun repo(storage: Memory, archive: (String) -> String = { BackupJson.sha256(it) }) = ProfileRepository(storage, archive)

    @Test fun verifiedMigrationRetainsOriginalLegacyJsonByteForByte() {
        val raw = Gson().toJson(listOf(complete, draft))
        val storage = Memory(StoredProfiles(raw, 1))
        var archived: ProfileData? = null
        val repository = repo(storage) { archived = ProfileBackup.decode(it); BackupJson.sha256(it) }
        val loaded = repository.load()
        assertEquals(loaded, archived); assertEquals(raw, storage.value.raw)
        assertEquals(1, storage.value.schema); assertEquals(1, loaded.selectedIndex)
        assertEquals("queue-v1-with-uploaded-jobs", storage.untouchedQueue)
        assertEquals("secret", storage.untouchedPcKey)
    }

    @Test fun failedArchiveOrFailedCommitCannotLoseOldProfiles() {
        val initial = StoredProfiles(Gson().toJson(listOf(complete)), 0)
        val failedArchive = Memory(initial)
        try { repo(failedArchive) { "0".repeat(64) }.load(); fail("Expected archive failure") } catch (_: IllegalArgumentException) { }
        assertEquals(initial, failedArchive.value)
        val memory = Memory(initial.copy(schema = 1))
        val before = memory.value; memory.failNext = true
        try { repo(memory).importBackup(ProfileBackup.encode(ProfileData(listOf(draft), 0))); fail("Expected commit failure") } catch (_: IllegalStateException) { }
        assertEquals(before, memory.value)
        rejects { repo(memory).importBackup("{bad json}") }
        assertEquals(before, memory.value)
    }

    @Test fun damagedLegacyWithOneValidProfileCannotBeReplacedOrMigrated() {
        val valid = Gson().toJson(complete)
        val raw = "[$valid,{\"title\":\"missing fields\"}]"
        val initial = StoredProfiles(raw, 0)
        val memory = Memory(initial)
        var archiveCalls=0
        val repository=repo(memory){archiveCalls++;BackupJson.sha256(it)}
        rejects{repository.load()}
        rejects{repository.importBackup(ProfileBackup.encode(ProfileData(listOf(draft),0)))}
        rejects{repository.save(ProfileData(listOf(draft),0))}
        assertEquals(initial,memory.value);assertEquals(0,memory.writes);assertEquals(0,archiveCalls)
    }

    @Test fun partialWriteAndReadBackFailuresRestoreTheEntireOriginalState() {
        val original=StoredProfiles(Gson().toJson(listOf(complete)),0,1,"a".repeat(64))
        val incoming=ProfileBackup.encode(ProfileData(listOf(draft),0))
        for(mode in 0..2){
            val memory=Memory(original)
            when(mode){0->memory.corruptNext=true;1->memory.throwAfterWriteNext=true;2->memory.readErrorAfterWriteNext=true}
            try{repo(memory).importBackup(incoming);fail("Expected IO failure in mode $mode")}catch(_:IllegalStateException){}
            assertEquals(original,memory.value)
            assertEquals(2,memory.writes)
        }
    }

    @Test fun missingProfilesWithMigrationMarkerCannotCreateANewDefaultOverOldData() {
        val memory=Memory(StoredProfiles(null,0,1,"a".repeat(64)))
        rejects{repo(memory).load()}
        rejects{repo(memory).importBackup(ProfileBackup.encode(ProfileData(listOf(draft),0)))}
        assertEquals(0,memory.writes)
    }
}
