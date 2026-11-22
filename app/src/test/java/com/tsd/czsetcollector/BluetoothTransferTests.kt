package com.tsd.czsetcollector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class BluetoothTransferTests {
    private val gtin = "04607010740016"
    private val profile = OrganizationProfile("7707083893", template=Template(gtin,mapOf(gtin to 1)))
    private val active = SessionAccounting.begin(Snapshot(),profile,1700000000000)
    private val opened = CollectionScan.accept(active,profile,"01${gtin}21SETCODE000001"){true}!!.snapshot
    private val ready = CollectionScan.accept(opened,profile,"01${gtin}21CHILD00000001"){true}!!.snapshot.jobs.single()

    @Test fun receiverPreservesOriginalIdAndContentsInAnIndependentQueue() {
        val transfer=BluetoothTransfers.decode(BluetoothTransfers.encode(BluetoothTransfer(profile,ready)))
        val before=Snapshot()
        val received=BluetoothTransfers.receive(before,transfer,1700000000200)
        assertEquals(emptyList<Job>(),before.jobs)
        assertEquals(ready,received.jobs.single())
        assertNull(received.current)
        assertNull(SessionAccounting.active(received))
        assertEquals(received,QueueBackup.decode(QueueBackup.encode(received)))
        assertFalse(received.jobs.single().uploaded)
    }

    @Test fun receiverRefusesTheSameIdAndAlsoTheSameCodesUnderAnotherId() {
        val transfer=BluetoothTransfer(profile,ready)
        val received=BluetoothTransfers.receive(Snapshot(),transfer,1700000000200)
        assertThrows(IllegalArgumentException::class.java){BluetoothTransfers.receive(received,transfer,1700000000300)}
        val retry=BluetoothTransfer(profile,ready.copy(id=UUID.randomUUID().toString()))
        assertThrows(IllegalArgumentException::class.java){BluetoothTransfers.receive(received,retry,1700000000300)}
    }

    @Test fun rejectsAnOpenOrAlreadyUploadedSetAndAcceptsProfileOnly() {
        for(job in listOf(opened.current!!,ready.copy(status="WAITING_SIGNATURE",uploaded=true))){
            assertThrows(IllegalArgumentException::class.java){BluetoothTransfers.encode(BluetoothTransfer(profile,job))}
        }
        val transfer=BluetoothTransfer(profile,null)
        assertEquals(transfer,BluetoothTransfers.decode(BluetoothTransfers.encode(transfer)))
    }
}
