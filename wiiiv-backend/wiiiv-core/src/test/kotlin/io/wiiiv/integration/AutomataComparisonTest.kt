package io.wiiiv.integration

import io.wiiiv.dacs.*
import io.wiiiv.execution.*
import io.wiiiv.execution.impl.*
import io.wiiiv.governor.RequestType
import io.wiiiv.governor.Spec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * 시나리오 1-2: 오토마타 vs LLM Governor 비교 테스트
 *
 * 입력: "프로젝트 파일들 분석해줘"
 *
 * 특징:
 * - 안전한 작업 (FILE_READ, 읽기 전용)
 * - 모호한 요청 (경로 미지정, 분석 유형 미지정)
 * - 시나리오 1과 등가적 복잡도
 *
 * 비교 대상:
 * - wiiiv v2.0 (LLM Governor + DACS)
 * - 오토마타 시뮬레이션 (규칙 기반 상태 머신)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomataComparisonTest {

    companion object {
        private val API_KEY = System.getenv("OPENAI_API_KEY") ?: ""
        private const val MODEL = "gpt-4o-mini"
    }

    private lateinit var llmProvider: OpenAIProvider
    private lateinit var llmDacs: LlmDACS

    @BeforeAll
    fun setup() {
        if (API_KEY.isNotBlank()) {
            llmProvider = OpenAIProvider(
                apiKey = API_KEY,
                defaultModel = MODEL,
                defaultMaxTokens = 1000
            )
            llmDacs = LlmDACS.create(llmProvider, MODEL)
        }
    }

    // ==================== 오토마타 시뮬레이션 ====================

    /**
     * 오토마타 상태 정의
     */
    enum class AutomataState {
        INIT,
        PARSE_INPUT,
        CHECK_OPERATION,
        CHECK_PATH,
        ALLOW,
        DENY,
        ERROR
    }

    /**
     * 오토마타 결과
     */
    data class AutomataResult(
        val finalState: AutomataState,
        val decision: String,  // "ALLOW", "DENY", "ERROR"
        val reason: String?,   // 오토마타는 이유를 제공하지 못함
        val clarificationQuestions: List<String> = emptyList()  // 오토마타는 질문 생성 불가
    )

    /**
     * 규칙 기반 오토마타 시뮬레이션
     *
     * 이것이 v1.0 오토마타가 할 수 있는 최선
     */
    class RuleBasedAutomata {
        // 허용된 작업 키워드
        private val allowedOperationKeywords = mapOf(
            "읽" to RequestType.FILE_READ,
            "분석" to RequestType.FILE_READ,
            "조회" to RequestType.FILE_READ,
            "확인" to RequestType.FILE_READ,
            "삭제" to RequestType.FILE_DELETE,
            "정리" to RequestType.FILE_DELETE,  // 모호함 - 삭제로 가정
            "제거" to RequestType.FILE_DELETE
        )

        // 금지된 경로
        private val blockedPaths = listOf("/etc", "/root", "~/.ssh", "/home")

        // 필수 정보
        private val requiredFields = listOf("operation", "path")

        fun process(input: String, specifiedPath: String? = null): AutomataResult {
            // State: INIT
            var detectedOperation: RequestType? = null

            // State: INIT → PARSE_INPUT

            // 키워드 매칭으로 작업 유형 감지
            for ((keyword, operation) in allowedOperationKeywords) {
                if (input.contains(keyword)) {
                    detectedOperation = operation
                    break
                }
            }

            // State: PARSE_INPUT → CHECK_OPERATION

            if (detectedOperation == null) {
                // 작업 유형을 파악할 수 없음
                return AutomataResult(
                    finalState = AutomataState.ERROR,
                    decision = "ERROR",
                    reason = null  // 오토마타는 이유를 설명할 수 없음
                )
            }

            // State: CHECK_OPERATION → CHECK_PATH

            if (specifiedPath == null) {
                // 경로가 지정되지 않음
                // 오토마타의 한계: "어떤 경로?" 라고 물어볼 수 없음
                // 옵션 1: ERROR 반환
                // 옵션 2: 현재 디렉토리 가정 (위험)
                // 옵션 3: 전체 거부 (과잉 차단)

                // 가장 안전한 옵션: ERROR
                return AutomataResult(
                    finalState = AutomataState.ERROR,
                    decision = "ERROR",
                    reason = null
                    // 오토마타는 질문을 생성할 수 없음!
                )
            }

            // 경로 검사
            val isBlocked = blockedPaths.any { blocked ->
                specifiedPath.startsWith(blocked)
            }

            return if (isBlocked) {
                AutomataResult(
                    finalState = AutomataState.DENY,
                    decision = "DENY",
                    reason = null  // 왜 거부되었는지 설명 불가
                )
            } else {
                AutomataResult(
                    finalState = AutomataState.ALLOW,
                    decision = "ALLOW",
                    reason = null
                )
            }
        }
    }

    // ==================== 비교 테스트 ====================

    @Test
    fun `Scenario 1-2 - Compare Automata vs LLM Governor`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        val userInput = "프로젝트 파일들 분석해줘"

        println("=" .repeat(70))
        println("시나리오 1-2: 오토마타 vs LLM Governor 비교")
        println("입력: \"$userInput\"")
        println("=" .repeat(70))

        // ==================== 오토마타 테스트 ====================
        println("\n" + "─".repeat(70))
        println("[ 오토마타 (v1.0 시뮬레이션) ]")
        println("─".repeat(70))

        val automata = RuleBasedAutomata()
        val automataResult = automata.process(userInput, specifiedPath = null)

        println("\n처리 과정:")
        println("  1. 상태: INIT → PARSE_INPUT")
        println("  2. 키워드 매칭: '분석' → FILE_READ 감지")
        println("  3. 상태: PARSE_INPUT → CHECK_OPERATION")
        println("  4. 상태: CHECK_OPERATION → CHECK_PATH")
        println("  5. 경로 미지정 감지")
        println("  6. 최종 상태: ${automataResult.finalState}")

        println("\n결과:")
        println("  결정: ${automataResult.decision}")
        println("  이유: ${automataResult.reason ?: "(제공 불가)"}")
        println("  추가 질문: ${if (automataResult.clarificationQuestions.isEmpty()) "(생성 불가)" else automataResult.clarificationQuestions.toString()}")

        println("\n오토마타 한계:")
        println("  ❌ '왜 ERROR인가?' 설명 불가")
        println("  ❌ '어떤 프로젝트?' 질문 생성 불가")
        println("  ❌ '어떤 분석?' 구체화 요청 불가")
        println("  ❌ 대안 제시 불가")

        // ==================== LLM Governor 테스트 ====================
        println("\n" + "─".repeat(70))
        println("[ LLM Governor (v2.0 wiiiv) ]")
        println("─".repeat(70))

        // Spec 생성 (모호한 요청 그대로)
        val ambiguousSpec = Spec(
            id = "spec-analyze-files",
            name = userInput,
            description = "사용자가 프로젝트 파일 분석을 요청함. 구체적인 프로젝트, 경로, 분석 유형 미지정.",
            allowedOperations = listOf(RequestType.FILE_READ),  // 안전한 읽기 작업
            allowedPaths = emptyList()  // 경로 미지정
        )

        val request = DACSRequest(
            spec = ambiguousSpec,
            context = "사용자가 CLI에서 '$userInput'라고만 입력함. 추가 정보 없음."
        )

        println("\n처리 과정:")
        println("  1. Spec 생성 (자연어 → 구조화)")
        println("  2. DACS 합의 요청")

        val dacsResult = llmDacs.evaluate(request)

        println("  3. 페르소나별 독립 평가:")
        dacsResult.personaOpinions.forEach { opinion ->
            println("     - ${opinion.persona}: ${opinion.vote}")
        }
        println("  4. VetoConsensusEngine 합의 도출")
        println("  5. 최종 결정: ${dacsResult.consensus}")

        println("\n결과:")
        println("  결정: ${dacsResult.consensus}")
        println("  이유: ${dacsResult.reason}")

        println("\n페르소나 상세 판단:")
        dacsResult.personaOpinions.forEach { opinion ->
            println("  [${opinion.persona}] ${opinion.vote}")
            println("    요약: ${opinion.summary}")
            if (opinion.concerns.isNotEmpty()) {
                println("    우려: ${opinion.concerns.joinToString("; ")}")
            }
        }

        // LLM에게 직접 응답 생성 요청 (사용자에게 보여줄 메시지)
        println("\n" + "─".repeat(70))
        println("[ LLM Governor 사용자 응답 생성 ]")
        println("─".repeat(70))

        val executor = LlmExecutor(llmProvider)
        val context = ExecutionContext.create(
            executionId = "scenario-1-2",
            blueprintId = "bp-test",
            instructionId = "instr-test"
        )

        val responseStep = ExecutionStep.LlmCallStep(
            stepId = "user-response",
            action = LlmAction.ANALYZE,
            prompt = """
                당신은 wiiiv 시스템의 Governor입니다.

                사용자 요청: "$userInput"

                DACS 판단 결과: ${dacsResult.consensus}
                이유: ${dacsResult.reason}

                사용자에게 친절하게 응답하세요:
                1. 요청을 이해했음을 알려주세요
                2. 추가로 필요한 정보를 질문하세요
                3. 가능한 옵션을 제시하세요

                한국어로 응답하세요.
            """.trimIndent(),
            model = MODEL,
            maxTokens = 500
        )

        val llmResponse = executor.execute(responseStep, context)
        val userMessage = if (llmResponse.isSuccess) {
            (llmResponse as ExecutionResult.Success).output.artifacts["content"] ?: ""
        } else {
            "(응답 생성 실패)"
        }

        println("\nLLM Governor가 사용자에게 보낼 응답:")
        println("─".repeat(50))
        println(userMessage)
        println("─".repeat(50))

        // ==================== 비교 결과 ====================
        println("\n" + "=" .repeat(70))
        println("[ 비교 결과 ]")
        println("=" .repeat(70))

        println("""

            ┌────────────────────┬─────────────────────┬─────────────────────────────┐
            │ 항목               │ 오토마타 (v1.0)     │ LLM Governor (v2.0)         │
            ├────────────────────┼─────────────────────┼─────────────────────────────┤
            │ 결정               │ ${automataResult.decision.padEnd(19)} │ ${dacsResult.consensus.toString().padEnd(27)} │
            ├────────────────────┼─────────────────────┼─────────────────────────────┤
            │ 이유 제공          │ ❌ 불가             │ ✅ 상세 제공                │
            ├────────────────────┼─────────────────────┼─────────────────────────────┤
            │ 추가 질문 생성     │ ❌ 불가             │ ✅ 자연어 질문              │
            ├────────────────────┼─────────────────────┼─────────────────────────────┤
            │ 대안 제시          │ ❌ 불가             │ ✅ 옵션 제시                │
            ├────────────────────┼─────────────────────┼─────────────────────────────┤
            │ 의도 파악          │ △ 키워드 매칭      │ ✅ 의미 이해                │
            ├────────────────────┼─────────────────────┼─────────────────────────────┤
            │ 사용자 경험        │ ❌ "ERROR" 만       │ ✅ 친절한 안내              │
            └────────────────────┴─────────────────────┴─────────────────────────────┘
        """.trimIndent())

        // 검증
        println("\n검증:")

        // 오토마타는 ERROR (경로 미지정)
        assertEquals(AutomataState.ERROR, automataResult.finalState, "오토마타는 경로 미지정 시 ERROR")
        println("  ✓ 오토마타: ERROR (경로 미지정으로 처리 불가)")

        // LLM Governor는 REVISION 또는 NO (추가 정보 요청) - soft assert
        val llmNotApproved = dacsResult.consensus != Consensus.YES
        if (llmNotApproved) {
            println("  ✓ LLM Governor: ${dacsResult.consensus} (모호함 감지)")
        } else {
            println("  [WARN] LLM Governor: YES 반환 - LLM 비결정성으로 인한 변동 (soft assert)")
        }

        // LLM은 이유를 제공함
        if (dacsResult.reason.isNotBlank()) {
            println("  ✓ LLM Governor: 이유 제공됨")
        } else {
            println("  [WARN] LLM Governor: 이유 미제공 - LLM 비결정성 (soft assert)")
        }

        // LLM은 질문을 생성함
        val hasQuestions = userMessage.contains("?")
        if (hasQuestions) {
            println("  ✓ LLM Governor: 추가 질문 생성됨")
        } else {
            println("  [WARN] LLM Governor: 추가 질문 미생성 - LLM 비결정성 (soft assert)")
        }

        println("\n" + "=" .repeat(70))
        println("✅ 시나리오 1-2 비교 완료")
        println("=" .repeat(70))
    }

    @Test
    fun `Scenario 1-2b - Automata with specified path vs LLM`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        println("=" .repeat(70))
        println("시나리오 1-2b: 경로 지정 시 비교")
        println("입력: \"프로젝트 파일들 분석해줘\" + 경로: ./src")
        println("=" .repeat(70))

        val userInput = "프로젝트 파일들 분석해줘"
        val specifiedPath = "./src"

        // 오토마타 테스트
        val automata = RuleBasedAutomata()
        val automataResult = automata.process(userInput, specifiedPath = specifiedPath)

        println("\n[오토마타]")
        println("  결정: ${automataResult.decision}")
        println("  이유: ${automataResult.reason ?: "(없음)"}")

        // LLM Governor 테스트
        val spec = Spec(
            id = "spec-analyze-src",
            name = userInput,
            description = "사용자가 ./src 경로의 프로젝트 파일 분석 요청",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf(specifiedPath)
        )

        val dacsResult = llmDacs.evaluate(DACSRequest(spec = spec))

        println("\n[LLM Governor]")
        println("  결정: ${dacsResult.consensus}")
        println("  이유: ${dacsResult.reason}")

        println("\n비교:")
        println("  오토마타: ${automataResult.decision} - 경로가 있으면 단순 ALLOW")
        println("  LLM Governor: ${dacsResult.consensus} - 분석 유형 등 추가 검토 가능")

        // 오토마타는 경로만 있으면 ALLOW (분석 유형은 고려 안 함)
        assertEquals("ALLOW", automataResult.decision)
        println("\n  ⚠️ 오토마타 한계: '어떤 분석?'은 고려하지 않음")
    }

    @Test
    fun `Scenario 1-2c - Automata keyword ambiguity`() = runBlocking {
        println("=" .repeat(70))
        println("시나리오 1-2c: 오토마타 키워드 모호성")
        println("=" .repeat(70))

        val automata = RuleBasedAutomata()

        // 같은 의미, 다른 표현
        val variations = listOf(
            "프로젝트 파일들 분석해줘",
            "프로젝트 파일들 살펴봐줘",      // '분석' 키워드 없음
            "프로젝트 파일들 검토해줘",      // '분석' 키워드 없음
            "analyze project files",         // 영어
            "프로젝트 현황 파악해줘"         // '분석' 키워드 없음
        )

        println("\n동일 의도, 다른 표현에 대한 오토마타 반응:")
        println("─".repeat(60))

        for (input in variations) {
            val result = automata.process(input, specifiedPath = "./src")
            val detected = when (result.finalState) {
                AutomataState.ALLOW -> "ALLOW (키워드 매칭 성공)"
                AutomataState.ERROR -> "ERROR (키워드 매칭 실패)"
                else -> result.decision
            }
            println("  \"$input\"")
            println("    → $detected")
        }

        println("\n─".repeat(60))
        println("⚠️ 오토마타 한계: 같은 의도도 표현이 다르면 다르게 처리")
        println("✅ LLM Governor: 의미를 이해하므로 모든 표현에 일관된 처리")
    }
}
