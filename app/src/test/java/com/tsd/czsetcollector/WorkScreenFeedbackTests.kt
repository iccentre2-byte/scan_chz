package com.tsd.czsetcollector

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class WorkScreenFeedbackTests {
    private val gtin="04607010740016"
    private val profile=OrganizationProfile("7707083893",template=Template(gtin,mapOf(gtin to 1)))
    private val set="01${gtin}21SETCODE000001"
    private val child="01${gtin}21CHILD00000001"
    private fun started()=SessionAccounting.begin(Snapshot(),profile,1700000000000)
    private fun opened()=CollectionScan.accept(started(),profile,set){true}!!.snapshot
    private fun complete()=CollectionScan.accept(opened(),profile,child){true}!!.snapshot

    @Test fun initialAndArchivedJobsAreNeutralUntilTheNewSessionCollectsASet() {
        val ui=WorkScreenFeedback()
        assertEquals(WorkScreenState.NEUTRAL,ui.savedState(Snapshot()))
        val legacy=complete().copy(sessions=null)
        assertEquals(WorkScreenState.NEUTRAL,ui.savedState(legacy))
        val fresh=SessionAccounting.begin(legacy,profile,1700000000100)
        assertEquals(WorkScreenState.NEUTRAL,ui.savedState(fresh))
    }
    @Test fun savedOpenSetIsYellowAfterPauseAndProcessRestart() {
        val snapshot=opened();val ui=WorkScreenFeedback()
        assertEquals(WorkScreenState.FILLING,ui.savedState(snapshot))
        ui.clearFlash()
        assertEquals(WorkScreenState.FILLING,ui.savedState(snapshot))
        val restored=QueueBackup.decode(QueueBackup.encode(snapshot))
        assertEquals(WorkScreenState.FILLING,WorkScreenFeedback().savedState(restored))
    }
    @Test fun savedCompletedSetIsGreenAfterRestartAndTheNextOpenSetReturnsToYellow() {
        val snapshot=complete()
        val restored=QueueBackup.decode(QueueBackup.encode(snapshot))
        assertEquals(WorkScreenState.COMPLETE,WorkScreenFeedback().savedState(restored))
        val nextSet="01${gtin}21SETCODE000002"
        val next=CollectionScan.accept(restored,profile,nextSet){true}!!.snapshot
        assertEquals(WorkScreenState.FILLING,WorkScreenFeedback().savedState(next))
        assertNull(CollectionScan.accept(restored,profile,nextSet){false})
        assertEquals(WorkScreenState.COMPLETE,WorkScreenFeedback().savedState(restored))
    }
    @Test fun duplicateFlashRestoresBothYellowAndGreenWithoutChangingStoredData() {
        for(snapshot in listOf(opened(),complete())){
            val ui=WorkScreenFeedback();val underlying=ui.savedState(snapshot)
            val before=QueueBackup.encode(snapshot)
            ui.duplicate(1000)
            assertEquals(WorkScreenState.DUPLICATE,ui.visibleState(snapshot,1001))
            assertEquals(underlying,ui.visibleState(snapshot,1000+WorkScreenFeedback.FLASH_MS))
            assertEquals(before,QueueBackup.encode(snapshot))
        }
    }
    @Test fun clearingEventTimerOrStartingANewSessionCannotLeaveRedBehind() {
        val ui=WorkScreenFeedback();val snapshot=opened()
        ui.duplicate(1000);ui.clearFlash()
        assertEquals(WorkScreenState.FILLING,ui.visibleState(snapshot,1001))
        val legacy=complete().copy(sessions=null)
        ui.savedCompletion(legacy)
        assertEquals(WorkScreenState.COMPLETE,ui.savedState(legacy))
        ui.duplicate(2000);ui.newSession()
        assertEquals(WorkScreenState.NEUTRAL,ui.visibleState(legacy,2001))
    }
    @Test fun allLightAndDarkStatesKeepNormalLabelsAtLeastFourPointFiveContrast() {
        val ui=WorkScreenFeedback()
        for(state in WorkScreenState.values()){
            val light=ui.colors(state,false);val dark=ui.colors(state,true)
            for(colors in listOf(light,dark)){
                val a=luminance(colors.background);val b=luminance(colors.foreground)
                assertTrue("Low contrast in $state: $colors",(maxOf(a,b)+0.05)/(minOf(a,b)+0.05)>=4.5)
            }
            assertTrue(luminance(dark.background)<luminance(light.background))
        }
    }
    private fun luminance(color:Int):Double {
        fun channel(shift:Int):Double {
            val value=((color shr shift) and 255)/255.0
            return if(value<=0.04045)value/12.92 else ((value+0.055)/1.055).pow(2.4)
        }
        return 0.2126*channel(16)+0.7152*channel(8)+0.0722*channel(0)
    }
}
