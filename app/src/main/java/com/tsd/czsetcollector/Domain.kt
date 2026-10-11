package com.tsd.czsetcollector

import java.util.UUID

/** This pilot handles grocery UNIT/SET codes with a 13-character serial, not SSCC. */
object Codes {
    fun gtin(s: String): Boolean = s.matches(Regex("[0-9]{14}")) &&
        (s.dropLast(1).mapIndexed { i,c -> (c-'0')*(if(i%2==0)3 else 1) }.sum() + (s.last()-'0'))%10==0
    fun inn(s: String): Boolean {
        if(!s.matches(Regex("[0-9]{10}|[0-9]{12}"))) return false
        fun digit(weights: List<Int>) = weights.mapIndexed { i,w -> (s[i]-'0')*w }.sum()%11%10
        return if(s.length==10) digit(listOf(2,4,10,3,5,9,4,6,8))==s[9]-'0'
        else digit(listOf(7,2,4,10,3,5,9,4,6,8))==s[10]-'0' && digit(listOf(3,7,2,4,10,3,5,9,4,6,8))==s[11]-'0'
    }
    fun withoutPrefix(raw: String): String {
        val s=raw.trim(' ','\r','\n','\t')
        return if(listOf("]d2","]e0","]Q3","]C1").any{s.startsWith(it)}) s.drop(3) else s
    }
    fun normalize(raw: String): String {
        require(raw.length<=512){"Слишком длинный код"}
        var s=withoutPrefix(raw)
        if(s.startsWith("(01)")) {
            val m=Regex("\\(01\\)([0-9]{14})\\(21\\)(.{13})(?:\\((?:91|93)\\).*)?").matchEntire(s)
                ?: throw IllegalArgumentException("Неоднозначный код. Настройте передачу GS/FNC1 сканером")
            s="01"+m.groupValues[1]+"21"+m.groupValues[2]
        }
        var head=s.substringBefore('\u001d')
        if(head.length>31) {
            require(head.substring(31).startsWith("91")||head.substring(31).startsWith("93")){"Нет GS после серийного номера"}
            head=head.take(31)
        }
        require(head.matches(Regex("01[0-9]{14}21[!-~]{13}"))){"Нужен DataMatrix бакалеи, не обычный штрихкод и не SSCC"}
        require(gtin(head.substring(2,16))){"Ошибочная контрольная цифра GTIN"}
        return head
    }
    /** Profile setup extracts AI (01) only; collection still uses the strict unit-code parser above. */
    fun profileGtin(raw:String):String {
        require(raw.length<=4096){"Слишком длинный код для GTIN"}
        val value=raw.trim(' ','\r','\n','\t').replaceFirst(Regex("^\\][A-Za-z][0-9]"),"")
        val candidate=when {
            value.matches(Regex("[0-9]{14}"))->value
            value.startsWith("(01)")-> {
                val m=Regex("^\\(01\\)([0-9]{14})(?:$|\\([0-9]{2,4}\\).*)",RegexOption.DOT_MATCHES_ALL).matchEntire(value)
                require(m!=null){"В коде нет однозначного GTIN после (01)"};m.groupValues[1]
            }
            value.startsWith("01")-> {
                require(value.length>=16){"Неполный GTIN после 01"}
                val tail=value.drop(16)
                require(tail.isEmpty() || tail.startsWith('\u001d') ||
                    Regex("(?:10|11|13|15|17|21|22|30|37|91|92|93).+",RegexOption.DOT_MATCHES_ALL).matches(tail)) {
                    "В коде нет однозначного GTIN после 01"
                }
                value.substring(2,16)
            }
            value.startsWith("https://") || value.startsWith("http://")-> {
                val uri=try{java.net.URI(value)}catch(e:Exception){throw IllegalArgumentException("Некорректный QR GS1",e)}
                require(!uri.host.isNullOrBlank() && uri.userInfo==null){"Некорректный QR GS1"}
                val parts=uri.rawPath.orEmpty().split('/')
                val identifiers=parts.indices.filter{parts[it]=="01"}
                require(identifiers.size==1 && identifiers[0]+1<parts.size){"QR должен содержать один GTIN в пути /01/GTIN"}
                parts[identifiers[0]+1]
            }
            else->throw IllegalArgumentException("Нужны 14 цифр GTIN или код GS1 с идентификатором 01")
        }
        require(gtin(candidate)){"Проверьте 14 цифр GTIN и контрольную цифру"}
        return candidate
    }
    fun composition(text: String): Map<String,Int> {
        val result=linkedMapOf<String,Int>()
        for(line in text.lines().map{it.trim()}.filter{it.isNotEmpty()}) {
            val p=line.split('=')
            require(p.size==2 && gtin(p[0].trim())){"Состав: каждая строка должна иметь вид GTIN=количество"}
            val qty=p[1].trim().toIntOrNull() ?: throw IllegalArgumentException("Неверное количество")
            val key=p[0].trim()
            require(qty in 1..1000 && !result.containsKey(key)){"Количество 1–1000; каждый GTIN укажите один раз"}
            result[key]=qty
        }
        require(result.isNotEmpty() && result.size<=100 && result.values.sum()<=1000){"Заполните состав: не более 1000 пачек"}
        return result
    }
}

data class Template(val setGtin:String="",val expected:Map<String,Int> = emptyMap())
data class OrganizationProfile(val inn:String="",val pg:String="grocery",val title:String="Новый профиль",
    val environment:String="production",val template:Template=Template()) { override fun toString()=title }
data class Job(val schemaVersion:Int=1,val id:String=UUID.randomUUID().toString(),val environment:String="production",
    val inn:String="",val pg:String="grocery",val template:Template=Template(),val setCode:String="",
    val children:MutableList<String> = mutableListOf(),var status:String="COLLECTING",var detail:String="",
    var documentId:String?=null,var uploaded:Boolean=false)
data class Snapshot(var current:Job?=null,val jobs:MutableList<Job> = mutableListOf())
data class PcConfig(val url:String="",val key:String="",val pin:String="")
object Labels {
    fun status(s:String):String=mapOf("COLLECTING" to "Комплектуется", "LOCAL_READY" to "Собран на ТСД — ещё не на ПК",
        "WAITING_SIGNATURE" to "На ПК: ожидает подписи", "SUBMITTING" to "ПК отправляет в ЧЗ",
        "SUBMITTED" to "Передан в ЧЗ", "PROCESSING" to "ЧЗ обрабатывает", "VERIFIED" to "Сформирован: состав проверен",
        "REJECTED" to "Отклонён ЧЗ", "UNKNOWN" to "Результат неизвестен — НЕ повторять",
        "BLOCKED" to "ПК: требуется исправление", "CANCELLED_LOCAL" to "Отменён на ПК до отправки", "CHECK_REQUIRED" to "Требуется проверка ЧЗ")[s]?:s
}
