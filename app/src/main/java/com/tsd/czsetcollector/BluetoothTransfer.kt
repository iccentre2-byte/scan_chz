package com.tsd.czsetcollector

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class BluetoothTransfer(val profile: OrganizationProfile, val job: Job?)

internal object BluetoothTransfers {
    private const val FORMAT = "cz-bluetooth-transfer"
    private val gson = Gson()

    fun canShare(job: Job) = job.status == "LOCAL_READY" && !job.uploaded && job.documentId == null

    fun encode(transfer: BluetoothTransfer): String {
        val payload = gson.toJson(transfer)
        val encoded = BackupJson.wrap(FORMAT, payload)
        check(decode(encoded) == transfer) { "Не удалось проверить файл передачи" }
        return encoded
    }

    fun decode(raw: String): BluetoothTransfer {
        val root = BackupJson.objectValue(BackupJson.parse(BackupJson.unwrap(raw, FORMAT)))
        BackupJson.keys(root, setOf("profile"), setOf("job"))
        val profile = ProfileBackup.decodeProfile(root["profile"])
        val job = root["job"]?.let { element ->
            val queue = JsonObject().apply { add("jobs", JsonArray().apply { add(element) }) }
            val parsed = QueueBackup.parsePayload(queue.toString()).jobs.single()
            require(canShare(parsed)) { "Передать можно только готовый набор до отправки на ПК" }
            require(WorkSession.matches(profile, parsed)) { "Набор относится к другому профилю" }
            parsed
        }
        return BluetoothTransfer(profile, job)
    }

    fun receive(before: Snapshot, transfer: BluetoothTransfer, now: Long): Snapshot {
        val job = transfer.job ?: return before
        require(before.current == null && SessionAccounting.active(before) == null) { "Сначала завершите рабочую сессию" }
        require(canShare(job) && WorkSession.matches(transfer.profile, job)) { "Набор не подходит для переноса" }
        require(before.jobs.size < 10000) { "В очереди достигнут предел количества наборов" }
        require(before.jobs.none { it.id == job.id }) { "Этот набор уже есть на ТСД" }
        val codes = (job.children + job.setCode).toSet()
        require(before.jobs.none {
            it.environment == job.environment && it.status != "CANCELLED_LOCAL" &&
                (it.setCode in codes || it.children.any(codes::contains))
        }) { "Код набора или его пачки уже есть в очереди ТСД" }
        val session = SessionAccounting.begin(before, transfer.profile, now)
        val attached = SessionAccounting.attachCurrent(session.copy(current = job))
        return SessionAccounting.finish(attached.copy(current = null, jobs = (before.jobs + job).toMutableList()), now)
    }
}
