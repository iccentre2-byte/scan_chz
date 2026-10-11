package com.tsd.czsetcollector

import com.google.gson.Gson

/** A separate, integrity-checked queue transfer. Its original job IDs and delivery state are retained. */
object QueueBackup {
    const val FORMAT = "cz-queue-backup"
    private val gson = Gson()
    private val states = setOf("COLLECTING", "LOCAL_READY", "WAITING_SIGNATURE", "SUBMITTING", "SUBMITTED",
        "PROCESSING", "VERIFIED", "REJECTED", "UNKNOWN", "BLOCKED", "CANCELLED_LOCAL", "CHECK_REQUIRED")

    fun encode(snapshot: Snapshot): String {
        val payload = gson.toJson(snapshot)
        require(parsePayload(payload) == snapshot) { "Проверка экспорта очереди не пройдена" }
        return BackupJson.wrap(FORMAT, payload)
    }
    fun decode(raw: String): Snapshot = parsePayload(BackupJson.unwrap(raw, FORMAT))

    fun parsePayload(raw: String): Snapshot {
        val root = BackupJson.objectValue(BackupJson.parse(raw))
        BackupJson.keys(root, setOf("jobs"), setOf("current"))
        val jobs = root["jobs"]
        require(jobs != null && jobs.isJsonArray && jobs.asJsonArray.size() <= 10000) { "Неполный/слишком большой список заданий" }
        val current = root["current"]
        val all = jobs.asJsonArray.toList() + if (current != null && !current.isJsonNull) listOf(current) else emptyList()
        for (element in all) {
            val j = BackupJson.objectValue(element)
            BackupJson.keys(j, setOf("schemaVersion", "id", "environment", "inn", "pg", "template", "setCode", "children", "status", "detail", "uploaded"), setOf("documentId"))
            require(BackupJson.integer(j["schemaVersion"]) == 1) { "Неизвестная версия задания" }
            java.util.UUID.fromString(BackupJson.string(j, "id", 36))
            listOf("environment", "inn", "pg", "setCode", "status").forEach { BackupJson.string(j, it) }
            BackupJson.string(j, "detail", 100000)
            j["documentId"]?.takeUnless { it.isJsonNull }?.let { BackupJson.string(j, "documentId", 512) }
            BackupJson.boolean(j["uploaded"])
            require(BackupJson.string(j, "status") in states) { "Неизвестный статус задания" }
            val t = BackupJson.objectValue(j["template"])
            BackupJson.keys(t, setOf("setGtin", "expected")); BackupJson.string(t, "setGtin", 64)
            val expected = BackupJson.objectValue(t["expected"])
            for ((_, value) in expected.entrySet()) BackupJson.integer(value)
            val children = j["children"]
            require(children != null && children.isJsonArray && children.asJsonArray.size() <= 1000) { "Неверный состав задания" }
            require(children.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) { "Повреждены коды пачек" }
        }
        val snapshot = gson.fromJson(root, Snapshot::class.java)
        val parsed = snapshot.jobs.toList() + listOfNotNull(snapshot.current)
        require(parsed.map { it.id }.toSet().size == parsed.size) { "Повтор идентификатора задания в файле" }
        for (j in parsed) {
            require(j.environment in setOf("production", "sandbox") && j.pg == "grocery" && Codes.inn(j.inn)) { "Неподдерживаемое задание в очереди" }
            require(Codes.normalize(j.setCode) == j.setCode && j.setCode.substring(2, 16) == j.template.setGtin) { "Повреждён код набора" }
            val expected = Codes.composition(j.template.expected.entries.joinToString("\n") { "${it.key}=${it.value}" })
            require(j.children.distinct().size == j.children.size && j.setCode !in j.children) { "Повтор кода в наборе" }
            require(j.children.all { Codes.normalize(it) == it && expected.containsKey(it.substring(2, 16)) }) { "Повреждены вложения" }
            val actual = j.children.groupingBy { it.substring(2, 16) }.eachCount()
            require(actual.all { (gtin, n) -> n <= (expected[gtin] ?: 0) }) { "Превышен состав набора" }
            require(j === snapshot.current || actual == expected) { "В очереди есть недоукомплектованный набор" }
        }
        return snapshot
    }

    /** Refuse replacement/merge: importing over a live queue risks a duplicate submission. */
    fun prepareImport(existing: Snapshot, raw: String): Snapshot {
        require(existing.current == null && existing.jobs.isEmpty()) { "Очередь уже содержит наборы. Перенос разрешён только в пустую очередь" }
        return decode(raw)
    }
}
