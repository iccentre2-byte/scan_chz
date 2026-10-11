package com.tsd.czsetcollector

import com.google.gson.JsonObject

data class StagedPlan(val merge:ProfileMerge,val source:ProfileData,val queue:Snapshot,
    val profilesHash:String,val queueHash:String)
data class StagedSources(val profiles:ProfileData,val queue:Snapshot,val profilesHash:String,val queueHash:String)

/** Validate both staged files and the entire destination before any part of a transfer is written. */
object StagedMigration {
    fun prepare(existingProfiles:ProfileData,existingQueue:Snapshot,profilesFile:String,queueFile:String):StagedPlan {
        return prepare(existingProfiles,existingQueue,read(profilesFile,queueFile))
    }
    fun read(profilesFile:String,queueFile:String):StagedSources {
        val source=ProfileBackup.decode(profilesFile)
        val queue=QueueBackup.decode(queueFile)
        require(source.profiles.isNotEmpty()) { "В исходной копии нет профилей" }
        return StagedSources(source,queue,
            BackupJson.sha256(BackupJson.unwrap(profilesFile,ProfileBackup.FORMAT)),
            BackupJson.sha256(BackupJson.unwrap(queueFile,QueueBackup.FORMAT)))
    }
    fun prepare(existingProfiles:ProfileData,existingQueue:Snapshot,source:StagedSources):StagedPlan {
        require((existingQueue.current==null && existingQueue.jobs.isEmpty()) || existingQueue==source.queue) {
            "Очередь отличается от исходной. Автоматический перенос остановлен без замены очереди"
        }
        val merge=ProfileBackup.merge(existingProfiles,source.profiles)
        return StagedPlan(merge,source.profiles,source.queue,source.profilesHash,source.queueHash)
    }

    fun verifiedResult(plan:StagedPlan,savedProfiles:ProfileData,savedQueue:Snapshot):String {
        require(savedProfiles==plan.merge.data && savedQueue==plan.queue) { "Проверка переноса после записи не пройдена" }
        return JsonObject().apply {
            addProperty("verified",true)
            addProperty("profilesSha256",plan.profilesHash);addProperty("queueSha256",plan.queueHash)
            addProperty("profileCount",plan.source.profiles.size);addProperty("jobCount",plan.queue.jobs.size)
            addProperty("hasCurrent",plan.queue.current!=null);addProperty("targetProfileCount",savedProfiles.profiles.size)
        }.toString()
    }
}
