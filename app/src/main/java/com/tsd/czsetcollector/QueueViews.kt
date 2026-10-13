package com.tsd.czsetcollector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class QueueView(val label:String) {CURRENT("Текущая сессия"),ALL("Все наборы"),HISTORY("История сессий")}
data class SessionOption(val id:String,val label:String) {override fun toString()=label}
data class QueueSelection(val title:String,val jobs:List<Job>,val current:Job?) {
    val summary:String get()="Готовых наборов: ${jobs.size}; на ТСД: ${jobs.count{!it.uploaded}}; подтверждено ЧЗ: ${jobs.count{it.status=="VERIFIED"}}; незавершённый: ${if(current==null)"нет" else "есть"}."
}

/** Read-only views never select a working profile, create metadata, or relabel historical jobs. */
object QueueViews {
    fun date(milliseconds:Long)=SimpleDateFormat("dd.MM.yyyy HH:mm",Locale("ru","RU")).format(Date(milliseconds))
    fun options(snapshot:Snapshot):List<SessionOption> {
        val result=snapshot.sessions?.entries.orEmpty().asReversed().map{session->
            val count=snapshot.jobs.count{SessionAccounting.belongs(snapshot,it,session.id)}
            SessionOption(session.id,"${date(session.startedAtMs)} · ${session.profile.title} · $count наборов${if(session.endedAtMs==null)" · открыта" else ""}")
        }.toMutableList()
        if((snapshot.jobs+listOfNotNull(snapshot.current)).any{SessionAccounting.belongs(snapshot,it,SessionAccounting.LEGACY)}) {
            result.add(SessionOption(SessionAccounting.LEGACY,"${SessionAccounting.LEGACY_TITLE} · ${snapshot.jobs.count{SessionAccounting.belongs(snapshot,it,SessionAccounting.LEGACY)}} наборов · даты неизвестны"))
        }
        return result
    }
    fun select(snapshot:Snapshot,view:QueueView,historyId:String?=null):QueueSelection {
        if(view==QueueView.ALL)return QueueSelection("Все наборы всех профилей",snapshot.jobs.toList(),snapshot.current)
        val active=SessionAccounting.active(snapshot)
        val id=if(view==QueueView.CURRENT)active?.id else historyId
        val session=snapshot.sessions?.entries?.firstOrNull{it.id==id}
        val title=when {
            id==SessionAccounting.LEGACY->"${SessionAccounting.LEGACY_TITLE}. Даты сборки неизвестны."
            session!=null->"${session.profile.title} · ${date(session.startedAtMs)}${session.endedAtMs?.let{" — ${date(it)}"} ?: " · открыта"}"
            view==QueueView.CURRENT->"Новая сессия ещё не начата. Старые наборы — в истории «${SessionAccounting.LEGACY_TITLE}»."
            else->"Выберите сессию из истории."
        }
        val jobs=if(id==null)emptyList()else snapshot.jobs.filter{SessionAccounting.belongs(snapshot,it,id)}
        val current=snapshot.current?.takeIf{id!=null && SessionAccounting.belongs(snapshot,it,id)}
        return QueueSelection(title,jobs,current)
    }
    fun describe(selection:QueueSelection):String {
        val lines=selection.jobs.asReversed().map{job->
            "…${job.setCode.takeLast(6)} · ИНН ${job.inn} · ${if(job.environment=="sandbox")"ТЕСТ" else "РЕАЛЬНЫЙ ЧЗ"} · ${job.children.size} шт\n${Labels.status(job.status)}${if(job.detail.isNotBlank())"\n"+job.detail.take(350)else ""}${job.documentId?.let{"\nДокумент: $it"} ?: ""}"
        }.toMutableList()
        selection.current?.let{lines.add(0,"Незавершённый: …${it.setCode.takeLast(6)} · ИНН ${it.inn} · ${it.children.size} пачек")}
        return lines.joinToString("\n\n").ifBlank{"В этом просмотре готовых наборов нет."}
    }
}
