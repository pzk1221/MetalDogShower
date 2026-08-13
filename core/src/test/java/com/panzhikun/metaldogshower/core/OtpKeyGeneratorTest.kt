package com.panzhikun.metaldogshower.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OtpKeyGeneratorTest {
    @Test
    fun matchesVerifiedOfficialXorThenMd5Algorithm() {
        assertEquals(
            "ccdcc0281cb9e1f6a04590f56928d667",
            OtpKeyGenerator.generate(phone = "13800138000", mark = "mark-123"),
        )
    }
}

