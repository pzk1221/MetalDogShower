package com.panzhikun.metaldogshower.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QrAliasParserTest {
    @Test
    fun extractsOnlyLastPathSegmentWithoutOpeningUrl() {
        assertEquals("ABCD_1234-xy", QrAliasParser.parse("http://26h.fitjs.com/a/ABCD_1234-xy"))
    }

    @Test
    fun acceptsHttpsAndTrailingSlash() {
        assertEquals("DEVICE99", QrAliasParser.parse("https://26h.fitjs.com/DEVICE99/"))
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            QrAliasParser.parse("javascript:DEVICE99")
        }
    }

    @Test
    fun rejectsCredentialsAndMalformedAlias() {
        assertThrows(IllegalArgumentException::class.java) {
            QrAliasParser.parse("https://user@26h.fitjs.com/DEVICE99")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrAliasParser.parse("https://26h.fitjs.com/not%20an%20alias")
        }
    }

    @Test
    fun rejectsAValidLookingAliasFromAnotherHost() {
        assertThrows(IllegalArgumentException::class.java) {
            QrAliasParser.parse("https://example.test/DEVICE99")
        }
    }
}
