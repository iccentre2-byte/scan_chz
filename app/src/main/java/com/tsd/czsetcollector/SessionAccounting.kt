package com.tsd.czsetcollector

import com.google.gson.JsonElement
import java.util.UUID

/** Optional queue-level metadata. Job/PC payloads remain unchanged. Null means pre-session accounting. */
data class CollectionSession(val id:String,val profile:OrganizationProfile,val startedAtMs:Long,val endedAtMs:Long?=null)
data class SessionLedger(val schemaVersion:Int=1,val entries:List<CollectionSession> = emptyList(),
    val jobSessions:Map<String,String> = emptyMap(),val activeSessionId:String?=null)

object SessionAccounting {
    const val LEGACY="legacy"
    const val LEGACY_TITLE="До учёта сессий"
    fun active(snapshot:Snapshot):CollectionSession?=snapshot.sessions?.let{ledger->ledger.entries.firstOrNull{it.id==ledger.activeSessionId}}
    fun belongs(snapshot:Snapshot,job:Job,sessionId:String):Boolean =
        (snapshot.sessions?.jobSessions?.get(job.id) ?: LEGACY)==sessionId
    fun ready(snapshot:Snapshot):Boolean=active(snapshot)!=null || snapshot.sessions==null && snapshot.current!=null
    fun emptyForImport(snapshot:Snapshot):Boolean=snapshot.current==null && snapshot.jobs.isEmpty() &&
        (snapshot.sessions==null || snapshot.sessions.entries.isEmpty() && snapshot.sessions.jobSessions.isEmpty() && snapshot.sessions.activeSessionId==null)

    fun begin(before:Snapshot,profile:OrganizationProfile,now:Long,id:String=UUID.randomUUID().toString()):Snapshot {
        require(before.current==null){"Сначала завершите незавершённый набор"}
        require(active(before)==null){"Рабочая сессия уже открыта"}
        require(now>=0){"Проверьте время на ТСД"};uuid(id)
        val validator=WorkSession();validator.enter(profile,null)
        val frozen=checkNotNull(validator.profile)
        val previous=before.sessions ?: SessionLedger(jobSessions=before.jobs.associate{it.id to LEGACY})
        require(previous.entries.size<10000 && previous.entries.none{it.id==id}){"Слишком много сессий или повтор их идентификатора"}
        val ledger=previous.copy(entries=previous.entries+CollectionSession(id,frozen,now),activeSessionId=id)
        return before.copy(sessions=ledger)
    }
    fun finish(before:Snapshot,now:Long):Snapshot {
        require(before.current==null){"Сначала завершите незавершённый набор"}
        val ledger=before.sessions ?: return before
        val session=active(before) ?: return before
        require(now>=0){"Проверьте время на ТСД"}
        // Store the observed wall clock; never fabricate an archive date or hide a changed device clock.
        return before.copy(sessions=ledger.copy(entries=ledger.entries.map{if(it.id==session.id)it.copy(endedAtMs=now)else it},activeSessionId=null))
    }
    fun attachCurrent(before:Snapshot):Snapshot {
        val current=checkNotNull(before.current)
        val ledger=checkNotNull(before.sessions){"Сначала нажмите «Начать новую сессию»"}
        val session=checkNotNull(active(before)){"Сначала нажмите «Начать новую сессию»"}
        require(WorkSession.matches(session.profile,current)){"Набор не относится к рабочей сессии"}
        require(current.id !in ledger.jobSessions){"Повтор идентификатора набора"}
        return before.copy(sessions=ledger.copy(jobSessions=ledger.jobSessions+(current.id to session.id)))
    }
    fun discardCurrent(before:Snapshot):Snapshot {
        val current=before.current ?: return before
        return before.copy(current=null,sessions=before.sessions?.let{it.copy(jobSessions=it.jobSessions-current.id)})
    }

    fun decode(value:JsonElement?,snapshot:Snapshot):SessionLedger? {
        if(value==null || value.isJsonNull)return null
        val root=BackupJson.objectValue(value)
        BackupJson.keys(root,setOf("schemaVersion","entries","jobSessions"),setOf("activeSessionId"))
        require(BackupJson.integer(root["schemaVersion"])==1){"Неизвестная версия учёта сессий"}
        val entries=root["entries"]
        require(entries!=null && entries.isJsonArray && entries.asJsonArray.size()<=10000){"Неверный список сессий"}
        val parsed=entries.asJsonArray.map{element->
            val entry=BackupJson.objectValue(element)
            BackupJson.keys(entry,setOf("id","profile","startedAtMs"),setOf("endedAtMs"))
            val id=BackupJson.string(entry,"id",36);uuid(id)
            val profile=ProfileBackup.decodeProfile(entry["profile"])
            WorkSession().enter(profile,null)
            CollectionSession(id,profile,time(entry["startedAtMs"]),entry["endedAtMs"]?.takeUnless{it.isJsonNull}?.let{time(it)})
        }
        require(parsed.map{it.id}.distinct().size==parsed.size){"Повтор идентификатора сессии"}
        val mappings=BackupJson.objectValue(root["jobSessions"])
        require(mappings.size()<=10001){"Слишком много наборов в учёте сессий"}
        val jobSessions=mappings.entrySet().associate{(jobId,_) ->
            UUID.fromString(jobId);jobId to BackupJson.string(mappings,jobId,36)
        }
        val active=root["activeSessionId"]?.takeUnless{it.isJsonNull}?.let{BackupJson.string(root,"activeSessionId",36).also{uuid(it)}}
        val ledger=SessionLedger(entries=parsed,jobSessions=jobSessions,activeSessionId=active)
        validate(ledger,snapshot)
        return ledger
    }
    private fun time(value:JsonElement?):Long {
        require(value!=null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber){"Ожидалась дата сессии в миллисекундах"}
        val result=try{value.asBigDecimal.longValueExact()}catch(e:ArithmeticException){throw IllegalArgumentException("Неверная дата сессии",e)}
        require(result>=0){"Неверная дата сессии"};return result
    }
    private fun uuid(id:String) {require(id.length==36 && UUID.fromString(id).toString()==id){"Неверный идентификатор сессии/набора"}}
    private fun validate(ledger:SessionLedger,snapshot:Snapshot) {
        val byId=ledger.entries.associateBy{it.id}
        val open=ledger.entries.filter{it.endedAtMs==null}
        require(if(ledger.activeSessionId==null)open.isEmpty()else open.size==1 && open.single().id==ledger.activeSessionId){"Непоследовательная активная сессия"}
        val jobs=snapshot.jobs.toList()+listOfNotNull(snapshot.current)
        require(ledger.jobSessions.keys==jobs.map{it.id}.toSet()){"Учёт сессий не совпадает с сохранёнными наборами"}
        for(job in jobs) {
            val sessionId=ledger.jobSessions.getValue(job.id)
            require(sessionId==LEGACY || byId.containsKey(sessionId)){"Набор относится к неизвестной сессии"}
            if(sessionId!=LEGACY)require(WorkSession.matches(byId.getValue(sessionId).profile,job)){"Профиль набора отличается от его сессии"}
        }
        snapshot.current?.let{require(ledger.jobSessions[it.id]==ledger.activeSessionId && ledger.activeSessionId!=null){"Незавершённый набор не относится к активной сессии"}}
    }
}
