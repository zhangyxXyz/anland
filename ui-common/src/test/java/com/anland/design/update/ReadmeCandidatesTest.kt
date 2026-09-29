package com.anland.design.update

import org.junit.Assert.*
import org.junit.Test

class ReadmeCandidatesTest {
    @Test fun `selected simplified Chinese precedes default English`() {
        assertEquals(listOf("README.zh-CN.md", "README.zh.md", "README.md"), readmeCandidates("zh-CN"))
    }
    @Test fun `English uses the default README`() {
        assertEquals(listOf("README.md"), readmeCandidates("en-US"))
    }
    @Test fun `other locales fall back without injecting a request path`() {
        assertEquals(listOf("README.ja-JP.md", "README.ja.md", "README.md"), readmeCandidates("ja-JP"))
        assertFalse(readmeCandidates("../../secrets").any { '/' in it })
        assertTrue(readmeCandidates("zh-TW").contains("README.zh-CN.md"))
    }
}
