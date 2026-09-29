package com.anland.design

import org.junit.Assert.*
import org.junit.Test

class LinuxThemePolicyTest {
    @Test fun `unknown package and shell input are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { LinuxThemePolicy.command("other.app", "System", true) }
        assertThrows(IllegalStateException::class.java) { LinuxThemePolicy.command("com.anland.shell", "Dark; id", true) }
    }
    @Test fun `following the system persists a policy instead of a stale dark snapshot`() {
        val script = LinuxThemePolicy.command("com.anland.shell", "System", true)
        assertTrue(script.contains("'system'"))
        assertFalse(script.contains("dumpsys"))
    }
    @Test fun `background changes require existing ownership`() {
        assertTrue(LinuxThemePolicy.command("com.anlandnext", "Dark", false).contains("|| exit 0"))
        assertFalse(LinuxThemePolicy.command("com.anlandnext", "Dark", true).contains("|| exit 0"))
    }
}
