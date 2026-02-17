package io.wiiiv.server

import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.server.session.SessionManager
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * SessionManager 단위 테스트
 */
class SessionManagerTest {

    private val sessionManager = SessionManager(WiiivRegistry.conversationalGovernor)

    @Test
    fun `createSession should return session with valid id`() {
        val session = sessionManager.createSession("user1")
        assertNotNull(session)
        assertNotNull(session.sessionId)
    }

    @Test
    fun `getSession should return created session`() {
        val session = sessionManager.createSession("user1")
        val retrieved = sessionManager.getSession(session.sessionId)
        assertNotNull(retrieved)
        assertEquals(session.sessionId, retrieved.sessionId)
    }

    @Test
    fun `getSession should return null for unknown session`() {
        val retrieved = sessionManager.getSession("non-existent")
        assertNull(retrieved)
    }

    @Test
    fun `getSessionInfo should return metadata`() {
        val session = sessionManager.createSession("user1")
        val info = sessionManager.getSessionInfo(session.sessionId)
        assertNotNull(info)
        assertEquals(session.sessionId, info.sessionId)
        assertEquals("user1", info.userId)
        assertNotNull(info.createdAt)
    }

    @Test
    fun `isOwner should verify ownership`() {
        val session = sessionManager.createSession("user1")
        assertTrue(sessionManager.isOwner("user1", session.sessionId))
        assertFalse(sessionManager.isOwner("user2", session.sessionId))
    }

    @Test
    fun `listUserSessions should return only user's sessions`() {
        val s1 = sessionManager.createSession("userA")
        val s2 = sessionManager.createSession("userA")
        val s3 = sessionManager.createSession("userB")

        val sessionsA = sessionManager.listUserSessions("userA")
        val sessionsB = sessionManager.listUserSessions("userB")

        assertEquals(2, sessionsA.size)
        assertEquals(1, sessionsB.size)
        assertTrue(sessionsA.any { it.sessionId == s1.sessionId })
        assertTrue(sessionsA.any { it.sessionId == s2.sessionId })
        assertEquals(s3.sessionId, sessionsB[0].sessionId)
    }

    @Test
    fun `listUserSessions should return empty for unknown user`() {
        val sessions = sessionManager.listUserSessions("unknown-user")
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `endSession should remove session`() {
        val session = sessionManager.createSession("user1")
        val sessionId = session.sessionId

        sessionManager.endSession("user1", sessionId)

        assertNull(sessionManager.getSession(sessionId))
        assertNull(sessionManager.getSessionInfo(sessionId))
        assertFalse(sessionManager.isOwner("user1", sessionId))
        assertTrue(sessionManager.listUserSessions("user1").isEmpty())
    }

    @Test
    fun `multiple users should have independent sessions`() {
        val s1 = sessionManager.createSession("alice")
        val s2 = sessionManager.createSession("bob")

        // alice는 bob의 세션에 소유자가 아님
        assertFalse(sessionManager.isOwner("alice", s2.sessionId))
        assertFalse(sessionManager.isOwner("bob", s1.sessionId))

        // 각자의 세션만 보임
        assertEquals(1, sessionManager.listUserSessions("alice").size)
        assertEquals(1, sessionManager.listUserSessions("bob").size)

        // alice 세션 삭제해도 bob 세션은 유지
        sessionManager.endSession("alice", s1.sessionId)
        assertEquals(0, sessionManager.listUserSessions("alice").size)
        assertEquals(1, sessionManager.listUserSessions("bob").size)
    }
}
