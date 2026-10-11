package com.tsd.czsetcollector

import com.google.gson.Gson

data class StoredProfiles(val raw: String?, val selected: Int, val schema: Int = 0, val originalHash: String? = null)
interface ProfilePreferences {
    fun read(): StoredProfiles
    /** Writes only the four profile keys as one transaction; PC credentials and session keys are out of scope. */
    fun write(value: StoredProfiles): Boolean
}
class ProfileStorageException(message:String):IllegalStateException(message)

/** Verifies the complete stored value and rolls back a failed write before accepting an import. */
class ProfileRepository(private val storage: ProfilePreferences, private val archive: (String) -> String) {
    private val gson = Gson()
    private fun data(value: StoredProfiles): ProfileData {
        if(value.raw!=null)return ProfileBackup.legacy(value.raw,value.selected)
        require(value.schema==0 && value.originalHash==null) { "Сохранённые профили отсутствуют после переноса. Исходную резервную копию нельзя перезаписывать" }
        return ProfileData(listOf(OrganizationProfile(title = "Основной профиль")), 0)
    }

    @Synchronized fun load(): ProfileData {
        val before = storage.read()
        require(before.schema in 0..1) { "Неизвестная версия хранения профилей" }
        val profiles = data(before)
        if (before.raw != null && before.schema == 0) {
            // The original 1.3.0 profiles_json stays byte-for-byte unchanged during migration.
            val encoded = ProfileBackup.encode(profiles)
            val hash = archive(encoded)
            require(hash == BackupJson.sha256(encoded)) { "Не подтверждена исходная копия профилей" }
            commit(before, before.copy(schema = 1, originalHash = hash))
        }
        return profiles
    }

    @Synchronized fun save(profiles: ProfileData) {
        ProfileBackup.decode(ProfileBackup.encode(profiles))
        load() // Confirm the 1.3.0 source archive before a first edit/import.
        val before = storage.read()
        // Never overwrite a malformed legacy value with a new empty/default template.
        data(before)
        commit(before, before.copy(raw = gson.toJson(profiles.profiles), selected = profiles.selectedIndex, schema = 1))
        require(data(storage.read()) == profiles) { "Проверка сохранённых профилей не пройдена" }
    }

    @Synchronized fun importBackup(raw: String): ProfileMerge {
        val incoming = ProfileBackup.decode(raw) // Validate the entire file before any mutation.
        load()
        val before = storage.read()
        val merged = ProfileBackup.merge(data(before), incoming)
        commit(before, before.copy(raw = gson.toJson(merged.data.profiles), selected = merged.data.selectedIndex, schema = 1))
        return merged
    }

    private fun commit(before: StoredProfiles, after: StoredProfiles) {
        check(storage.read() == before) { "Профили изменились во время импорта; повторите операцию" }
        val success = try { storage.write(after) } catch(_:Exception) { false }
        val verified = try { success && storage.read() == after } catch(_:Exception) { false }
        if(verified)return
        val restored = try { storage.write(before) && storage.read() == before } catch(_:Exception) { false }
        if(!restored)throw ProfileStorageException("Ошибка хранения профилей. Исходные данные в резервной копии; не удаляйте приложение")
        throw IllegalStateException("Профили не сохранены; исходные профили восстановлены")
    }
}
