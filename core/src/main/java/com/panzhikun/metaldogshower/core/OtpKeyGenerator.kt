package com.panzhikun.metaldogshower.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Implements the exact transformation used by the verified official Android app. */
object OtpKeyGenerator {
    fun generate(phone: String, mark: String): String {
        val source = "$phone:$mark"
        val obscured = buildString(source.length) {
            source.forEachIndexed { index, character ->
                append((character.code xor (index % 5 + 1)).toChar())
            }
        }
        return MessageDigest.getInstance("MD5")
            .digest(obscured.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

