package com.kinodaran.vast.core

import kotlin.test.Test
import kotlin.test.assertTrue

class VastVersionTest {
    @Test
    fun versionIsSemantic() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(VastVersion.CURRENT), VastVersion.CURRENT)
    }
}
