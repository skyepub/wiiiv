package io.wiiiv.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * ConnectionArgs 파싱 테스트
 */
class ConnectionArgsTest {

    @Test
    fun `empty args should produce auto-login localhost`() {
        val result = ConnectionArgs.parse(emptyArray())
        assertNull(result.username)
        assertEquals("localhost", result.host)
        assertEquals(8235, result.port)
        assertTrue(result.isAutoLogin)
        assertEquals("http://localhost:8235", result.toServerUrl())
    }

    @Test
    fun `username only should produce local manual login`() {
        val result = ConnectionArgs.parse(arrayOf("admin"))
        assertEquals("admin", result.username)
        assertEquals("localhost", result.host)
        assertEquals(8235, result.port)
        assertFalse(result.isAutoLogin)
    }

    @Test
    fun `user at host should produce remote default port`() {
        val result = ConnectionArgs.parse(arrayOf("admin@192.168.1.10"))
        assertEquals("admin", result.username)
        assertEquals("192.168.1.10", result.host)
        assertEquals(8235, result.port)
        assertFalse(result.isAutoLogin)
        assertEquals("http://192.168.1.10:8235", result.toServerUrl())
    }

    @Test
    fun `user at host with port should produce remote custom port`() {
        val result = ConnectionArgs.parse(arrayOf("admin@192.168.1.10:9000"))
        assertEquals("admin", result.username)
        assertEquals("192.168.1.10", result.host)
        assertEquals(9000, result.port)
        assertFalse(result.isAutoLogin)
        assertEquals("http://192.168.1.10:9000", result.toServerUrl())
    }

    @Test
    fun `invalid port should fallback to default 8235`() {
        val result = ConnectionArgs.parse(arrayOf("admin@10.0.0.1:abc"))
        assertEquals("admin", result.username)
        assertEquals("10.0.0.1", result.host)
        assertEquals(8235, result.port)
        assertFalse(result.isAutoLogin)
    }

    @Test
    fun `credentialKey should be host colon port`() {
        val result = ConnectionArgs.parse(arrayOf("user@myserver.com:3000"))
        assertEquals("myserver.com:3000", result.credentialKey())
    }

    @Test
    fun `default credentialKey should be localhost colon 8235`() {
        val result = ConnectionArgs.parse(emptyArray())
        assertEquals("localhost:8235", result.credentialKey())
    }
}
