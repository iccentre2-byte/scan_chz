package com.tsd.czsetcollector

import java.io.IOException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

internal data class StatusReadReport(val checked:Int,val skipped:Int,val firstSkipped:Exception?,
    val blockingFailure:Exception?=null,val stopped:Boolean=false)

/** The cursor advances on actual attempts, including refused jobs. No queue fields are used as a cursor. */
internal class StatusReadRotation(private val limit:Int=100) {
    init {require(limit>0)}
    private var lastAttemptedId:String?=null

    fun batch(jobs:List<Job>):List<Job> {
        if(jobs.isEmpty())return emptyList()
        val start=(jobs.indexOfFirst{it.id==lastAttemptedId}+1)%jobs.size
        val result=mutableListOf<Job>()
        for(offset in jobs.indices) {
            val job=jobs[(start+offset)%jobs.size]
            if(job.uploaded && job.status!="VERIFIED" && job.status!="CANCELLED_LOCAL")result.add(job)
            if(result.size==limit)break
        }
        return result
    }

    suspend fun readBatch(jobs:List<Job>,read:suspend(Job)->Job,apply:(Job,Job)->Boolean,
        shouldStop:()->Boolean):StatusReadReport {
        var checked=0;var skipped=0;var firstSkipped:Exception?=null
        for(job in jobs) {
            if(shouldStop())return StatusReadReport(checked,skipped,firstSkipped,stopped=true)
            lastAttemptedId=job.id
            try {
                val reply=read(job)
                if(shouldStop() || !apply(job,reply))return StatusReadReport(checked,skipped,firstSkipped,stopped=true)
                checked++
            } catch(error:Exception) {
                val causes=generateSequence<Throwable>(error){it.cause}.take(10).toList()
                causes.filterIsInstance<CancellationException>().firstOrNull()?.let{throw it}
                if(!isolated(error,causes))return StatusReadReport(checked,skipped,firstSkipped,blockingFailure=error)
                skipped++;if(firstSkipped==null)firstSkipped=error
            }
        }
        return StatusReadReport(checked,skipped,firstSkipped)
    }

    private fun isolated(error:Exception,causes:List<Throwable>):Boolean {
        if(causes.any{it is SSLException})return false
        val http=causes.filterIsInstance<PcHttpException>().firstOrNull()
        if(http!=null)return http.code==404 || http.code==410
        if(causes.any{it is PcResponseException})return true
        // Composition/UUID refusal belongs to this job; a transport fault belongs to the connection.
        return error is IllegalArgumentException && causes.none{it is IOException}
    }
}

/** Validate against the latest queue, save a replacement, then publish it. Failed writes leave memory unchanged. */
internal object PcReplyCommit {
    private val states=setOf("WAITING_SIGNATURE","SUBMITTING","SUBMITTED","PROCESSING","VERIFIED","REJECTED",
        "UNKNOWN","BLOCKED","CHECK_REQUIRED","CANCELLED_LOCAL")

    fun apply(snapshot:Snapshot,requested:Job,reply:Job,save:(Snapshot)->Boolean):Snapshot? {
        val at=snapshot.jobs.indexOfFirst{it.id==requested.id}
        require(at>=0){"Набор больше не найден в очереди. Ответ не принят"}
        val actual=snapshot.jobs[at]
        require(samePayload(actual,requested) && samePayload(actual,reply)){"ПК вернул другой состав. Ответ не принят"}
        require(reply.status in states){"Неизвестный статус ПК"}
        val changed=!actual.uploaded || actual.status!=reply.status || actual.detail!=reply.detail || actual.documentId!=reply.documentId
        if(!changed)return snapshot
        val replacement=actual.copy(children=actual.children.toMutableList(),status=reply.status,detail=reply.detail,
            documentId=reply.documentId,uploaded=true)
        val next=snapshot.copy(jobs=snapshot.jobs.toMutableList().apply{this[at]=replacement})
        return if(save(next))next else null
    }
    private fun samePayload(a:Job,b:Job)=a.schemaVersion==b.schemaVersion && a.pg==b.pg && a.id==b.id && a.inn==b.inn &&
        a.environment==b.environment && a.setCode==b.setCode && a.children.size==b.children.size &&
        a.children.toSet()==b.children.toSet() && a.template==b.template
}
