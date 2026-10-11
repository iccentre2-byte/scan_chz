package com.tsd.czsetcollector

import com.google.gson.JsonObject

object CompleteBackup {
    fun verifiedResult(profiles:ProfileData,queue:Snapshot,profilesFile:String,queueFile:String,requestId:String):String {
        require(requestId.length<=64 && requestId.matches(Regex("[A-Za-z0-9-]+"))){"Неверный идентификатор запроса резервной копии"}
        require(ProfileBackup.decode(profilesFile)==profiles && QueueBackup.decode(queueFile)==queue){"Данные резервной копии отличаются"}
        return JsonObject().apply{
            addProperty("verified",true)
            addProperty("requestId",requestId)
            addProperty("profilesSha256",BackupJson.sha256(BackupJson.unwrap(profilesFile,ProfileBackup.FORMAT)))
            addProperty("queueSha256",BackupJson.sha256(BackupJson.unwrap(queueFile,QueueBackup.FORMAT)))
            addProperty("profileCount",profiles.profiles.size);addProperty("jobCount",queue.jobs.size);addProperty("hasCurrent",queue.current!=null)
        }.toString()
    }
}
