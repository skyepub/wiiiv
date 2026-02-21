package io.wiiiv.plugins.cron

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * 인메모리 스케줄 저장소
 *
 * 서버 재시작 시 스케줄 소멸 (D-2 범위, 영속화는 미래)
 */
class ScheduleStore {

    data class ScheduleEntry(
        val jobId: String,
        val cronExpr: String,
        val callbackUrl: String,
        val payload: String?,
        val future: ScheduledFuture<*>
    )

    private val jobs = ConcurrentHashMap<String, ScheduleEntry>()

    fun put(entry: ScheduleEntry) {
        jobs[entry.jobId] = entry
    }

    fun get(jobId: String): ScheduleEntry? = jobs[jobId]

    fun remove(jobId: String): ScheduleEntry? = jobs.remove(jobId)

    fun all(): List<ScheduleEntry> = jobs.values.toList()

    val size: Int get() = jobs.size
}
