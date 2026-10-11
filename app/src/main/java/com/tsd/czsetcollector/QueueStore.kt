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
        return QueueBackup.parsePayload(raw)
    }
    fun save(snapshot:Snapshot) {
        val stream=file.startWrite()
        try {stream.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw e}
    }
    fun importEmpty(raw:String):Snapshot {
        val before=load()
        val imported=QueueBackup.prepareImport(before,raw)
        try {
            save(imported)
            require(load()==imported){"Проверка перенесённой очереди не пройдена"}
        } catch(e:Exception) {
            // Restore the previous empty snapshot if the post-write read-back fails.
            save(before)
            require(load()==before){"Ошибка восстановления очереди; не удаляйте приложение"}
            throw e
        }
        return imported
    }
}
