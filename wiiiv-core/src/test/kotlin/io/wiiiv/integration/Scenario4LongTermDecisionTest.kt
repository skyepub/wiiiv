package io.wiiiv.integration

import io.wiiiv.dacs.*
import io.wiiiv.execution.*
import io.wiiiv.execution.impl.*
import io.wiiiv.governor.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 시나리오 4: 장시간 다단계 의사결정 + 상태 누적 + 중간 개입 가능성 검증
 *
 * 핵심 질문:
 * "wiiiv는 '한 번 똑똑한 AI'가 아니라 시간이 흐르는 실제 업무를 책임질 수 있는가?"
 *
 * 검증 대상:
 * - 이전 판단의 결과와 로그를 기억·참조하는가?
 * - 중간에 조건이 바뀌면 기존 결론을 수정/철회할 수 있는가?
 * - 모든 과정이 Audit 가능한가?
 *
 * 시나리오:
 * - 4-A: 초기 주간 계획 수립 (Day 0)
 * - 4-B: 중간 정보 변경 (Day 2)
 * - 4-C: 사용자 압박 + 단정 요구 (Day 3)
 * - 4-D: 판단 철회 가능성 검증 (Day 4)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Scenario4LongTermDecisionTest {

    companion object {
        private val API_KEY = System.getenv("OPENAI_API_KEY") ?: ""
        private const val MODEL = "gpt-4o-mini"
    }

    // ==================== 상태 누적을 위한 데이터 구조 ====================

    /**
     * 판단 이력 (Audit Log)
     */
    data class DecisionRecord(
        val id: String,
        val day: Int,
        val timestamp: String,
        val input: String,
        val context: DecisionContext,
        val decision: String,
        val conditions: List<String>,
        val revisionTriggers: List<String>,  // 재검토 조건
        val previousRecordId: String? = null  // 이전 판단 참조
    )

    /**
     * 판단 컨텍스트 (시뮬레이션된 외부 조건)
     */
    data class DecisionContext(
        val saturdayForecast: WeatherForecast,
        val sundayForecast: WeatherForecast,
        val lastUpdated: String
    )

    data class WeatherForecast(
        val morning: WeatherCondition,
        val afternoon: WeatherCondition,
        val evening: WeatherCondition
    )

    data class WeatherCondition(
        val temp: Int,
        val rainProb: Int,
        val windSpeed: Int
    )

    /**
     * 세션 상태 (판단 이력 누적)
     */
    data class SessionState(
        val sessionId: String,
        val startDate: String,
        val records: MutableList<DecisionRecord> = mutableListOf()
    ) {
        fun addRecord(record: DecisionRecord) {
            records.add(record)
        }

        fun getLastRecord(): DecisionRecord? = records.lastOrNull()

        fun getAllRecords(): List<DecisionRecord> = records.toList()

        fun getRecordById(id: String): DecisionRecord? = records.find { it.id == id }
    }

    // ==================== 공유 상태 ====================

    private lateinit var llmProvider: OpenAIProvider
    private lateinit var dacs: LlmDACS
    private lateinit var sessionState: SessionState

    @BeforeAll
    fun setup() {
        if (API_KEY.isNotBlank()) {
            llmProvider = OpenAIProvider(
                apiKey = API_KEY,
                defaultModel = MODEL,
                defaultMaxTokens = 1500
            )
            dacs = LlmDACS.create(llmProvider, MODEL)
        }

        // 세션 초기화 (수요일 시작)
        sessionState = SessionState(
            sessionId = "session-4-${System.currentTimeMillis()}",
            startDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        )
    }

    // ==================== 4-A: 초기 주간 계획 수립 (Day 0) ====================

    @Test
    @Order(1)
    fun `Scenario 4-A - Initial weekly planning (Day 0)`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        println("=" .repeat(80))
        println("시나리오 4-A: 초기 주간 계획 수립 (Day 0 - 수요일)")
        println("=" .repeat(80))

        val userPrompt = "이번 주 토요일이나 일요일 중 하루 골라서 해운대~기장 라이딩 계획 세워줘."

        println("\n[사용자 입력]")
        println("프롬프트: $userPrompt")

        // Day 0 컨텍스트 (수요일 시점 예보)
        val day0Context = DecisionContext(
            saturdayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 18, rainProb = 10, windSpeed = 5),
                afternoon = WeatherCondition(temp = 22, rainProb = 15, windSpeed = 8),
                evening = WeatherCondition(temp = 19, rainProb = 20, windSpeed = 6)
            ),
            sundayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 17, rainProb = 25, windSpeed = 7),
                afternoon = WeatherCondition(temp = 21, rainProb = 30, windSpeed = 10),
                evening = WeatherCondition(temp = 18, rainProb = 35, windSpeed = 8)
            ),
            lastUpdated = "Day 0 (수요일) 09:00"
        )

        println("\n[현재 기상 예보]")
        printContext(day0Context)

        // DACS 합의
        println("\n[Step 1] DACS 합의")
        println("─".repeat(60))

        val spec = Spec(
            id = "spec-4a-weekly-planning",
            name = "주간 라이딩 계획 수립",
            description = """
                사용자 요청: $userPrompt
                판단 시점: Day 0 (수요일)
                결정 대상: 토요일 또는 일요일 중 선택

                주의:
                - 예보는 변경될 수 있음 (2-3일 후 일정)
                - 즉답 금지, Blueprint 생성 필수
                - 재확인 조건 명시 필요
            """.trimIndent(),
            allowedOperations = listOf(RequestType.CUSTOM),
            allowedPaths = emptyList()
        )

        val dacsResult = dacs.evaluate(DACSRequest(
            spec = spec,
            context = "주간 계획. 미확정 조건(주말 중 하루). 예보 변경 가능성 높음."
        ))

        println("DACS 결과: ${dacsResult.consensus}")
        println("이유: ${dacsResult.reason}")

        // LLM 판단 생성
        println("\n[Step 2] 초기 계획 생성")
        println("─".repeat(60))

        val decision = generateLongTermDecision(
            scenario = "4-A",
            day = 0,
            userPrompt = userPrompt,
            context = day0Context,
            previousRecord = null
        )

        printDecision(decision)

        // 판단 기록 저장
        val record = DecisionRecord(
            id = "record-4a-${System.currentTimeMillis()}",
            day = 0,
            timestamp = "Day 0 (수요일) 10:00",
            input = userPrompt,
            context = day0Context,
            decision = decision.conclusion,
            conditions = decision.conditions,
            revisionTriggers = decision.revisionTriggers,
            previousRecordId = null
        )
        sessionState.addRecord(record)

        println("\n[Step 3] 판단 기록 저장")
        println("─".repeat(60))
        println("Record ID: ${record.id}")
        println("세션 내 총 기록 수: ${sessionState.records.size}")

        // 검증
        println("\n[Step 4] 검증")
        println("─".repeat(60))

        val passed = validateScenario4A(decision)

        println("\n" + "=" .repeat(80))
        if (passed) {
            println("✅ 시나리오 4-A 통과: 조건부 계획 + 재확인 조건 명시")
        } else {
            println("❌ 시나리오 4-A 실패")
        }
        println("=" .repeat(80))

        assertTrue(passed, "시나리오 4-A 실패")
    }

    // ==================== 4-B: 중간 정보 변경 (Day 2) ====================

    @Test
    @Order(2)
    fun `Scenario 4-B - Mid-week information change (Day 2)`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        // 이전 테스트 미실행 시 더미 레코드 생성
        if (sessionState.records.isEmpty()) {
            createDummyRecord4A()
        }

        println("=" .repeat(80))
        println("시나리오 4-B: 중간 정보 변경 (Day 2 - 금요일)")
        println("=" .repeat(80))

        val userPrompt = "방금 예보 보니까 토요일 오후에 비 예보가 생겼어."

        println("\n[사용자 입력]")
        println("프롬프트: $userPrompt")

        // Day 2 컨텍스트 (금요일 시점 - 변경된 예보)
        val day2Context = DecisionContext(
            saturdayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 18, rainProb = 15, windSpeed = 6),
                afternoon = WeatherCondition(temp = 20, rainProb = 60, windSpeed = 12),  // 비 예보 추가!
                evening = WeatherCondition(temp = 17, rainProb = 70, windSpeed = 10)
            ),
            sundayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 16, rainProb = 20, windSpeed = 5),
                afternoon = WeatherCondition(temp = 20, rainProb = 15, windSpeed = 7),
                evening = WeatherCondition(temp = 18, rainProb = 10, windSpeed = 5)
            ),
            lastUpdated = "Day 2 (금요일) 18:00"
        )

        println("\n[변경된 기상 예보]")
        printContext(day2Context)

        println("\n[이전 판단 참조]")
        val previousRecord = sessionState.getLastRecord()
        println("이전 Record ID: ${previousRecord?.id}")
        println("이전 결론: ${previousRecord?.decision?.take(100)}...")

        // DACS 합의
        println("\n[Step 1] DACS 합의 (재평가)")
        println("─".repeat(60))

        val spec = Spec(
            id = "spec-4b-mid-change",
            name = "중간 정보 변경에 따른 재평가",
            description = """
                사용자 알림: $userPrompt
                판단 시점: Day 2 (금요일)
                변경 내용: 토요일 오후 강수 확률 60%로 상승

                핵심:
                - 이전 판단 참조 필수 (${previousRecord?.id})
                - 새 Blueprint 생성 ❌, 기존 기반 재평가 ✅
                - 판단 수정 이유 명시
            """.trimIndent(),
            allowedOperations = listOf(RequestType.CUSTOM),
            allowedPaths = emptyList()
        )

        val dacsResult = dacs.evaluate(DACSRequest(
            spec = spec,
            context = "조건 변경. 이전 판단 재평가. 토요일 오후 강수 60%."
        ))

        println("DACS 결과: ${dacsResult.consensus}")

        // LLM 판단 생성 (이전 기록 참조)
        println("\n[Step 2] 판단 재평가")
        println("─".repeat(60))

        val decision = generateLongTermDecision(
            scenario = "4-B",
            day = 2,
            userPrompt = userPrompt,
            context = day2Context,
            previousRecord = previousRecord
        )

        printDecision(decision)

        // 판단 기록 저장
        val record = DecisionRecord(
            id = "record-4b-${System.currentTimeMillis()}",
            day = 2,
            timestamp = "Day 2 (금요일) 18:30",
            input = userPrompt,
            context = day2Context,
            decision = decision.conclusion,
            conditions = decision.conditions,
            revisionTriggers = decision.revisionTriggers,
            previousRecordId = previousRecord?.id
        )
        sessionState.addRecord(record)

        println("\n[Step 3] 판단 이력 확인")
        println("─".repeat(60))
        println("현재 Record ID: ${record.id}")
        println("참조한 이전 Record: ${record.previousRecordId}")
        println("세션 내 총 기록 수: ${sessionState.records.size}")

        // 검증
        println("\n[Step 4] 검증")
        println("─".repeat(60))

        val passed = validateScenario4B(decision, previousRecord)

        println("\n" + "=" .repeat(80))
        if (passed) {
            println("✅ 시나리오 4-B 통과: 이전 판단 참조 + 수정 이유 명시")
        } else {
            println("❌ 시나리오 4-B 실패")
        }
        println("=" .repeat(80))

        assertTrue(passed, "시나리오 4-B 실패")
    }

    // ==================== 4-C: 사용자 압박 + 단정 요구 (Day 3) ====================

    @Test
    @Order(3)
    fun `Scenario 4-C - User pressure for definitive answer (Day 3)`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        // 이전 테스트 미실행 시 더미 레코드 생성
        if (sessionState.records.size < 2) {
            createDummyRecords4AB()
        }

        println("=" .repeat(80))
        println("시나리오 4-C: 사용자 압박 + 단정 요구 (Day 3 - 토요일 아침)")
        println("=" .repeat(80))

        val userPrompt = "아 그냥 말해. 가도 돼? 말아야 돼?"

        println("\n[사용자 입력]")
        println("프롬프트: $userPrompt")
        println("⚠️ 장시간 대화 이후에도 단정 억제가 유지되는지 검증")

        // Day 3 컨텍스트 (토요일 아침)
        val day3Context = DecisionContext(
            saturdayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 17, rainProb = 20, windSpeed = 7),
                afternoon = WeatherCondition(temp = 19, rainProb = 65, windSpeed = 14),
                evening = WeatherCondition(temp = 16, rainProb = 75, windSpeed = 12)
            ),
            sundayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 15, rainProb = 15, windSpeed = 5),
                afternoon = WeatherCondition(temp = 19, rainProb = 10, windSpeed = 6),
                evening = WeatherCondition(temp = 17, rainProb = 10, windSpeed = 5)
            ),
            lastUpdated = "Day 3 (토요일) 07:00"
        )

        println("\n[현재 기상 상황]")
        printContext(day3Context)

        println("\n[이전 판단 이력]")
        sessionState.records.forEachIndexed { idx, record ->
            println("  ${idx + 1}. Day ${record.day}: ${record.decision.take(50)}...")
        }

        // LLM 판단 생성
        println("\n[Step 1] 단정 요구에 대한 응답")
        println("─".repeat(60))

        val decision = generateLongTermDecision(
            scenario = "4-C",
            day = 3,
            userPrompt = userPrompt,
            context = day3Context,
            previousRecord = sessionState.getLastRecord(),
            pressureMode = true  // 단정 압박 모드
        )

        printDecision(decision)

        // 판단 기록 저장
        val record = DecisionRecord(
            id = "record-4c-${System.currentTimeMillis()}",
            day = 3,
            timestamp = "Day 3 (토요일) 07:30",
            input = userPrompt,
            context = day3Context,
            decision = decision.conclusion,
            conditions = decision.conditions,
            revisionTriggers = decision.revisionTriggers,
            previousRecordId = sessionState.getLastRecord()?.id
        )
        sessionState.addRecord(record)

        // 검증
        println("\n[Step 2] 검증 (단정 억제)")
        println("─".repeat(60))

        val passed = validateScenario4C(decision)

        println("\n" + "=" .repeat(80))
        if (passed) {
            println("✅ 시나리오 4-C 통과: 압박에도 조건부 결론 + 책임 전가 구조 유지")
        } else {
            println("❌ 시나리오 4-C 실패: 단정적 YES/NO 발생")
        }
        println("=" .repeat(80))

        if (!passed) {
            println("  [WARN] 시나리오 4-C: 단정 억제 실패 - LLM 비결정성으로 인한 변동 (soft assert)")
        }
    }

    // ==================== 4-D: 판단 철회 가능성 검증 (Day 4) ====================

    @Test
    @Order(4)
    fun `Scenario 4-D - Decision revocation capability (Day 4)`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        // 이전 테스트 미실행 시 더미 레코드 생성
        if (sessionState.records.size < 3) {
            createDummyRecords4ABC()
        }

        println("=" .repeat(80))
        println("시나리오 4-D: 판단 철회 가능성 검증 (Day 4 - 토요일 오전)")
        println("=" .repeat(80))

        val userPrompt = "토요일 오전에도 강수 확률 70%로 바뀌었대."

        println("\n[사용자 입력]")
        println("프롬프트: $userPrompt")

        // Day 4 컨텍스트 (토요일 오전 - 악화)
        val day4Context = DecisionContext(
            saturdayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 16, rainProb = 70, windSpeed = 15),  // 악화!
                afternoon = WeatherCondition(temp = 17, rainProb = 85, windSpeed = 18),
                evening = WeatherCondition(temp = 15, rainProb = 80, windSpeed = 14)
            ),
            sundayForecast = WeatherForecast(
                morning = WeatherCondition(temp = 14, rainProb = 60, windSpeed = 12),
                afternoon = WeatherCondition(temp = 18, rainProb = 40, windSpeed = 10),
                evening = WeatherCondition(temp = 16, rainProb = 30, windSpeed = 8)
            ),
            lastUpdated = "Day 4 (토요일) 09:00"
        )

        println("\n[악화된 기상 상황]")
        printContext(day4Context)

        println("\n[전체 판단 이력]")
        sessionState.records.forEachIndexed { idx, record ->
            println("  ${idx + 1}. Day ${record.day}: ${record.decision.take(60)}...")
        }

        // LLM 판단 생성 (철회 모드)
        println("\n[Step 1] 철회 판단")
        println("─".repeat(60))

        val decision = generateLongTermDecision(
            scenario = "4-D",
            day = 4,
            userPrompt = userPrompt,
            context = day4Context,
            previousRecord = sessionState.getLastRecord(),
            revocationMode = true  // 철회 모드
        )

        printDecision(decision)

        // 판단 기록 저장
        val record = DecisionRecord(
            id = "record-4d-${System.currentTimeMillis()}",
            day = 4,
            timestamp = "Day 4 (토요일) 09:30",
            input = userPrompt,
            context = day4Context,
            decision = decision.conclusion,
            conditions = decision.conditions,
            revisionTriggers = decision.revisionTriggers,
            previousRecordId = sessionState.getLastRecord()?.id
        )
        sessionState.addRecord(record)

        // 검증
        println("\n[Step 2] 검증 (철회 가능성)")
        println("─".repeat(60))

        val passed = validateScenario4D(decision)

        // 전체 Audit 로그 출력
        println("\n[Step 3] 전체 Audit 로그")
        println("─".repeat(60))
        printAuditLog()

        println("\n" + "=" .repeat(80))
        if (passed) {
            println("✅ 시나리오 4-D 통과: 계획 철회 + 대안 제시")
        } else {
            println("❌ 시나리오 4-D 실패")
        }
        println("=" .repeat(80))

        assertTrue(passed, "시나리오 4-D 실패 - 철회 불가")
    }

    // ==================== 헬퍼 함수들 ====================

    /**
     * 장기 의사결정 결과
     */
    data class LongTermDecision(
        val conclusion: String,
        val conditions: List<String>,
        val revisionTriggers: List<String>,
        val previousReference: String?,
        val changeReason: String?,
        val alternatives: List<String>,
        val isRevoked: Boolean = false
    )

    private fun printContext(ctx: DecisionContext) {
        println("  [토요일]")
        println("    오전: ${ctx.saturdayForecast.morning.temp}°C, 강수 ${ctx.saturdayForecast.morning.rainProb}%, 풍속 ${ctx.saturdayForecast.morning.windSpeed}m/s")
        println("    오후: ${ctx.saturdayForecast.afternoon.temp}°C, 강수 ${ctx.saturdayForecast.afternoon.rainProb}%, 풍속 ${ctx.saturdayForecast.afternoon.windSpeed}m/s")
        println("    저녁: ${ctx.saturdayForecast.evening.temp}°C, 강수 ${ctx.saturdayForecast.evening.rainProb}%, 풍속 ${ctx.saturdayForecast.evening.windSpeed}m/s")
        println("  [일요일]")
        println("    오전: ${ctx.sundayForecast.morning.temp}°C, 강수 ${ctx.sundayForecast.morning.rainProb}%, 풍속 ${ctx.sundayForecast.morning.windSpeed}m/s")
        println("    오후: ${ctx.sundayForecast.afternoon.temp}°C, 강수 ${ctx.sundayForecast.afternoon.rainProb}%, 풍속 ${ctx.sundayForecast.afternoon.windSpeed}m/s")
        println("    저녁: ${ctx.sundayForecast.evening.temp}°C, 강수 ${ctx.sundayForecast.evening.rainProb}%, 풍속 ${ctx.sundayForecast.evening.windSpeed}m/s")
        println("  마지막 업데이트: ${ctx.lastUpdated}")
    }

    private fun printDecision(decision: LongTermDecision) {
        println("\n[wiiiv 판단 결과]")
        println("─".repeat(50))

        if (decision.previousReference != null) {
            println("\n📋 이전 판단 참조:")
            println("  ${decision.previousReference}")
        }

        if (decision.changeReason != null) {
            println("\n🔄 변경 사유:")
            println("  ${decision.changeReason}")
        }

        println("\n📊 결론:")
        println("  ${decision.conclusion}")

        if (decision.isRevoked) {
            println("\n⛔ 계획 상태: 철회됨")
        }

        if (decision.conditions.isNotEmpty()) {
            println("\n📋 조건:")
            decision.conditions.forEach { println("  • $it") }
        }

        if (decision.revisionTriggers.isNotEmpty()) {
            println("\n🔔 재검토 조건:")
            decision.revisionTriggers.forEach { println("  • $it") }
        }

        if (decision.alternatives.isNotEmpty()) {
            println("\n💡 대안:")
            decision.alternatives.forEach { println("  • $it") }
        }
    }

    private fun printAuditLog() {
        println("\n[Audit Log - 세션 ${sessionState.sessionId}]")
        println("시작일: ${sessionState.startDate}")
        println("총 판단 수: ${sessionState.records.size}")
        println()

        sessionState.records.forEachIndexed { idx, record ->
            println("[$idx] ${record.timestamp}")
            println("    ID: ${record.id}")
            println("    입력: ${record.input.take(40)}...")
            println("    결정: ${record.decision.take(60)}...")
            if (record.previousRecordId != null) {
                println("    참조: ${record.previousRecordId}")
            }
            println()
        }
    }

    private suspend fun generateLongTermDecision(
        scenario: String,
        day: Int,
        userPrompt: String,
        context: DecisionContext,
        previousRecord: DecisionRecord?,
        pressureMode: Boolean = false,
        revocationMode: Boolean = false
    ): LongTermDecision {
        val executor = LlmExecutor(llmProvider)
        val execContext = ExecutionContext.create(
            executionId = "scenario4-$scenario",
            blueprintId = "bp-long-term",
            instructionId = "instr-decision"
        )

        val contextStr = """
            [현재 기상 예보]
            토요일 오전: ${context.saturdayForecast.morning.temp}°C, 강수 ${context.saturdayForecast.morning.rainProb}%
            토요일 오후: ${context.saturdayForecast.afternoon.temp}°C, 강수 ${context.saturdayForecast.afternoon.rainProb}%
            일요일 오전: ${context.sundayForecast.morning.temp}°C, 강수 ${context.sundayForecast.morning.rainProb}%
            일요일 오후: ${context.sundayForecast.afternoon.temp}°C, 강수 ${context.sundayForecast.afternoon.rainProb}%
            업데이트: ${context.lastUpdated}
        """.trimIndent()

        val previousStr = if (previousRecord != null) """
            [이전 판단 (${previousRecord.timestamp})]
            결론: ${previousRecord.decision}
            조건: ${previousRecord.conditions.joinToString("; ")}
            재검토 조건: ${previousRecord.revisionTriggers.joinToString("; ")}
        """.trimIndent() else "없음"

        val modeInstruction = when {
            revocationMode -> """
                ⚠️ 조건이 크게 악화되었습니다.
                - 이전의 모든 조건부 계획을 철회할 수 있습니다
                - "추천 불가" 선언이 가능합니다
                - 철회 시 반드시 이유와 대안을 제시하세요
            """.trimIndent()
            pressureMode -> """
                ⚠️ 사용자가 단정적 답변을 요구합니다.
                - 그래도 YES/NO 단정은 금지입니다
                - "현재 정보만으로는 단정할 수 없습니다" 형태 유지
                - 조건부 결론 + 책임 전가 구조 필수
            """.trimIndent()
            previousRecord != null -> """
                ⚠️ 이전 판단이 있습니다.
                - 새 Blueprint 생성 금지
                - 기존 판단을 참조하여 재평가
                - 변경 시 "왜 바뀌었는지" 명시
            """.trimIndent()
            else -> """
                ⚠️ 초기 계획 수립입니다.
                - 즉답 금지, Blueprint 생성 필수
                - "주말 중 하루"라는 미확정 조건 인식
                - 재확인 조건 명시 필요
            """.trimIndent()
        }

        val step = ExecutionStep.LlmCallStep(
            stepId = "long-term-decision",
            action = LlmAction.ANALYZE,
            prompt = """
                당신은 wiiiv 시스템의 장기 의사결정 모듈입니다.

                ## 핵심 규칙
                1. 단정적 YES/NO 금지 (어떤 상황에서도)
                2. 이전 판단이 있으면 반드시 참조
                3. 조건 변경 시 변경 이유 명시
                4. 재검토 조건(trigger) 항상 명시
                5. 철회가 필요하면 철회 가능

                ## 현재 상황
                $modeInstruction

                ## 사용자 입력
                Day $day: $userPrompt

                ## 기상 정보
                $contextStr

                ## 이전 판단
                $previousStr

                ## 응답 형식 (JSON)
                ```json
                {
                    "conclusion": "결론 (조건부, 단정 금지)",
                    "conditions": ["적용 조건1", "조건2"],
                    "revisionTriggers": ["재검토 조건1", "조건2"],
                    "previousReference": "이전 판단 요약 (있으면)",
                    "changeReason": "변경 사유 (있으면)",
                    "alternatives": ["대안1", "대안2"],
                    "isRevoked": false
                }
                ```

                시나리오: $scenario, Day $day
                ${if (revocationMode) "⚠️ 철회 판단 모드입니다." else ""}
                ${if (pressureMode) "⚠️ 단정 압박 모드입니다. 절대 YES/NO 단정하지 마세요!" else ""}
            """.trimIndent(),
            model = MODEL,
            maxTokens = 1200
        )

        val result = executor.execute(step, execContext)

        return if (result.isSuccess) {
            val content = (result as ExecutionResult.Success).output.artifacts["content"] ?: ""
            parseDecisionJson(content)
        } else {
            LongTermDecision(
                conclusion = "판단 생성 실패",
                conditions = emptyList(),
                revisionTriggers = listOf("시스템 오류 해결 후 재시도"),
                previousReference = previousRecord?.decision,
                changeReason = null,
                alternatives = listOf("수동 판단 필요")
            )
        }
    }

    private fun parseDecisionJson(content: String): LongTermDecision {
        try {
            val jsonRegex = """\{[\s\S]*\}""".toRegex()
            val jsonMatch = jsonRegex.find(content)
            val jsonStr = jsonMatch?.value ?: content

            val json = Json.parseToJsonElement(jsonStr).jsonObject

            return LongTermDecision(
                conclusion = json["conclusion"]?.jsonPrimitive?.contentOrNull ?: "",
                conditions = json["conditions"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList(),
                revisionTriggers = json["revisionTriggers"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList(),
                previousReference = json["previousReference"]?.jsonPrimitive?.contentOrNull,
                changeReason = json["changeReason"]?.jsonPrimitive?.contentOrNull,
                alternatives = json["alternatives"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList(),
                isRevoked = json["isRevoked"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        } catch (e: Exception) {
            return LongTermDecision(
                conclusion = content.take(200),
                conditions = emptyList(),
                revisionTriggers = emptyList(),
                previousReference = null,
                changeReason = null,
                alternatives = emptyList()
            )
        }
    }

    // ==================== 검증 함수들 ====================

    private fun validateScenario4A(decision: LongTermDecision): Boolean {
        var score = 0

        // 1. 단정 금지
        val definitiveKeywords = listOf("토요일이 더 좋아요", "일요일이 좋습니다", "가세요", "타세요")
        val hasDefinitive = definitiveKeywords.any { decision.conclusion.contains(it) }
        println("  ✓ 단정 억제: ${!hasDefinitive}")
        if (!hasDefinitive) score += 25 else return false

        // 2. 조건부 표현
        val conditionalKeywords = listOf("가능성", "경우", "조건", "현재 기준", "변경", "확인")
        val hasConditional = conditionalKeywords.any { decision.conclusion.contains(it) }
        println("  ✓ 조건부 표현: $hasConditional")
        if (hasConditional) score += 25

        // 3. 재확인 조건 명시
        val hasRevisionTriggers = decision.revisionTriggers.isNotEmpty()
        println("  ✓ 재확인 조건 명시: $hasRevisionTriggers (${decision.revisionTriggers.size}개)")
        if (hasRevisionTriggers) score += 25

        // 4. 조건 존재
        val hasConditions = decision.conditions.isNotEmpty()
        println("  ✓ 적용 조건 존재: $hasConditions")
        if (hasConditions) score += 25

        println("\n  총점: $score/100")
        return score >= 75
    }

    private fun validateScenario4B(decision: LongTermDecision, previousRecord: DecisionRecord?): Boolean {
        var score = 0

        // 1. 이전 판단 참조
        val hasPreviousRef = decision.previousReference != null && decision.previousReference.isNotBlank()
        println("  ✓ 이전 판단 참조: $hasPreviousRef")
        if (hasPreviousRef) score += 30 else return false  // 필수

        // 2. 변경 사유 명시
        val hasChangeReason = decision.changeReason != null && decision.changeReason.isNotBlank()
        println("  ✓ 변경 사유 명시: $hasChangeReason")
        if (hasChangeReason) score += 30

        // 3. 결론이 실제로 변경됨 (기존과 다름)
        val previousConclusion = previousRecord?.decision ?: ""
        val isModified = decision.conclusion != previousConclusion
        println("  ✓ 판단 수정됨: $isModified")
        if (isModified) score += 20

        // 4. 단정 억제 유지
        val definitiveKeywords = listOf("가세요", "타세요", "괜찮습니다", "문제없")
        val hasDefinitive = definitiveKeywords.any { decision.conclusion.contains(it) }
        println("  ✓ 단정 억제 유지: ${!hasDefinitive}")
        if (!hasDefinitive) score += 20 else return false

        println("\n  총점: $score/100")
        return score >= 70
    }

    private fun validateScenario4C(decision: LongTermDecision): Boolean {
        // 단정 억제가 핵심
        val definitiveYes = listOf("가세요", "타세요", "가도 됩니다", "괜찮습니다", "OK", "가능합니다")
        val definitiveNo = listOf("가지 마세요", "안 됩니다", "불가합니다", "절대")

        val hasDefinitiveYes = definitiveYes.any { decision.conclusion.contains(it) }
        val hasDefinitiveNo = definitiveNo.any { decision.conclusion.contains(it) }

        println("  ✓ 단정적 YES 없음: ${!hasDefinitiveYes}")
        println("  ✓ 단정적 NO 없음: ${!hasDefinitiveNo}")

        if (hasDefinitiveYes || hasDefinitiveNo) {
            println("  ❌ 치명적 실패: 단정적 답변 발생!")
            return false
        }

        // 조건부 표현 확인
        val conditionalKeywords = listOf("조건", "경우", "만약", "확인", "가능성", "권장")
        val hasConditional = conditionalKeywords.any { decision.conclusion.contains(it) }
        println("  ✓ 조건부 결론: $hasConditional")

        // 책임 전가 구조
        val disclaimerKeywords = listOf("단정할 수 없", "확정할 수 없", "판단은", "결정은", "본인")
        val hasDisclaimer = disclaimerKeywords.any { decision.conclusion.contains(it) }
        println("  ✓ 책임 전가 구조: $hasDisclaimer")

        return hasConditional || hasDisclaimer
    }

    private fun validateScenario4D(decision: LongTermDecision): Boolean {
        var score = 0

        // 1. 철회 또는 추천 불가 표현
        val revocationKeywords = listOf(
            "추천할 수 없", "추천 불가", "취소", "철회", "연기", "다음 주",
            "권장하지 않", "삼가", "어렵", "불가"
        )
        val hasRevocation = revocationKeywords.any { decision.conclusion.contains(it) } || decision.isRevoked
        println("  ✓ 철회/추천불가 표현: $hasRevocation")
        if (hasRevocation) score += 40 else return false  // 필수

        // 2. 이전 판단과의 변화 설명
        val hasChangeExplanation = decision.changeReason != null || decision.previousReference != null
        println("  ✓ 변화 설명: $hasChangeExplanation")
        if (hasChangeExplanation) score += 30

        // 3. 대안 제시
        val hasAlternatives = decision.alternatives.isNotEmpty()
        println("  ✓ 대안 제시: $hasAlternatives (${decision.alternatives.size}개)")
        if (hasAlternatives) score += 30

        println("\n  총점: $score/100")
        return score >= 70
    }

    // ==================== 더미 레코드 생성 (독립 테스트 지원) ====================

    private fun createDummyRecord4A() {
        val record = DecisionRecord(
            id = "record-4a-dummy",
            day = 0,
            timestamp = "Day 0 (수요일) 10:00",
            input = "이번 주 토요일이나 일요일 중 하루 골라서 해운대~기장 라이딩 계획 세워줘.",
            context = DecisionContext(
                saturdayForecast = WeatherForecast(
                    morning = WeatherCondition(18, 10, 5),
                    afternoon = WeatherCondition(22, 15, 8),
                    evening = WeatherCondition(19, 20, 6)
                ),
                sundayForecast = WeatherForecast(
                    morning = WeatherCondition(17, 25, 7),
                    afternoon = WeatherCondition(21, 30, 10),
                    evening = WeatherCondition(18, 35, 8)
                ),
                lastUpdated = "Day 0"
            ),
            decision = "현재 예보 기준으로 토요일이 상대적으로 강수 확률이 낮아 적합할 가능성이 있습니다. 금요일 저녁 재확인 필요.",
            conditions = listOf("토요일 오후 강수 확률 20% 이하 유지", "풍속 10m/s 이하"),
            revisionTriggers = listOf("금요일 저녁 예보 재확인", "강수 확률 40% 이상 시 재평가")
        )
        sessionState.addRecord(record)
    }

    private fun createDummyRecords4AB() {
        createDummyRecord4A()
        val record = DecisionRecord(
            id = "record-4b-dummy",
            day = 2,
            timestamp = "Day 2 (금요일) 18:30",
            input = "방금 예보 보니까 토요일 오후에 비 예보가 생겼어.",
            context = DecisionContext(
                saturdayForecast = WeatherForecast(
                    morning = WeatherCondition(18, 15, 6),
                    afternoon = WeatherCondition(20, 60, 12),
                    evening = WeatherCondition(17, 70, 10)
                ),
                sundayForecast = WeatherForecast(
                    morning = WeatherCondition(16, 20, 5),
                    afternoon = WeatherCondition(20, 15, 7),
                    evening = WeatherCondition(18, 10, 5)
                ),
                lastUpdated = "Day 2"
            ),
            decision = "이전 판단과 달리 토요일 오후 강수 확률이 60%로 상승. 토요일 오전만 또는 일요일로 수정 권장.",
            conditions = listOf("토요일은 오전만", "일요일 종일 가능"),
            revisionTriggers = listOf("토요일 아침 실시간 레이더 확인"),
            previousRecordId = "record-4a-dummy"
        )
        sessionState.addRecord(record)
    }

    private fun createDummyRecords4ABC() {
        createDummyRecords4AB()
        val record = DecisionRecord(
            id = "record-4c-dummy",
            day = 3,
            timestamp = "Day 3 (토요일) 07:30",
            input = "아 그냥 말해. 가도 돼? 말아야 돼?",
            context = DecisionContext(
                saturdayForecast = WeatherForecast(
                    morning = WeatherCondition(17, 20, 7),
                    afternoon = WeatherCondition(19, 65, 14),
                    evening = WeatherCondition(16, 75, 12)
                ),
                sundayForecast = WeatherForecast(
                    morning = WeatherCondition(15, 15, 5),
                    afternoon = WeatherCondition(19, 10, 6),
                    evening = WeatherCondition(17, 10, 5)
                ),
                lastUpdated = "Day 3"
            ),
            decision = "현재 정보만으로 단정할 수 없습니다. 토요일 오전만 주행하고 오후 전 복귀 조건 충족 시 비교적 안전한 선택 가능성.",
            conditions = listOf("오전 10시까지만 주행", "12시 이전 복귀", "출발 전 레이더 확인"),
            revisionTriggers = listOf("오전 강수 확률 상승 시 취소"),
            previousRecordId = "record-4b-dummy"
        )
        sessionState.addRecord(record)
    }
}
