package com.tsd.czsetcollector

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class QueueBackupTests {
    private val gtin = "04607010740016"
    private fun job(serial: String) = Job(inn = "7707083893", template = Template(gtin, mapOf(gtin to 1)),
        setCode = "01${gtin}21$serial", children = mutableListOf("01${gtin}21CHILD00000001"),
        status = "SUBMITTED", documentId = "document-123", uploaded = true)

    @Test fun queueTransferPreservesDeliveryStateAndCurrentJob() {
        val sent = job("SETCODE000001")
        val current = job("SETCODE000002").copy(children = mutableListOf(), status = "COLLECTING", documentId = null, uploaded = false)
        val original = Snapshot(current, mutableListOf(sent))
        val imported = QueueBackup.prepareImport(Snapshot(), QueueBackup.encode(original))
        assertEquals(original, imported)
        assertTrue(imported.jobs.single().uploaded)
        assertEquals("document-123", imported.jobs.single().documentId)
        assertEquals("SUBMITTED", imported.jobs.single().status)
        assertEquals(sent.id, imported.jobs.single().id)
    }

    @Test fun queueCannotBeImportedOverAnExistingJob() {
        val existing = Snapshot(jobs = mutableListOf(job("SETCODE000001")))
        val before = existing.jobs.toList()
        try { QueueBackup.prepareImport(existing, QueueBackup.encode(Snapshot())); fail("Expected refusal") } catch (_: IllegalArgumentException) { }
        assertEquals(before, existing.jobs)
    }

    @Test fun corruptedQueueIsRejectedBeforeAnyMutation() {
        val good = QueueBackup.encode(Snapshot(jobs = mutableListOf(job("SETCODE000001"))))
        val envelope = JsonParser.parseString(good).asJsonObject
        envelope.addProperty("sha256", "0".repeat(64))
        try { QueueBackup.prepareImport(Snapshot(), envelope.toString()); fail("Expected checksum refusal") } catch (_: IllegalArgumentException) { }
        val payload = JsonParser.parseString(BackupJson.unwrap(good, QueueBackup.FORMAT)).asJsonObject
        payload["jobs"].asJsonArray[0].asJsonObject.addProperty("pcKey", "secret")
        try { QueueBackup.decode(BackupJson.wrap(QueueBackup.FORMAT, payload.toString())); fail("Expected schema refusal") } catch (_: IllegalArgumentException) { }
    }
}
