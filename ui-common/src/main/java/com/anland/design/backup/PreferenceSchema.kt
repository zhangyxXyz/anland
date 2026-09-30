package com.anland.design.backup

internal object PreferenceSchema {
    fun validate(namespace: String, key: String, value: Any) {
        val valid=when(namespace) {
            "anland_appearance" -> when(key) {
                "dynamic","glass","useCustom" -> value is Boolean
                "mode","color","custom" -> value is String
                else -> false
            }
            "language_settings" -> key=="language_tag" && value is String
            "shell" -> when {
                key=="console_font_sp" -> value is Int && value in 6..28
                key=="active_container" || key.startsWith("launch_user.") || key.startsWith("launch_credential.") || key.startsWith("launch_env.") -> value is String
                else -> false
            }
            "awl" -> when(key) {
                "window_scope" -> value is String && (value in setOf("all","unknown") || value.startsWith("container:"))
                "ime_mode" -> value is Int && value in 0..1
                "close_on_task_removed", "show_container_name" -> value is Boolean
                else -> false
            }
            else -> false
        }
        require(valid) { "Invalid preference: $namespace/$key" }
    }
}
