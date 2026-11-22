package com.tsd.czsetcollector

internal enum class WorkScreenState {NEUTRAL,FILLING,COMPLETE,DUPLICATE}
internal data class WorkScreenColors(val background:Int,val foreground:Int)

/** Presentation only: saved queue/session state survives activity pauses and restarts without new storage keys. */
internal class WorkScreenFeedback {
    companion object {const val FLASH_MS=1400L}
    private var duplicateUntil=Long.MIN_VALUE
    private var completedJobHint:String?=null

    fun savedState(snapshot:Snapshot):WorkScreenState {
        if(snapshot.current!=null)return WorkScreenState.FILLING
        val session=SessionAccounting.active(snapshot)
        if(session!=null && snapshot.jobs.any{SessionAccounting.belongs(snapshot,it,session.id)})return WorkScreenState.COMPLETE
        if(completedJobHint!=null && snapshot.jobs.any{it.id==completedJobHint})return WorkScreenState.COMPLETE
        return WorkScreenState.NEUTRAL
    }
    fun visibleState(snapshot:Snapshot,now:Long):WorkScreenState =
        if(now<duplicateUntil)WorkScreenState.DUPLICATE else savedState(snapshot)
    fun duplicate(now:Long){duplicateUntil=now+FLASH_MS}
    fun savedCompletion(snapshot:Snapshot){completedJobHint=snapshot.jobs.lastOrNull()?.id}
    fun clearFlash(){duplicateUntil=Long.MIN_VALUE}
    fun newSession(){clearFlash();completedJobHint=null}

    fun colors(state:WorkScreenState,night:Boolean):WorkScreenColors {
        val background=if(night)when(state){
            WorkScreenState.NEUTRAL->0xff202124
            WorkScreenState.FILLING->0xffffba00
            WorkScreenState.COMPLETE->0xff42c86f
            WorkScreenState.DUPLICATE->0xffb71c1c
        }else when(state){
            WorkScreenState.NEUTRAL->0xfff5f5f5
            WorkScreenState.FILLING->0xffffd527
            WorkScreenState.COMPLETE->0xff64df85
            WorkScreenState.DUPLICATE->0xffd32020
        }
        val foreground=if(state==WorkScreenState.DUPLICATE || state==WorkScreenState.NEUTRAL && night)
            0xffffffff else 0xff202124
        return WorkScreenColors(background.toInt(),foreground.toInt())
    }
}
