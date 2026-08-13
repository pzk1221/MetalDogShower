package com.panzhikun.metaldogshower.wear.provisioning

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateBusTest {
    @Test
    fun replacementAndClearUseSeparateCredentialScopedSignals() {
        val received = mutableListOf<String>()
        val replacementListener = ConfigReplacedListener { received += "replace:$it" }
        val clearListener = CredentialClearListener { received += "clear:$it" }
        SessionStateBus.registerReplacementListener(replacementListener)
        SessionStateBus.registerClearListener(clearListener)
        try {
            SessionStateBus.notifyConfigReplaced("new-credential")
            SessionStateBus.notifyCredentialCleared("old-credential")

            assertEquals(
                listOf("replace:new-credential", "clear:old-credential"),
                received,
            )
        } finally {
            SessionStateBus.unregisterReplacementListener(replacementListener)
            SessionStateBus.unregisterClearListener(clearListener)
        }
    }
}
