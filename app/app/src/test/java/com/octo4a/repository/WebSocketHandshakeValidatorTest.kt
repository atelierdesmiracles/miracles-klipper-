package com.octo4a.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketHandshakeValidatorTest {
    private val sampleKey = "dGhlIHNhbXBsZSBub25jZQ=="

    @Test
    fun calculatesRfc6455AcceptValue() {
        assertTrue(
            WebSocketHandshakeValidator.expectedAccept(sampleKey) ==
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        )
    }

    @Test
    fun acceptsValidUpgradeResponse() {
        val headers = mapOf(
            "Upgrade" to "websocket",
            "Connection" to "keep-alive, Upgrade",
            "Sec-WebSocket-Accept" to WebSocketHandshakeValidator.expectedAccept(sampleKey)
        )

        assertTrue(
            WebSocketHandshakeValidator.isSuccessfulUpgrade(
                "HTTP/1.1 101 Switching Protocols",
                headers,
                sampleKey
            )
        )
    }

    @Test
    fun rejectsOrdinaryHttpResponse() {
        val headers = mapOf(
            "Upgrade" to "websocket",
            "Connection" to "Upgrade",
            "Sec-WebSocket-Accept" to WebSocketHandshakeValidator.expectedAccept(sampleKey)
        )

        assertFalse(
            WebSocketHandshakeValidator.isSuccessfulUpgrade(
                "HTTP/1.1 200 OK",
                headers,
                sampleKey
            )
        )
    }

    @Test
    fun rejectsMissingUpgradeToken() {
        val headers = mapOf(
            "Upgrade" to "websocket",
            "Connection" to "keep-alive",
            "Sec-WebSocket-Accept" to WebSocketHandshakeValidator.expectedAccept(sampleKey)
        )

        assertFalse(
            WebSocketHandshakeValidator.isSuccessfulUpgrade(
                "HTTP/1.1 101 Switching Protocols",
                headers,
                sampleKey
            )
        )
    }

    @Test
    fun rejectsIncorrectAcceptHash() {
        val headers = mapOf(
            "Upgrade" to "websocket",
            "Connection" to "Upgrade",
            "Sec-WebSocket-Accept" to "incorrect"
        )

        assertFalse(
            WebSocketHandshakeValidator.isSuccessfulUpgrade(
                "HTTP/1.1 101 Switching Protocols",
                headers,
                sampleKey
            )
        )
    }
}
