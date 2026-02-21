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
    fun createSession(userId: String, role: String = "OPERATOR", projectId: Long? = null): ConversationSession {
        val session = governor.startSession()
        val sessionId = session.sessionId
        session.projectId = projectId                    // F-4: Governor가 audit에 전파

        userSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(sessionId)
        sessionInfos[sessionId] = SessionInfo(
            sessionId = sessionId,
            userId = userId,
            createdAt = Instant.now().toString(),
            role = role,
            projectId = projectId
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
     *
     * @param projectId null이면 projectId=null인 글로벌 세션만 반환 (F-4 정책)
     *                  non-null이면 해당 프로젝트 세션만 반환
     */
    fun listUserSessions(userId: String, projectId: Long? = null): List<SessionInfo> {
        val sessionIds = userSessions[userId] ?: return emptyList()
        val all = sessionIds.mapNotNull { sessionInfos[it] }
        return all.filter { it.projectId == projectId }
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
    val createdAt: String,
    val role: String = "OPERATOR",  // Phase D: 기본 OPERATOR (ADMIN/OPERATOR/VIEWER)
    val projectId: Long? = null     // F-4: 프로젝트 스코핑
)
