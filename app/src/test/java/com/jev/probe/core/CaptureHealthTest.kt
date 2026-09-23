package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureHealthTest {
    @Test fun permissionAloneDoesNotMeanConnected() {
        val service = Any()
        CaptureHealth.connected(service)
        CaptureHealth.disconnected(service)
        assertEquals(CaptureHealth.State.DISCONNECTED, CaptureHealth.state(true))
        assertEquals(CaptureHealth.State.UNAUTHORIZED, CaptureHealth.state(false))
    }

    @Test fun lateDisconnectDoesNotClearReplacementService() {
        val old = Any()
        val replacement = Any()
        CaptureHealth.connected(old)
        CaptureHealth.connected(replacement)
        CaptureHealth.disconnected(old)
        assertEquals(CaptureHealth.State.CONNECTED, CaptureHealth.state(true))
        CaptureHealth.disconnected(replacement)
        assertEquals(CaptureHealth.State.DISCONNECTED, CaptureHealth.state(true))
    }

    @Test fun keepAliveFailureDoesNotClaimReaderDisconnected() {
        val service = Any()
        CaptureHealth.connected(service)
        CaptureHealth.keepAliveFailed("SecurityException")
        assertEquals(CaptureHealth.State.CONNECTED, CaptureHealth.state(true))
        assertEquals("SecurityException", CaptureHealth.keepAliveFailure)
        CaptureHealth.keepAliveStarted()
        assertNull(CaptureHealth.keepAliveFailure)
        CaptureHealth.disconnected(service)
    }
}
