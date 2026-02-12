package io.wiiiv.dacs

import io.wiiiv.execution.ErrorCategory
import io.wiiiv.execution.LlmAction
import io.wiiiv.execution.impl.*
import io.wiiiv.governor.RequestType
import io.wiiiv.governor.Spec
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * LLM Persona Tests
 *
 * LLM 기반 페르소나 테스트
 */
class LlmPersonaTest {

    private lateinit var mockProvider: MockLlmProvider

    @BeforeTest
    fun setup() {
        mockProvider = MockLlmProvider()
    }

    // ==================== LlmArchitect Tests ====================

    @Test
    fun `LlmArchitect should parse APPROVE response`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "Spec is well-structured", "concerns": []}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val spec = createTestSpec()

        val opinion = architect.evaluate(spec, null)

        assertEquals(PersonaType.ARCHITECT, opinion.persona)
        assertEquals(Vote.APPROVE, opinion.vote)
        assertEquals("Spec is well-structured", opinion.summary)
        assertTrue(opinion.concerns.isEmpty())
    }

    @Test
    fun `LlmArchitect should parse REJECT response`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "REJECT", "summary": "Spec is malformed", "concerns": ["Missing required fields"]}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val spec = createTestSpec()

        val opinion = architect.evaluate(spec, null)

        assertEquals(Vote.REJECT, opinion.vote)
        assertEquals("Spec is malformed", opinion.summary)
        assertEquals(listOf("Missing required fields"), opinion.concerns)
    }

    @Test
    fun `LlmArchitect should parse ABSTAIN response`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "ABSTAIN", "summary": "Need more information", "concerns": ["Description is vague"]}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val spec = createTestSpec()

        val opinion = architect.evaluate(spec, null)

        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertEquals("Need more information", opinion.summary)
    }

    @Test
    fun `LlmArchitect should handle markdown code block response`() = runBlocking {
        mockProvider.setMockResponse("""
            Here is my evaluation:
            ```json
            {"vote": "APPROVE", "summary": "All good", "concerns": []}
            ```
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertEquals(Vote.APPROVE, opinion.vote)
    }

    @Test
    fun `LlmArchitect should fallback to ABSTAIN on LLM error`() = runBlocking {
        mockProvider.setFailure(
            ErrorCategory.EXTERNAL_SERVICE_ERROR,
            "API_ERROR",
            "LLM API failed"
        )

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertTrue(opinion.summary.contains("LLM evaluation failed"))
    }

    @Test
    fun `LlmArchitect should fallback to ABSTAIN on parse error`() = runBlocking {
        mockProvider.setMockResponse("This is not valid JSON")

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertTrue(opinion.summary.contains("parse"))
    }

    // ==================== LlmReviewer Tests ====================

    @Test
    fun `LlmReviewer should evaluate requirements`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "Requirements are clear and consistent", "concerns": []}
        """.trimIndent())

        val reviewer = LlmReviewer(mockProvider)
        val opinion = reviewer.evaluate(createTestSpec(), null)

        assertEquals(PersonaType.REVIEWER, opinion.persona)
        assertEquals(Vote.APPROVE, opinion.vote)
    }

    @Test
    fun `LlmReviewer should identify unclear requirements`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "ABSTAIN", "summary": "Requirements need clarification", "concerns": ["Intent is unclear", "Operations don't match description"]}
        """.trimIndent())

        val reviewer = LlmReviewer(mockProvider)
        val opinion = reviewer.evaluate(createTestSpec(), null)

        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertEquals(2, opinion.concerns.size)
    }

    // ==================== LlmAdversary Tests ====================

    @Test
    fun `LlmAdversary should detect security risks`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "ABSTAIN", "summary": "Potential security risks identified", "concerns": ["Accessing system paths", "Broad scope"]}
        """.trimIndent())

        val adversary = LlmAdversary(mockProvider)
        val spec = Spec(
            id = "dangerous-spec",
            name = "Dangerous Spec",
            description = "Access system files",
            allowedPaths = listOf("/etc/config"),
            allowedOperations = listOf(RequestType.FILE_READ)
        )

        val opinion = adversary.evaluate(spec, null)

        assertEquals(PersonaType.ADVERSARY, opinion.persona)
        assertEquals(Vote.ABSTAIN, opinion.vote)  // REVISION 우선 원칙
    }

    @Test
    fun `LlmAdversary should reject explicit prohibitions`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "REJECT", "summary": "Explicitly prohibited pattern", "concerns": ["Accessing /etc/passwd"]}
        """.trimIndent())

        val adversary = LlmAdversary(mockProvider)
        val spec = Spec(
            id = "malicious-spec",
            name = "Malicious Spec",
            description = "Read password file",
            allowedPaths = listOf("/etc/passwd"),
            allowedOperations = listOf(RequestType.FILE_READ)
        )

        val opinion = adversary.evaluate(spec, null)

        assertEquals(Vote.REJECT, opinion.vote)
    }

    @Test
    fun `LlmAdversary should approve safe operations`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "No security concerns", "concerns": []}
        """.trimIndent())

        val adversary = LlmAdversary(mockProvider)
        val spec = Spec(
            id = "safe-spec",
            name = "Safe Spec",
            description = "Read temp files",
            allowedPaths = listOf("/tmp/myapp"),
            allowedOperations = listOf(RequestType.FILE_READ)
        )

        val opinion = adversary.evaluate(spec, null)

        assertEquals(Vote.APPROVE, opinion.vote)
    }

    // ==================== LlmDACS Tests ====================

    @Test
    fun `LlmDACS should return YES when all personas approve`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "All good", "concerns": []}
        """.trimIndent())

        val dacs = LlmDACS.create(mockProvider)
        val request = DACSRequest(spec = createTestSpec())

        val result = dacs.evaluate(request)

        assertEquals(Consensus.YES, result.consensus)
        assertEquals(3, result.personaOpinions.size)
        assertTrue(result.personaOpinions.all { it.vote == Vote.APPROVE })
    }

    @Test
    fun `LlmDACS should return NO when any persona rejects`() = runBlocking {
        // Simulate different responses for each persona call
        var callCount = 0
        val responses = listOf(
            """{"vote": "APPROVE", "summary": "Good", "concerns": []}""",
            """{"vote": "APPROVE", "summary": "Good", "concerns": []}""",
            """{"vote": "REJECT", "summary": "Security risk", "concerns": ["Dangerous"]}"""
        )

        val sequenceProvider = object : LlmProvider {
            override val defaultModel = "test"
            override val defaultMaxTokens = 1000
            override suspend fun call(request: LlmRequest): LlmResponse {
                val response = responses[callCount % responses.size]
                callCount++
                return LlmResponse(response, "stop", LlmUsage.ZERO)
            }
            override suspend fun cancel(executionId: String) = true
        }

        val dacs = LlmDACS.create(sequenceProvider)
        val request = DACSRequest(spec = createTestSpec())

        val result = dacs.evaluate(request)

        assertEquals(Consensus.NO, result.consensus)
    }

    @Test
    fun `LlmDACS should return REVISION when any persona abstains`() = runBlocking {
        var callCount = 0
        val responses = listOf(
            """{"vote": "APPROVE", "summary": "Good", "concerns": []}""",
            """{"vote": "ABSTAIN", "summary": "Need info", "concerns": ["Unclear"]}""",
            """{"vote": "APPROVE", "summary": "Good", "concerns": []}"""
        )

        val sequenceProvider = object : LlmProvider {
            override val defaultModel = "test"
            override val defaultMaxTokens = 1000
            override suspend fun call(request: LlmRequest): LlmResponse {
                val response = responses[callCount % responses.size]
                callCount++
                return LlmResponse(response, "stop", LlmUsage.ZERO)
            }
            override suspend fun cancel(executionId: String) = true
        }

        val dacs = LlmDACS.create(sequenceProvider)
        val request = DACSRequest(spec = createTestSpec())

        val result = dacs.evaluate(request)

        assertEquals(Consensus.REVISION, result.consensus)
    }

    @Test
    fun `LlmDACS should handle LLM failures gracefully`() = runBlocking {
        mockProvider.setFailure(
            ErrorCategory.EXTERNAL_SERVICE_ERROR,
            "API_ERROR",
            "All LLM calls failed"
        )

        val dacs = LlmDACS.create(mockProvider)
        val request = DACSRequest(spec = createTestSpec())

        val result = dacs.evaluate(request)

        // All personas fallback to ABSTAIN → REVISION
        assertEquals(Consensus.REVISION, result.consensus)
        assertTrue(result.personaOpinions.all { it.vote == Vote.ABSTAIN })
    }

    // ==================== HybridDACS Tests ====================

    @Test
    fun `HybridDACS should short-circuit on rule-based NO`() = runBlocking {
        // Rule-based should detect prohibited path
        val spec = Spec(
            id = "prohibited",
            name = "Prohibited Spec",
            description = "Access password file",
            allowedPaths = listOf("/etc/passwd"),
            allowedOperations = listOf(RequestType.FILE_READ)
        )

        val dacs = HybridDACS(mockProvider)
        val request = DACSRequest(spec = spec)

        // LLM should not be called for clear prohibitions
        val result = dacs.evaluate(request)

        assertEquals(Consensus.NO, result.consensus)
        // Only rule-based opinions (no LLM calls needed)
        assertEquals(3, result.personaOpinions.size)
    }

    @Test
    fun `HybridDACS should use LLM for non-obvious cases`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "All good", "concerns": []}
        """.trimIndent())

        val spec = Spec(
            id = "normal",
            name = "Normal Spec",
            description = "Read temp files",
            allowedPaths = listOf("/tmp/myapp"),
            allowedOperations = listOf(RequestType.FILE_READ)
        )

        val dacs = HybridDACS(mockProvider)
        val request = DACSRequest(spec = spec)

        val result = dacs.evaluate(request)

        assertEquals(Consensus.YES, result.consensus)
        // Both rule-based and LLM opinions
        assertEquals(6, result.personaOpinions.size)
    }

    // ==================== Context Handling Tests ====================

    @Test
    fun `LlmPersona should include context in prompt`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "Good", "concerns": []}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val spec = createTestSpec()
        val context = "This is for a trusted internal tool"

        architect.evaluate(spec, context)

        val lastCall = mockProvider.getLastCall()
        assertNotNull(lastCall)
        assertTrue(lastCall.prompt.contains("This is for a trusted internal tool"))
    }

    @Test
    fun `LlmPersona should include spec details in prompt`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "Good", "concerns": []}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val spec = Spec(
            id = "test-spec-123",
            name = "Test Spec Name",
            description = "This is a test description",
            allowedPaths = listOf("/path/one", "/path/two"),
            allowedOperations = listOf(RequestType.FILE_READ, RequestType.FILE_WRITE)
        )

        architect.evaluate(spec, null)

        val lastCall = mockProvider.getLastCall()
        assertNotNull(lastCall)
        assertTrue(lastCall.prompt.contains("test-spec-123"))
        assertTrue(lastCall.prompt.contains("Test Spec Name"))
        assertTrue(lastCall.prompt.contains("This is a test description"))
        assertTrue(lastCall.prompt.contains("/path/one"))
        assertTrue(lastCall.prompt.contains("FILE_READ"))
    }

    // ==================== Vote Parsing Edge Cases ====================

    @Test
    fun `LlmPersona should handle lowercase vote`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "approve", "summary": "Good", "concerns": []}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertEquals(Vote.APPROVE, opinion.vote)
    }

    @Test
    fun `LlmPersona should handle mixed case vote`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "Approve", "summary": "Good", "concerns": []}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertEquals(Vote.APPROVE, opinion.vote)
    }

    @Test
    fun `LlmPersona should handle JSON with extra fields`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "Good", "concerns": [], "reasoning": "extra field", "confidence": 0.9}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertEquals(Vote.APPROVE, opinion.vote)
        assertEquals("Good", opinion.summary)
    }

    @Test
    fun `LlmPersona should handle empty concerns array`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "Good", "concerns": []}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertTrue(opinion.concerns.isEmpty())
    }

    @Test
    fun `LlmPersona should handle missing concerns field`() = runBlocking {
        mockProvider.setMockResponse("""
            {"vote": "APPROVE", "summary": "Good"}
        """.trimIndent())

        val architect = LlmArchitect(mockProvider)
        val opinion = architect.evaluate(createTestSpec(), null)

        assertTrue(opinion.concerns.isEmpty())
    }

    // ==================== Helper ====================

    private fun createTestSpec(): Spec = Spec(
        id = "test-spec",
        name = "Test Spec",
        description = "A test spec for unit testing",
        allowedPaths = listOf("/tmp/test"),
        allowedOperations = listOf(RequestType.FILE_READ)
    )
}
