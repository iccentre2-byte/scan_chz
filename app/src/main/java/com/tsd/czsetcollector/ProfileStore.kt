package com.tsd.czsetcollector

import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File

class ProfileStore(prefs: SharedPreferences, dir: File) {
    private val original = AtomicFile(File(dir, "profiles-1.3.0-backup.json"))
    val repository = ProfileRepository(object : ProfilePreferences {
        override fun read():StoredProfiles {
            val raw=prefs.getString("profiles_json",null)
            if(raw==null && (original.baseFile.exists() || File(original.baseFile.path+".bak").exists()))
                throw ProfileStorageException("Профили отсутствуют, но сохранилась исходная резервная копия. Не удаляйте приложение и его данные")
            return StoredProfiles(raw,prefs.getInt("selected_profile_index",0),prefs.getInt("profile_schema_version",0),
                prefs.getString("profile_130_backup_sha256",null))
        }
        override fun write(value: StoredProfiles): Boolean = prefs.edit()
            .putString("profiles_json", value.raw).putInt("selected_profile_index", value.selected)
            .putInt("profile_schema_version", value.schema).putString("profile_130_backup_sha256", value.originalHash).commit()
    }) { encoded ->
        if (!original.baseFile.exists() && !File(original.baseFile.path + ".bak").exists()) {
            val stream = original.startWrite()
            try { stream.write(encoded.toByteArray(Charsets.UTF_8)); original.finishWrite(stream) }
            catch (e: Exception) { original.failWrite(stream); throw e }
        }
        val saved = String(original.readFully(), Charsets.UTF_8)
        require(ProfileBackup.decode(saved) == ProfileBackup.decode(encoded)) { "Исходная резервная копия профилей отличается; перенос остановлен" }
        BackupJson.sha256(saved)
    }
}
