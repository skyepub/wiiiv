package io.wiiiv.server.session

import io.wiiiv.governor.ConversationSession
import io.wiiiv.governor.ConversationalGovernor
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Session Manager - 사용자별 세션 관리
 *
 * userId → Set<sessionId> 매핑으로 사용자별 세션을 관리한다.
 * ConversationalGovernor의 세션 생명주기를 래핑한다.
 */
class SessionManager(private val governor: ConversationalGovernor) {

    // userId → Set<sessionId>
    private val userSessions = ConcurrentHashMap<String, MutableSet<String>>()

    // sessionId → SessionInfo
    private val sessionInfos = ConcurrentHashMap<String, SessionInfo>()

    /**
     * 새 세션 생성
     */
    fun createSession(userId: String): ConversationSession {
        val session = governor.startSession()
        val sessionId = session.sessionId

        userSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(sessionId)
        sessionInfos[sessionId] = SessionInfo(
            sessionId = sessionId,
            userId = userId,
            createdAt = Instant.now().toString()
        )

        return session
    }

    /**
     * 세션 조회 (Governor 위임)
     */
    fun getSession(sessionId: String): ConversationSession? {
        return governor.getSession(sessionId)
    }

    /**
     * 세션 메타데이터 조회
     */
    fun getSessionInfo(sessionId: String): SessionInfo? {
        return sessionInfos[sessionId]
    }

    /**
     * 소유권 확인
     */
    fun isOwner(userId: String, sessionId: String): Boolean {
        return sessionInfos[sessionId]?.userId == userId
    }

    /**
     * 사용자의 세션 목록
     */
    fun listUserSessions(userId: String): List<SessionInfo> {
        val sessionIds = userSessions[userId] ?: return emptyList()
        return sessionIds.mapNotNull { sessionInfos[it] }
    }

    /**
     * 세션 종료
     */
    fun endSession(userId: String, sessionId: String) {
        governor.endSession(sessionId)
        userSessions[userId]?.remove(sessionId)
        sessionInfos.remove(sessionId)
    }
}

/**
 * 세션 메타데이터
 */
data class SessionInfo(
    val sessionId: String,
    val userId: String,
    val createdAt: String
)
