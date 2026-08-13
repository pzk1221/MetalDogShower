package com.panzhikun.metaldogshower.wear.provisioning

internal object ProvisioningProtocol {
    const val REQUEST_PATH = "/provision/request"
    const val PUBLIC_KEY_PATH = "/provision/public-key"
    const val ENVELOPE_PATH = "/provision/envelope"
    const val RESULT_PATH = "/provision/result"
    const val SESSION_CLEAR_PATH = "/session/clear"
    const val SESSION_INVALID_PATH = "/session/invalid"
    const val SESSION_SYNC_REQUEST_PATH = "/session/sync-request"

    const val VERSION = 1
    const val ENVELOPE_AAD_PREFIX = "MetalDogShowerProvisioning:v1"
    const val LOCAL_AAD = "MetalDogShowerLocalConfig:v1"

    fun envelopeAad(requestId: String, challengeBase64: String): ByteArray =
        "$ENVELOPE_AAD_PREFIX:$requestId:$challengeBase64".toByteArray(Charsets.UTF_8)
}
