package com.tsd.czsetcollector

enum class ScanSignal(val spoken:String) {
    OPENED("Набор открыт"), COMPLETED("Набор собран"), DUPLICATE("Дубль")
}

data class CollectionResult(val snapshot:Snapshot,val signal:ScanSignal?,val message:String)

/** Hardware often reports one read through both keyboard and broadcast. Sliding silence prevents a burst of echoes. */
class ScanEchoGuard {
    private val recent=linkedMapOf<String,Long>()
    fun accept(code:String,now:Long):Boolean {
        if(recent.values.any{now<it})recent.clear()
        val previous=recent.remove(code)
        val echo=previous!=null && now-previous<700
        recent[code]=now
        recent.entries.removeAll{now-it.value>=700}
        while(recent.size>64)recent.remove(recent.keys.first())
        return !echo
    }
}

/** No input mutation. A state-changing result is returned only after its complete snapshot has been saved. */
object CollectionScan {
    fun accept(before:Snapshot,profile:OrganizationProfile,code:String,save:(Snapshot)->Boolean):CollectionResult? {
        require(Codes.normalize(code)==code){"Некорректный код маркировки"}
        val current=before.current
        require(current==null || WorkSession.matches(profile,current)){"Состав незавершённого набора не соответствует рабочему профилю"}
        val used=before.jobs.firstOrNull {
            it.status!="CANCELLED_LOCAL" && it.environment==profile.environment && (it.setCode==code || code in it.children)
        }
        val duplicate=when {
            used?.setCode==code -> "Дубль: этот набор уже собран. Повторно не добавлен."
            used!=null -> "Дубль: эта пачка уже в собранном наборе. Повторно не добавлена."
            current?.setCode==code -> "Дубль: этот набор уже открыт. Продолжайте сканировать пачки."
            current!=null && code in current.children -> "Дубль: эта пачка уже в текущем наборе. Повторно не добавлена."
            else -> null
        }
        if(duplicate!=null)return CollectionResult(before,ScanSignal.DUPLICATE,duplicate)
        require(SessionAccounting.ready(before)){"Сначала нажмите «Начать новую сессию»"}
        val next:Snapshot
        val signal:ScanSignal?
        val message:String
        if(current==null) {
            require(code.substring(2,16)==profile.template.setGtin){"Сначала сканируйте код НАБОРА с GTIN ${profile.template.setGtin}"}
            next=SessionAccounting.attachCurrent(before.copy(current=Job(environment=profile.environment,inn=profile.inn,pg=profile.pg,
                template=profile.template,setCode=code),jobs=before.jobs.toMutableList()))
            signal=ScanSignal.OPENED
            message="Набор открыт. Сканируйте пачки."
        } else {
            val gtin=code.substring(2,16);val expected=current.template.expected[gtin]?:0
            require(expected>0){"Этого GTIN нет в составе шаблона: $gtin"}
            require(current.children.count{it.substring(2,16)==gtin}<expected){"По GTIN $gtin уже набрано нужное количество"}
            val changed=current.copy(children=(current.children+code).toMutableList())
            if(changed.children.size==changed.template.expected.values.sum()) {
                changed.status="LOCAL_READY"
                next=before.copy(current=null,jobs=(before.jobs+changed).toMutableList())
                signal=ScanSignal.COMPLETED
                message="Набор собран и сохранён на ТСД. Сканируйте следующий набор."
            } else {
                next=before.copy(current=changed,jobs=before.jobs.toMutableList())
                signal=null
                message="Пачка сохранена: ${changed.children.size} / ${changed.template.expected.values.sum()}."
            }
        }
        return if(save(next))CollectionResult(next,signal,message)else null
    }
}
