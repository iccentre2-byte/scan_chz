package com.tsd.czsetcollector

import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File

/** AtomicFile retains the previous file if the app/device stops during a write. */
class QueueStore(dir:File) {
    private val file=AtomicFile(File(dir,"queue-v1.json"))
    private val gson=Gson()
    fun load():Snapshot {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return Snapshot()
        val raw=String(file.readFully(),Charsets.UTF_8)
        val root=com.google.gson.JsonParser.parseString(raw)
        require(root.isJsonObject && root.asJsonObject.has("jobs") && root.asJsonObject.get("jobs").isJsonArray){"Неполный/повреждённый файл очереди"}
        val snapshot=gson.fromJson(raw,Snapshot::class.java) ?: throw IllegalStateException("Файл очереди пуст/повреждён")
        requireNotNull(snapshot.jobs){"Повреждён список заданий"}
        val all=snapshot.jobs.toList()+listOfNotNull(snapshot.current)
        require(all.map{it.id}.toSet().size==all.size){"Повтор идентификатора задания в файле"}
        for(j in all){
            java.util.UUID.fromString(j.id)
            require(j.schemaVersion==1 && j.environment in setOf("production","sandbox") && j.pg=="grocery" && Codes.inn(j.inn)){"Неподдерживаемое задание в очереди"}
            require(Codes.normalize(j.setCode)==j.setCode && j.setCode.substring(2,16)==j.template.setGtin){"Повреждён код набора"}
            val expected=Codes.composition(j.template.expected.entries.joinToString("\n"){"${it.key}=${it.value}"})
            require(j.children.distinct().size==j.children.size && j.setCode !in j.children){"Повтор кода в наборе"}
            require(j.children.all{Codes.normalize(it)==it && expected.containsKey(it.substring(2,16))}){"Повреждены вложения"}
            val actual=j.children.groupingBy{it.substring(2,16)}.eachCount()
            require(actual.all{(g,n)->n <= (expected[g]?:0)}){"Превышен состав набора"}
            require(j===snapshot.current || actual==expected){"В очереди есть недоукомплектованный набор"}
        }
        return snapshot
    }
    fun save(snapshot:Snapshot) {
        val stream=file.startWrite()
        try {stream.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw e}
    }
}
