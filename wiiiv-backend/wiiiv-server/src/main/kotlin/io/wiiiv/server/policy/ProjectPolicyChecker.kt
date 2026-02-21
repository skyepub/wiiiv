package io.wiiiv.server.policy

import io.wiiiv.audit.AuditStore
import io.wiiiv.platform.store.PlatformStore
import java.time.LocalDate
import java.time.ZoneOffset

data class PolicyCheckResult(
    val allowed: Boolean,
    val message: String? = null,
    val currentCount: Long = 0,
    val limit: Int? = null
)

object ProjectPolicyChecker {
    fun checkDailyLimit(
        projectId: Long,
        platformStore: PlatformStore?,
        auditStore: AuditStore?
    ): PolicyCheckResult {
        if (platformStore == null) return PolicyCheckResult(allowed = true)
        val policy = platformStore.getPolicy(projectId) ?: return PolicyCheckResult(allowed = true)
        val maxRequests = policy.maxRequestsPerDay ?: return PolicyCheckResult(allowed = true)
        if (auditStore == null) return PolicyCheckResult(allowed = true)

        val todayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant()
        val count = auditStore.countByProject(projectId, todayStart)

        return if (count >= maxRequests) {
            PolicyCheckResult(false, "Daily request limit exceeded ($count/$maxRequests)", count, maxRequests)
        } else {
            PolicyCheckResult(true, null, count, maxRequests)
        }
    }
}
