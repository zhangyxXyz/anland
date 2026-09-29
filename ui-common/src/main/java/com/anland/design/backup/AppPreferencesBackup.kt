package com.anland.design.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Portable typed preferences, not a copy of Android's live XML files or Keystore. */
class AppPreferencesBackup(private val context: Context, private val namespaces: Set<String>) {
    fun export(): BackupInput {
        val files=JSONObject()
        for(name in namespaces) {
            val entries=JSONObject()
            context.getSharedPreferences(name,Context.MODE_PRIVATE).all.forEach { (key,value) ->
                // Commands may contain passwords; command history is not configuration.
                if(key=="history") return@forEach
                val entry=when(value) {
                    is String -> JSONObject().put("type","string").put("value",value)
                    is Boolean -> JSONObject().put("type","boolean").put("value",value)
                    is Int -> JSONObject().put("type","int").put("value",value)
                    is Long -> JSONObject().put("type","long").put("value",value)
                    is Float -> JSONObject().put("type","float").put("value",value.toDouble())
                    is Set<*> -> JSONObject().put("type","strings").put("value",JSONArray(value.toList()))
                    else -> error("Unsupported preference type")
                }
                entries.put(key,entry)
            }
            files.put(name,entries)
        }
        return BackupInput.Serialized("app/preferences.json",JSONObject().put("schema",1).put("package",context.packageName).put("files",files).toString())
    }
    fun decode(backup: RestoredBackup): Map<String,Map<String,Any>> {
        val root=JSONObject(backup.text("app/preferences.json") ?: error("Missing Anland settings"))
        require(root.getInt("schema")==1 && root.getString("package")==context.packageName) { "Backup belongs to another application or format" }
        val files=root.getJSONObject("files")
        return files.keys().asSequence().associateWith { name ->
            require(name in namespaces) { "Unknown preference namespace" }
            val entries=files.getJSONObject(name)
            entries.keys().asSequence().associateWith { key ->
                val entry=entries.getJSONObject(key)
                val value = when(entry.getString("type")) {
                    "string" -> entry.getString("value")
                    "boolean" -> entry.getBoolean("value")
                    "int" -> entry.getInt("value")
                    "long" -> entry.getLong("value")
                    "float" -> entry.getDouble("value").toFloat().also { require(it.isFinite()) }
                    "strings" -> entry.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    else -> error("Unsupported preference type")
                }
                PreferenceSchema.validate(name,key,value)
                value
            }
        }
    }
    fun restore(values: Map<String,Map<String,Any>>) {
        for((name,entries) in values) {
            require(name in namespaces)
            val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
            val editor=prefs.edit()
            // History is excluded from backup and must also survive restoring settings.
            prefs.all.keys.filterNot { name=="shell" && it=="history" }.forEach(editor::remove)
            for((key,value) in entries) when(value) {
                is String -> editor.putString(key,value)
                is Boolean -> editor.putBoolean(key,value)
                is Int -> editor.putInt(key,value)
                is Long -> editor.putLong(key,value)
                is Float -> editor.putFloat(key,value)
                is Set<*> -> editor.putStringSet(key,value.map { it as String }.toSet())
            }
            check(editor.commit()) { "Cannot save restored settings" }
        }
    }
}
