package com.anland.design

import org.junit.Assert.*
import org.junit.Test

class LinuxThemePolicyTest {
    @Test fun `mapping and focusing Linux windows preserves the controlling app`() {
        for (name in listOf("AppLaunchActivity", "SessionPreflightActivity", "OpenWindowActivity", "AwlWindowActivity", "AwlDialogActivity"))
            assertFalse(name, LinuxThemePolicy.claimsOnResume(name))
        for (name in listOf("ShellActivity", "MainActivity", "WlSettingsActivity"))
            assertTrue(name, LinuxThemePolicy.claimsOnResume(name))
    }
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
    @Test fun `monitor is restored before publishing the policy`() {
        val script = LinuxThemePolicy.command("com.anlandnext", "System", true, "/data/user/0/com.anland.shell/files/android-appearance.sh")
        assertTrue(script.contains("--ensure"))
        assertTrue(script.indexOf("--ensure") < script.indexOf("printf"))
    }
}
