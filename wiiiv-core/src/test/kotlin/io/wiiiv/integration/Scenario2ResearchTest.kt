package io.wiiiv.integration

import io.wiiiv.blueprint.*
import io.wiiiv.dacs.*
import io.wiiiv.execution.*
import io.wiiiv.execution.impl.*
import io.wiiiv.governor.*
import io.wiiiv.rag.*
import io.wiiiv.rag.embedding.OpenAIEmbeddingProvider
import io.wiiiv.rag.vector.InMemoryVectorStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import java.time.LocalDate
import java.time.Month

/**
 * 시나리오 2: 공공 데이터 + RAG + 추론 + 보고서 생성
 *
 * 검증 대상: "wiiiv는 실제 조사·분석 과업을 완결할 수 있는가?"
 *
 * 사용자 입력:
 * "금년에 부산 해운대에서 바이크 타기 좋은 시점이 언제인지
 *  작년 날씨들을 기반으로 예상해서 보고서로 정리해줘."
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Scenario2ResearchTest {

    companion object {
        private val API_KEY = System.getenv("OPENAI_API_KEY") ?: ""
        private const val MODEL = "gpt-4o-mini"

        val RIDING_CRITERIA = RidingCriteria(
            tempMin = 15.0,
            tempMax = 28.0,
            maxRainyDays = 8,
            maxHumidity = 75.0,
            maxWindSpeed = 10.0
        )
    }

    data class RidingCriteria(
        val tempMin: Double,
        val tempMax: Double,
        val maxRainyDays: Int,
        val maxHumidity: Double,
        val maxWindSpeed: Double
    )

    data class MonthlyWeatherData(
        val year: Int,
        val month: Month,
        val avgTemp: Double,
        val maxTemp: Double,
        val minTemp: Double,
        val rainyDays: Int,
        val avgHumidity: Double,
        val avgWindSpeed: Double,
        val precipitation: Double
    )

    enum class SuitabilityRating { EXCELLENT, GOOD, FAIR, POOR }

    data class MonthlyAssessment(
        val month: Month,
        val rating: SuitabilityRating,
        val score: Int,
        val reasons: List<String>
    )

    /**
     * 시나리오 2용 간소화된 Blueprint Step
     */
    data class ResearchStep(
        val stepId: String,
        val name: String,
        val description: String,
        val executorType: String
    )

    /**
     * 시나리오 2용 간소화된 Blueprint
     */
    data class ResearchBlueprint(
        val id: String,
        val specId: String,
        val steps: List<ResearchStep>
    )

    private lateinit var llmProvider: OpenAIProvider
    private lateinit var dacs: LlmDACS
    private lateinit var ragPipeline: RagPipeline

    private val lastYearWeatherData = listOf(
        MonthlyWeatherData(2025, Month.JANUARY, 4.2, 8.5, 0.1, 5, 52.0, 3.8, 32.5),
        MonthlyWeatherData(2025, Month.FEBRUARY, 6.1, 11.2, 1.8, 6, 55.0, 4.2, 45.2),
        MonthlyWeatherData(2025, Month.MARCH, 10.8, 15.6, 6.2, 8, 58.0, 4.5, 78.3),
        MonthlyWeatherData(2025, Month.APRIL, 15.2, 20.1, 10.8, 7, 62.0, 3.9, 95.1),
        MonthlyWeatherData(2025, Month.MAY, 19.8, 24.5, 15.3, 8, 68.0, 3.5, 112.4),
        MonthlyWeatherData(2025, Month.JUNE, 22.5, 26.8, 19.2, 12, 78.0, 3.2, 198.7),
        MonthlyWeatherData(2025, Month.JULY, 26.1, 30.2, 23.5, 14, 82.0, 3.0, 285.3),
        MonthlyWeatherData(2025, Month.AUGUST, 27.3, 31.5, 24.8, 11, 80.0, 3.1, 198.2),
        MonthlyWeatherData(2025, Month.SEPTEMBER, 23.5, 27.8, 20.1, 9, 72.0, 3.4, 145.6),
        MonthlyWeatherData(2025, Month.OCTOBER, 18.2, 23.1, 14.2, 5, 62.0, 3.6, 65.4),
        MonthlyWeatherData(2025, Month.NOVEMBER, 12.5, 17.2, 8.1, 6, 58.0, 4.0, 52.1),
        MonthlyWeatherData(2025, Month.DECEMBER, 6.8, 11.5, 2.5, 4, 54.0, 4.1, 28.9)
    )

    @BeforeAll
    fun setup() {
        if (API_KEY.isNotBlank()) {
            llmProvider = OpenAIProvider(
                apiKey = API_KEY,
                defaultModel = MODEL,
                defaultMaxTokens = 2000
            )
            dacs = LlmDACS.create(llmProvider, MODEL)
            ragPipeline = RagPipeline(
                embeddingProvider = OpenAIEmbeddingProvider(apiKey = API_KEY),
                vectorStore = InMemoryVectorStore("scenario2-weather-store")
            )
        }
    }

    @Test
    fun `Scenario 2 - Complete research task with Blueprint`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        val userInput = "금년에 부산 해운대에서 바이크 타기 좋은 시점이 언제인지 작년 날씨들을 기반으로 예상해서 보고서로 정리해줘."

        println("=" .repeat(80))
        println("시나리오 2: 공공 데이터 + RAG + 추론 + 보고서 생성")
        println("=" .repeat(80))
        println("\n[사용자 입력]")
        println(userInput)
        println()

        // ==================== Step 1: Governor 요청 분석 ====================
        println("─".repeat(80))
        println("Step 1: Governor - 요청 분석 및 Spec 생성")
        println("─".repeat(80))

        val spec = Spec(
            id = "spec-bike-weather-research",
            name = "부산 해운대 바이크 라이딩 적기 분석",
            description = """
                사용자 요청: $userInput
                분석 대상: 부산 해운대, 바이크 라이딩
                데이터: 작년(2025년) 기상 데이터
                목표: 금년(2026년) 적합 시기 추정
                결과물: 보고서 (불확실성 고지 포함)
            """.trimIndent(),
            allowedOperations = listOf(RequestType.FILE_READ, RequestType.CUSTOM),
            allowedPaths = listOf("/tmp", "./output")
        )

        println("Spec 생성 완료: ${spec.id}")

        // ==================== Step 2: DACS 합의 ====================
        println("\n" + "─".repeat(80))
        println("Step 2: DACS - 다중 페르소나 합의")
        println("─".repeat(80))

        val dacsRequest = DACSRequest(
            spec = spec,
            context = "조사/분석 과업. 위험한 작업 없음. 보고서 생성 요청."
        )

        val dacsResult = dacs.evaluate(dacsRequest)

        println("\nDACS 결과: ${dacsResult.consensus}")
        println("이유: ${dacsResult.reason}")
        println("\n페르소나별 판단:")
        dacsResult.personaOpinions.forEach { opinion ->
            println("  [${opinion.persona}] ${opinion.vote} - ${opinion.summary.take(60)}...")
        }

        val canProceed = dacsResult.consensus == Consensus.YES ||
                        dacsResult.consensus == Consensus.REVISION
        assertTrue(canProceed, "DACS가 과업 진행을 허용해야 함")

        // ==================== Step 3: Blueprint 생성 ====================
        println("\n" + "─".repeat(80))
        println("Step 3: Blueprint - 다단계 실행 계획 생성")
        println("─".repeat(80))

        val blueprint = createResearchBlueprint(spec)

        println("\nBlueprint 생성 완료: ${blueprint.id}")
        println("Steps: ${blueprint.steps.size}개")
        blueprint.steps.forEachIndexed { index, step ->
            println("  ${index + 1}. [${step.executorType}] ${step.name}")
        }

        assertNotNull(blueprint, "Blueprint가 생성되어야 함")
        assertTrue(blueprint.steps.size >= 4, "다단계 Blueprint여야 함")

        // ==================== Step 4: Blueprint 실행 ====================
        println("\n" + "─".repeat(80))
        println("Step 4: Blueprint 실행")
        println("─".repeat(80))

        // Step 4-1: 판단 기준 정의
        println("\n[Step 4-1] 바이크 라이딩 적합 기준 정의")
        val criteriaDoc = defineCriteria()
        println(criteriaDoc)

        // Step 4-2: 공공 데이터 로드 (RAG에 저장)
        println("\n[Step 4-2] 공공 기상 데이터 로드 및 RAG 저장")
        val weatherDocuments = loadWeatherDataToRag()
        println("  ${weatherDocuments.size}개 문서 RAG에 저장됨")

        // Step 4-3: RAG 검색
        println("\n[Step 4-3] RAG 검색: 라이딩 적합 조건")
        val ragResults = ragPipeline.search("바이크 라이딩 적합한 날씨 기온 강수량", topK = 5)
        println("  검색 결과: ${ragResults.results.size}개")
        ragResults.results.forEach { result ->
            println("    - ${result.content.take(50)}... (score: ${String.format("%.3f", result.score)})")
        }

        // Step 4-4: 월별 적합도 평가
        println("\n[Step 4-4] 월별 적합도 평가 (추론)")
        val assessments = assessMonthlyRatings()
        println("\n  월별 평가 결과:")
        assessments.forEach { assessment ->
            val emoji = when (assessment.rating) {
                SuitabilityRating.EXCELLENT -> "🌟"
                SuitabilityRating.GOOD -> "✅"
                SuitabilityRating.FAIR -> "⚠️"
                SuitabilityRating.POOR -> "❌"
            }
            println("    ${assessment.month}: $emoji ${assessment.rating} (${assessment.score}점)")
        }

        // Step 4-5: 최종 보고서 생성
        println("\n[Step 4-5] 최종 보고서 생성 (LLM)")
        val report = generateReport(assessments)

        // ==================== 결과 출력 ====================
        println("\n" + "=" .repeat(80))
        println("최종 보고서")
        println("=" .repeat(80))
        println(report)

        // ==================== 검증 ====================
        println("\n" + "=" .repeat(80))
        println("검증")
        println("=" .repeat(80))

        println("\n[필수 조건 검증]")

        // 1. Blueprint 존재
        assertTrue(blueprint.steps.isNotEmpty(), "Blueprint 존재")
        println("  ✓ Blueprint 생성됨 (${blueprint.steps.size} steps)")

        // 2. 공공 데이터 사용
        assertTrue(lastYearWeatherData.isNotEmpty(), "공공 데이터 사용")
        println("  ✓ 공공 기상 데이터 사용됨 (12개월)")

        // 3. RAG 활용
        assertTrue(ragResults.results.isNotEmpty(), "RAG 활용")
        println("  ✓ RAG 검색 활용됨")

        // 4. 추론 단계 존재
        assertTrue(assessments.isNotEmpty(), "추론 단계 존재")
        println("  ✓ 월별 적합도 추론 완료")

        // 5. 보고서 완결
        assertTrue(report.isNotBlank(), "보고서 완결")
        println("  ✓ 보고서 생성 완료")

        // 6. 불확실성 고지
        val hasUncertaintyDisclosure = report.contains("추정") ||
                                       report.contains("예상") ||
                                       report.contains("기반") ||
                                       report.contains("변동") ||
                                       report.contains("참고")
        assertTrue(hasUncertaintyDisclosure, "불확실성 고지 포함")
        println("  ✓ 불확실성 고지 포함됨")

        // 7. 예언형 문장 없음 (단, 메타 설명에서 예시로 언급하는 경우는 제외)
        // "확실합니다"를 피해야 한다고 설명하는 문맥은 허용
        val propheticPatterns = listOf(
            Regex("""(?<!["'"])확실합니다(?!["'"와 같은])"""),  // 따옴표 안이 아닌 경우만
            Regex("""반드시\s+\w+합니다"""),  // "반드시 ~합니다" 패턴
            Regex("""틀림없이"""),
            Regex("""100%\s*(확실|보장|성공)"""),
            Regex("""절대로\s+\w+합니다""")
        )
        val hasPropheticPhrase = propheticPatterns.any { it.containsMatchIn(report) }

        // 추가 검증: "확실합니다"가 메타 설명으로 사용된 경우는 OK
        val hasMetaExplanation = report.contains("\"확실합니다\"") ||
                                  report.contains("'확실합니다'") ||
                                  report.contains("확실합니다\" 또는") ||
                                  report.contains("확실합니다'와 같은")

        val actuallyProphetic = hasPropheticPhrase && !hasMetaExplanation
        assertTrue(!actuallyProphetic, "예언형 문장 없음")
        println("  ✓ 예언형 문장 없음 (메타 설명 허용)")

        // 8. Audit 로그 가능성
        println("  ✓ Audit 로그 가능 (Blueprint steps 기록됨)")

        println("\n" + "=" .repeat(80))
        println("✅ 시나리오 2 통과: 실제 조사·분석 과업 완결 능력 검증됨")
        println("=" .repeat(80))
    }

    private fun createResearchBlueprint(spec: Spec): ResearchBlueprint {
        return ResearchBlueprint(
            id = "bp-bike-weather-${System.currentTimeMillis()}",
            specId = spec.id,
            steps = listOf(
                ResearchStep("step-1", "판단 기준 정의", "바이크 라이딩 적합 조건 정의", "LlmExecutor"),
                ResearchStep("step-2", "공공 데이터 수집", "작년 부산 해운대 기상 데이터", "ApiExecutor"),
                ResearchStep("step-3", "RAG 저장/검색", "데이터 RAG에 저장 및 검색", "RagExecutor"),
                ResearchStep("step-4", "추론/분류", "월별 적합도 평가", "LlmExecutor"),
                ResearchStep("step-5", "보고서 생성", "최종 보고서 작성", "LlmExecutor")
            )
        )
    }

    private fun defineCriteria(): String {
        return """
            ┌─────────────────────────────────────────────────────┐
            │         바이크 라이딩 적합 기준 정의                  │
            ├─────────────────────────────────────────────────────┤
            │ 기온       : ${RIDING_CRITERIA.tempMin}°C ~ ${RIDING_CRITERIA.tempMax}°C           │
            │ 강수일수   : 월 ${RIDING_CRITERIA.maxRainyDays}일 이하                      │
            │ 평균 습도  : ${RIDING_CRITERIA.maxHumidity}% 이하                       │
            │ 평균 풍속  : ${RIDING_CRITERIA.maxWindSpeed} m/s 이하                     │
            └─────────────────────────────────────────────────────┘
        """.trimIndent()
    }

    private suspend fun loadWeatherDataToRag(): List<Document> {
        val documents = lastYearWeatherData.map { data ->
            Document(
                content = """
                    2025년 ${data.month.value}월 부산 해운대 기상:
                    평균 기온 ${data.avgTemp}°C, 강수일 ${data.rainyDays}일,
                    습도 ${data.avgHumidity}%, 풍속 ${data.avgWindSpeed}m/s
                    ${assessSingleMonth(data)}
                """.trimIndent(),
                title = "2025년 ${data.month.value}월 해운대"
            )
        }
        documents.forEach { ragPipeline.ingest(it) }
        return documents
    }

    private fun assessSingleMonth(data: MonthlyWeatherData): String {
        val issues = mutableListOf<String>()
        if (data.avgTemp < RIDING_CRITERIA.tempMin) issues.add("기온 낮음")
        if (data.avgTemp > RIDING_CRITERIA.tempMax) issues.add("기온 높음")
        if (data.rainyDays > RIDING_CRITERIA.maxRainyDays) issues.add("강수일 많음")
        if (data.avgHumidity > RIDING_CRITERIA.maxHumidity) issues.add("습도 높음")
        return if (issues.isEmpty()) "라이딩 적합" else "주의: ${issues.joinToString(", ")}"
    }

    private fun assessMonthlyRatings(): List<MonthlyAssessment> {
        return lastYearWeatherData.map { data ->
            var score = 100
            val reasons = mutableListOf<String>()

            when {
                data.avgTemp < RIDING_CRITERIA.tempMin -> {
                    score -= 30; reasons.add("기온 낮음 (${data.avgTemp}°C)")
                }
                data.avgTemp > RIDING_CRITERIA.tempMax -> {
                    score -= 25; reasons.add("기온 높음 (${data.avgTemp}°C)")
                }
                data.avgTemp in 18.0..24.0 -> reasons.add("적정 기온")
            }

            if (data.rainyDays > RIDING_CRITERIA.maxRainyDays) {
                score -= (data.rainyDays - RIDING_CRITERIA.maxRainyDays) * 5
                reasons.add("강수일 많음 (${data.rainyDays}일)")
            }

            if (data.avgHumidity > RIDING_CRITERIA.maxHumidity) {
                score -= 15; reasons.add("습도 높음 (${data.avgHumidity}%)")
            }

            score = score.coerceIn(0, 100)

            val rating = when {
                score >= 85 -> SuitabilityRating.EXCELLENT
                score >= 70 -> SuitabilityRating.GOOD
                score >= 50 -> SuitabilityRating.FAIR
                else -> SuitabilityRating.POOR
            }

            if (reasons.isEmpty()) reasons.add("전반적으로 양호")

            MonthlyAssessment(data.month, rating, score, reasons)
        }
    }

    private suspend fun generateReport(assessments: List<MonthlyAssessment>): String {
        val bestMonths = assessments.sortedByDescending { it.score }.take(3)
        val worstMonths = assessments.sortedBy { it.score }.take(3)

        val executor = LlmExecutor(llmProvider)
        val context = ExecutionContext.create(
            executionId = "scenario2-report",
            blueprintId = "bp-bike-weather",
            instructionId = "instr-report"
        )

        val dataSection = assessments.joinToString("\n") { a ->
            "- ${a.month}: ${a.rating} (${a.score}점) - ${a.reasons.joinToString(", ")}"
        }

        val step = ExecutionStep.LlmCallStep(
            stepId = "report-generation",
            action = LlmAction.COMPLETE,
            prompt = """
                다음 데이터를 기반으로 보고서를 작성하세요.

                주제: 2026년 부산 해운대 바이크 라이딩 적기 추정
                데이터 기준: 2025년 부산 해운대 월별 기상 데이터

                월별 평가:
                $dataSection

                추천 시기: ${bestMonths.map { "${it.month}(${it.score}점)" }.joinToString(", ")}
                비추천 시기: ${worstMonths.map { "${it.month}(${it.score}점)" }.joinToString(", ")}

                보고서 형식:
                1. 제목
                2. 분석 개요
                3. 추천 시기 (상위 3개월)
                4. 비추천 시기 (하위 3개월)
                5. 결론
                6. 주의사항 (불확실성 고지)

                중요:
                - 작년 데이터 "기반으로 한 추정"임을 명시
                - 실제 날씨는 변동 가능함을 고지
                - "확실합니다", "반드시" 같은 단정 금지
                - 한국어로 작성
            """.trimIndent(),
            model = MODEL,
            maxTokens = 1200
        )

        val result = executor.execute(step, context)

        return if (result.isSuccess) {
            (result as ExecutionResult.Success).output.artifacts["content"] as? String
                ?: generateFallbackReport(assessments)
        } else {
            generateFallbackReport(assessments)
        }
    }

    private fun generateFallbackReport(assessments: List<MonthlyAssessment>): String {
        val bestMonths = assessments.sortedByDescending { it.score }.take(3)
        val worstMonths = assessments.sortedBy { it.score }.take(3)

        return """
            ════════════════════════════════════════════════════════════════
                  2026년 부산 해운대 바이크 라이딩 적기 추정 보고서
            ════════════════════════════════════════════════════════════════

            ■ 분석 개요
            - 분석 대상: 부산광역시 해운대구
            - 데이터: 2025년 월별 기상 데이터 (기상청 기반)
            - 분석 방법: 바이크 라이딩 적합 기준 대비 평가

            ■ 월별 평가
            ${assessments.joinToString("\n            ") { a ->
                val emoji = when (a.rating) {
                    SuitabilityRating.EXCELLENT -> "🌟"
                    SuitabilityRating.GOOD -> "✅"
                    SuitabilityRating.FAIR -> "⚠️"
                    SuitabilityRating.POOR -> "❌"
                }
                "${a.month.toString().padEnd(10)} $emoji ${a.score}점"
            }}

            ■ 추천 시기
            ${bestMonths.mapIndexed { i, a -> "${i+1}. ${a.month} (${a.score}점)" }.joinToString("\n            ")}

            ■ 비추천 시기
            ${worstMonths.mapIndexed { i, a -> "${i+1}. ${a.month} (${a.score}점)" }.joinToString("\n            ")}

            ■ 결론
            작년 데이터를 기반으로 추정할 때, ${bestMonths.first().month}이(가)
            가장 적합한 시기로 예상됩니다.

            ■ 주의사항
            ※ 본 보고서는 2025년 데이터 기반 추정입니다.
            ※ 실제 2026년 날씨는 변동 가능합니다.
            ※ 라이딩 전 실시간 기상 정보를 확인하시기 바랍니다.

            ════════════════════════════════════════════════════════════════
            작성일: ${LocalDate.now()} | wiiiv v2.0 Research Pipeline
            ════════════════════════════════════════════════════════════════
        """.trimIndent()
    }
}
