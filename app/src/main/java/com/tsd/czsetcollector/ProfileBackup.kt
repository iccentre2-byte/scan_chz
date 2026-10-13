package com.tsd.czsetcollector

import com.google.gson.Gson
import com.google.gson.JsonElement

data class ProfileData(val profiles: List<OrganizationProfile>, val selectedIndex: Int)
data class ProfileMerge(val data: ProfileData, val added: Int, val unchanged: Int, val renamed: Int)

/** Profiles only: this type has no PC connection, signing data, or queue fields. */
object ProfileBackup {
    const val FORMAT = "cz-profiles-backup"
    private val gson = Gson()
    internal fun decodeProfile(value: JsonElement?): OrganizationProfile = profile(requireNotNull(value))

    fun encode(data: ProfileData): String {
        val payload = gson.toJson(data)
        require(decodePayload(payload) == data) { "Не удалось проверить экспорт профилей" }
        return BackupJson.wrap(FORMAT, payload)
    }
    fun decode(raw: String): ProfileData = decodePayload(BackupJson.unwrap(raw, FORMAT))
    fun legacy(rawProfiles: String, selected: Int): ProfileData {
        val list = profiles(BackupJson.parse(rawProfiles))
        return ProfileData(list, if (list.isEmpty()) 0 else selected.coerceIn(0, list.lastIndex))
    }
    private fun decodePayload(raw: String): ProfileData {
        val root = BackupJson.objectValue(BackupJson.parse(raw))
        BackupJson.keys(root, setOf("profiles", "selectedIndex"))
        val list = profiles(root["profiles"]); val selected = BackupJson.integer(root["selectedIndex"])
        require(if (list.isEmpty()) selected == 0 else selected in list.indices) { "Неверный выбранный профиль" }
        return ProfileData(list, selected)
    }
    private fun profiles(value: JsonElement?): List<OrganizationProfile> {
        require(value != null && value.isJsonArray && value.asJsonArray.size() <= 1000) { "Неверный список профилей" }
        return value.asJsonArray.map { profile(it) }
    }
    private fun profile(value: JsonElement): OrganizationProfile {
        val p = BackupJson.objectValue(value)
        BackupJson.keys(p, setOf("inn", "pg", "title", "environment", "template"))
        val t = BackupJson.objectValue(p["template"])
        BackupJson.keys(t, setOf("setGtin", "expected"))
        val expected = BackupJson.objectValue(t["expected"])
        require(expected.size() <= 100) { "Слишком большой состав профиля" }
        val quantities = linkedMapOf<String, Int>()
        for ((gtin, amount) in expected.entrySet()) {
            require(gtin.length <= 64) { "Слишком длинный GTIN" }
            val n = BackupJson.integer(amount)
            require(n in 1..1000) { "Неверное количество в составе" }
            quantities[gtin] = n
        }
        require(quantities.values.sum() <= 1000) { "Слишком много пачек в составе" }
        // Incomplete/legacy profiles are preserved. WorkSession validates business rules before scanning.
        return OrganizationProfile(BackupJson.string(p, "inn", 64), BackupJson.string(p, "pg", 64),
            BackupJson.string(p, "title"), BackupJson.string(p, "environment", 64),
            Template(BackupJson.string(t, "setGtin", 64), quantities))
    }

    /** Build an entire import before touching storage. A name collision never replaces a template. */
    fun merge(existing: ProfileData, incoming: ProfileData): ProfileMerge {
        val next = existing.profiles.map { it.copy(template = it.template.copy(expected = it.template.expected.toMap())) }.toMutableList()
        var added = 0; var unchanged = 0; var renamed = 0
        val selectedMap = mutableListOf<Int>()
        for (original in incoming.profiles) {
            val suffixPattern = Regex(Regex.escape(original.title.take(480)) + " \\(импорт [0-9]+\\)")
            val exact = next.indexOfFirst { it == original ||
                (suffixPattern.matches(it.title) && it.copy(title = original.title) == original) }
            if (exact >= 0) { unchanged++; selectedMap.add(exact); continue }
            var title = original.title
            if (next.any { it.title == title }) {
                var suffix = 1
                do { title = original.title.take(480) + " (импорт ${suffix++})" } while (next.any { it.title == title })
                renamed++
            }
            next.add(original.copy(title = title, template = original.template.copy(expected = original.template.expected.toMap())))
            selectedMap.add(next.lastIndex); added++
        }
        require(next.size <= 1000) { "Слишком много профилей после импорта" }
        val selected = selectedMap.getOrNull(incoming.selectedIndex) ?: existing.selectedIndex
        val result = ProfileData(next, selected)
        decode(encode(result)) // Whole result, including unchanged profiles, must be exportable before committing.
        return ProfileMerge(result, added, unchanged, renamed)
    }
}
