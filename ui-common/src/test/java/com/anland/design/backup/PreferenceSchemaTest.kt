package com.anland.design.backup
import org.junit.Assert.assertThrows
import org.junit.Test
class PreferenceSchemaTest {
    @Test fun `restored settings keep expected runtime types and bounds`() {
        PreferenceSchema.validate("shell","launch_user.Debian","seiun")
        PreferenceSchema.validate("shell","console_font_sp",14)
        PreferenceSchema.validate("awl","close_on_task_removed",true)
        PreferenceSchema.validate("awl","show_container_name",true)
        assertThrows(IllegalArgumentException::class.java) { PreferenceSchema.validate("awl","show_container_name","true") }
        assertThrows(IllegalArgumentException::class.java) { PreferenceSchema.validate("awl","ime_mode","1") }
        assertThrows(IllegalArgumentException::class.java) { PreferenceSchema.validate("shell","console_font_sp",999) }
        assertThrows(IllegalArgumentException::class.java) { PreferenceSchema.validate("shell","history","secret") }
        assertThrows(IllegalArgumentException::class.java) { PreferenceSchema.validate("github_session","token","secret") }
    }
}
