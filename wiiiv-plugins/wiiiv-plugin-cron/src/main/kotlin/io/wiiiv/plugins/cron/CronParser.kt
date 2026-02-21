package io.wiiiv.plugins.cron

import java.time.ZonedDateTime

/**
 * 간단 크론 파서 — 5필드 (분 시 일 월 요일)
 *
 * 지원: 숫자, *(every), 쉼표(1,3,5), 범위(1-5), 간격(아스터/N)
 * 예: "0 9 * * 1-5" = 평일 오전 9시
 */
class CronParser(expression: String) {

    private val fields: List<Set<Int>>

    init {
        val parts = expression.trim().split("\\s+".toRegex())
        require(parts.size == 5) { "Cron expression must have 5 fields: $expression" }
        fields = listOf(
            parseField(parts[0], 0, 59),   // minute
            parseField(parts[1], 0, 23),   // hour
            parseField(parts[2], 1, 31),   // day of month
            parseField(parts[3], 1, 12),   // month
            parseField(parts[4], 0, 6)     // day of week (0=Sun)
        )
    }

    /**
     * 주어진 시각 이후 다음 실행 시각 계산
     */
    fun nextExecution(from: ZonedDateTime): ZonedDateTime {
        var candidate = from.plusMinutes(1).withSecond(0).withNano(0)
        var safety = 0
        while (safety++ < 525_600) { // 1년 = 525,600분
            if (matches(candidate)) return candidate
            candidate = candidate.plusMinutes(1)
        }
        throw IllegalStateException("No matching time found within 1 year")
    }

    fun matches(time: ZonedDateTime): Boolean {
        return time.minute in fields[0] &&
                time.hour in fields[1] &&
                time.dayOfMonth in fields[2] &&
                time.monthValue in fields[3] &&
                (time.dayOfWeek.value % 7) in fields[4] // java DayOfWeek: 1=Mon..7=Sun → %7: 0=Sun
    }

    private fun parseField(field: String, min: Int, max: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        for (part in field.split(",")) {
            when {
                part == "*" -> result.addAll(min..max)
                part.contains("/") -> {
                    val (base, stepStr) = part.split("/", limit = 2)
                    val step = stepStr.toInt()
                    val start = if (base == "*") min else base.toInt()
                    var i = start
                    while (i <= max) { result.add(i); i += step }
                }
                part.contains("-") -> {
                    val (lo, hi) = part.split("-", limit = 2)
                    result.addAll(lo.toInt()..hi.toInt())
                }
                else -> result.add(part.toInt())
            }
        }
        return result
    }
}
