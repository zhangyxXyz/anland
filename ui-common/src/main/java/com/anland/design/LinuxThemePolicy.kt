package com.anland.design

/** Fixed vocabulary only; no caller-controlled paths or shell fragments. */
object LinuxThemePolicy {
    fun claimsOnResume(activityName: String): Boolean = activityName !in setOf(
        "SessionPreflightActivity", "OpenWindowActivity", "AppLaunchActivity",
        "AwlWindowActivity", "AwlDialogActivity")

    fun command(owner: String, mode: String, claim: Boolean, monitorPath: String? = null): String {
        require(owner in setOf("com.anland.shell", "com.anlandnext"))
        val policy = when (mode) { "System" -> "system"; "Light" -> "light"; "Dark" -> "dark"; else -> error("Unknown theme mode") }
        return """
            set -eu
            rt=${'$'}(sed -n 's/.*"runtime_dir": *"\([^"]*\)".*/\1/p' /data/adb/modules/anland-awl/config.json 2>/dev/null | head -1)
            rt=${'$'}{rt:-/data/local/tmp/awl}
            state="${'$'}rt/appearance"
            [ -d "${'$'}rt" ] && [ ! -L "${'$'}state" ]
            mkdir -p "${'$'}state"
            chmod 755 "${'$'}state"
            ${monitorPath?.let { "sh '" + it.replace("'", "'\"'\"'") + "' \"${'$'}rt\" --ensure" } ?: ":"}
            ${if (claim) ":" else "[ \"${'$'}(head -1 \"${'$'}state/app-theme\" 2>/dev/null)\" = '$owner' ] || exit 0"}
            tmp="${'$'}state/app-theme.tmp.${'$'}${'$'}"
            trap 'rm -f "${'$'}tmp"' EXIT
            printf '%s\n%s\n' '$owner' '$policy' > "${'$'}tmp"
            if cmp -s "${'$'}tmp" "${'$'}state/app-theme"; then exit 0; fi
            chmod 644 "${'$'}tmp"
            mv -f "${'$'}tmp" "${'$'}state/app-theme"
        """.trimIndent()
    }
}
