package com.panzhikun.metaldogshower.qr

import java.net.URI

object QrAliasParser {
    private val aliasPattern = Regex("^[A-Za-z0-9_-]{4,64}$")

    /** Parses locally. This function never opens or requests the QR URL. */
    fun parse(rawValue: String): String {
        val value = rawValue.trim()
        require(value.isNotEmpty()) { "请先扫描或粘贴二维码链接" }

        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw IllegalArgumentException("二维码链接格式不正确")
        require(uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) {
            "仅支持 http/https 二维码链接"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "二维码链接格式不正确"
        }
        require(uri.host.equals(OFFICIAL_QR_HOST, ignoreCase = true)) {
            "这不是金属狗官方二维码链接"
        }

        val segment = uri.rawPath
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            .orEmpty()
        require(aliasPattern.matches(segment)) {
            "二维码中没有可识别的设备别名"
        }
        return segment
    }

    private const val OFFICIAL_QR_HOST = "26h.fitjs.com"
}
