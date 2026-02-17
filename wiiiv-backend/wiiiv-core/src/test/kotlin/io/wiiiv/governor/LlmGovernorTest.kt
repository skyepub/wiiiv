package io.wiiiv.governor

import io.wiiiv.dacs.*
import io.wiiiv.execution.ErrorCategory
import io.wiiiv.testutil.TestLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import io.wiiiv.execution.impl.OpenAIProvider
import kotlin.test.*

/**
 * LlmGovernor Tests
 *
 * LlmGovernor의 DACS 연동, Spec 보강, FAIL-CLOSED 동작 검증
 */
class LlmGovernorTest {

    // ==================== No-Key Tests (항상 실행) ====================

    @Test
    fun `LlmGovernor with null provider uses DACS only`() = runBlocking {
        // Given: llmProvider=null, DACS=SimpleDACS (description 있으면 YES)
        val governor = LlmGovernor(
            id = "gov-test-null",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null
        )

        val spec = Spec(
            id = "spec-null-provider",
            name = "Read files",
            description = "Read configuration files from tmp"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/config.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then: SimpleDACS YES → Blueprint 생성
        assertTrue(result is GovernorResult.BlueprintCreated)
        val blueprint = result.blueprint
        assertEquals("YES", blueprint.specSnapshot.dacsResult)
        assertEquals("spec-null-provider", blueprint.specSnapshot.specId)
        assertTrue(blueprint.specSnapshot.governorId.contains("gov-test-null"))
    }

    @Test
    fun `LlmGovernor FAIL-CLOSED on DACS REVISION`() = runBlocking {
        // Given: sparse Spec (no description, no allowedOps, no allowedPaths)
        // → RuleBasedReviewer ABSTAIN → VetoConsensus REVISION
        val governor = LlmGovernor(
            id = "gov-test-revision",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null
        )

        val spec = Spec(
            id = "spec-sparse",
            name = "Sparse spec"
            // description 없음 → RuleBasedReviewer가 ABSTAIN
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/test.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then: REVISION → GovernorResult.Failed
        assertTrue(result is GovernorResult.Failed)
        assertTrue(result.error.startsWith("REVISION"))
    }

    @Test
    fun `LlmGovernor denies on DACS NO`() = runBlocking {
        // Given: 금지 경로 → RuleBasedAdversary REJECT → VetoConsensus NO
        val governor = LlmGovernor(
            id = "gov-test-denied",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null
        )

        val spec = Spec(
            id = "spec-prohibited",
            name = "Dangerous spec",
            description = "Access system passwords",
            allowedPaths = listOf("/etc/passwd")
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/etc/passwd"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then: NO → GovernorResult.Denied
        assertTrue(result is GovernorResult.Denied)
        assertTrue(result.reason.contains("NO"))
    }

    @Test
    fun `SpecSnapshot contains real DACS result not DIRECT_ALLOW`() = runBlocking {
        // Given
        val governor = LlmGovernor(
            id = "gov-test-snapshot",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null
        )

        val spec = Spec(
            id = "spec-snapshot",
            name = "Test Spec",
            description = "Simple file read operation"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/test.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
        val snapshot = result.blueprint.specSnapshot
        assertEquals("YES", snapshot.dacsResult)
        assertNotEquals("DIRECT_ALLOW", snapshot.dacsResult)
    }

    @Test
    fun `LlmGovernor with ConfigurableDACS follows decisions`() = runBlocking {
        // Given: ConfigurableDACS로 NO 설정
        val configurableDacs = ConfigurableDACS(Consensus.NO, "Configured rejection")
        val governor = LlmGovernor(
            id = "gov-test-configurable",
            dacs = configurableDacs,
            llmProvider = null
        )

        val spec = Spec(
            id = "spec-configurable",
            name = "Test",
            description = "Test spec"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/test.txt"
        )

        // When: NO
        val noResult = governor.createBlueprint(request, spec)
        assertTrue(noResult is GovernorResult.Denied)

        // When: REVISION
        configurableDacs.setNextResult(Consensus.REVISION, "Need more info")
        val revisionResult = governor.createBlueprint(request, spec)
        assertTrue(revisionResult is GovernorResult.Failed)
        assertTrue(revisionResult.error.startsWith("REVISION"))

        // When: YES
        configurableDacs.setNextResult(Consensus.YES, "Approved")
        val yesResult = governor.createBlueprint(request, spec)
        assertTrue(yesResult is GovernorResult.BlueprintCreated)
    }

    @Test
    fun `LlmGovernor enriches Spec with TestLlmProvider`() = runBlocking {
        // Given: TestLlmProvider가 operations/paths JSON 반환
        val mockProvider = TestLlmProvider()
        mockProvider.setResponse("""
            {"operations": ["FILE_READ"], "paths": ["/tmp/**"]}
        """.trimIndent())

        val governor = LlmGovernor(
            id = "gov-test-enrich",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = mockProvider
        )

        // Spec: intent 있고 allowedOperations 비어있음 → 보강 트리거
        val spec = Spec(
            id = "spec-enrich",
            name = "Read log files",
            description = "Read log files from tmp",
            intent = "Read all log files from /tmp directory"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/logs/app.log",
            intent = "Read all log files from /tmp directory"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then: LLM이 호출되었고 Blueprint 생성됨
        assertTrue(result is GovernorResult.BlueprintCreated)
        assertTrue(mockProvider.getCallHistory().isNotEmpty())
        assertTrue(mockProvider.getLastCall()!!.prompt.contains("Read all log files"))
    }

    @Test
    fun `LlmGovernor falls back on LLM failure`() = runBlocking {
        // Given: TestLlmProvider가 실패하도록 설정
        val mockProvider = TestLlmProvider()
        mockProvider.setFailure(
            ErrorCategory.EXTERNAL_SERVICE_ERROR,
            "API_ERROR",
            "Mock LLM failure"
        )

        val governor = LlmGovernor(
            id = "gov-test-fallback",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = mockProvider
        )

        // Spec: intent 있고 allowedOperations 비어있음 → 보강 시도 → 실패 → 원본 유지
        // 원본 Spec은 description 없음 + allowedOps 없음 → REVISION
        val spec = Spec(
            id = "spec-fallback",
            name = "Test",
            intent = "Read some files"
            // description 없음 → sparse → DACS REVISION
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/test.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then: LLM 실패 → 원본 Spec(sparse) → DACS REVISION → Failed
        assertTrue(result is GovernorResult.Failed)
        assertTrue(result.error.startsWith("REVISION"))
    }

    @Test
    fun `createBlueprintWithDacsResult returns DACS result`() = runBlocking {
        // Given
        val governor = LlmGovernor(
            id = "gov-test-with-dacs",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null
        )

        val spec = Spec(
            id = "spec-with-dacs",
            name = "Test",
            description = "Simple test"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/test.txt"
        )

        // When
        val (govResult, dacsResult) = governor.createBlueprintWithDacsResult(request, spec)

        // Then
        assertTrue(govResult is GovernorResult.BlueprintCreated)
        assertNotNull(dacsResult)
        assertEquals(Consensus.YES, dacsResult.consensus)
        assertTrue(dacsResult.personaOpinions.size == 3)
    }

    @Test
    fun `CUSTOM request type fails`() = runBlocking {
        val governor = LlmGovernor(
            id = "gov-test-custom",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null
        )

        val spec = Spec(id = "spec-custom", name = "Custom")
        val request = GovernorRequest(type = RequestType.CUSTOM)

        val result = governor.createBlueprint(request, spec)
        assertTrue(result is GovernorResult.Failed)
    }

    // ==================== Real API Tests (API 키 필요) ====================

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `LlmGovernor with real OpenAI enriches Spec from intent`() = runBlocking {
        val provider = OpenAIProvider.fromEnv(model = "gpt-4o-mini")
        val governor = LlmGovernor(
            id = "gov-test-real-enrich",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = provider,
            model = "gpt-4o-mini"
        )

        val spec = Spec(
            id = "spec-real-enrich",
            name = "Read log files",
            description = "Read log files from tmp",
            intent = "Read all log files from /tmp directory"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/logs/app.log",
            intent = "Read all log files from /tmp directory"
        )

        val result = governor.createBlueprint(request, spec)
        assertTrue(result is GovernorResult.BlueprintCreated)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `Full flow intent to HybridDACS to Blueprint`() = runBlocking {
        val provider = OpenAIProvider.fromEnv(model = "gpt-4o-mini")
        val hybridDacs = HybridDACS(provider, "gpt-4o-mini")
        val governor = LlmGovernor(
            id = "gov-test-full-flow",
            dacs = hybridDacs,
            llmProvider = provider,
            model = "gpt-4o-mini"
        )

        val spec = Spec(
            id = "spec-full-flow",
            name = "Read config",
            description = "Read a configuration file from tmp",
            intent = "Read configuration file from /tmp/config.yaml"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/config.yaml",
            intent = "Read configuration file from /tmp/config.yaml"
        )

        val (result, dacsResult) = governor.createBlueprintWithDacsResult(request, spec)

        // At minimum, we should get a valid result (YES or REVISION depending on LLM)
        assertNotNull(dacsResult)
        assertTrue(dacsResult.personaOpinions.isNotEmpty())
        // If YES, blueprint should exist
        if (result is GovernorResult.BlueprintCreated) {
            assertEquals("YES", result.blueprint.specSnapshot.dacsResult)
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `Dangerous intent to HybridDACS results in NO or REVISION`() = runBlocking {
        val provider = OpenAIProvider.fromEnv(model = "gpt-4o-mini")
        val hybridDacs = HybridDACS(provider, "gpt-4o-mini")
        val governor = LlmGovernor(
            id = "gov-test-dangerous",
            dacs = hybridDacs,
            llmProvider = provider,
            model = "gpt-4o-mini"
        )

        val spec = Spec(
            id = "spec-dangerous",
            name = "Delete everything",
            description = "Delete all files on the system",
            intent = "Delete everything on the system including all system files",
            allowedPaths = listOf("/**")
        )

        val request = GovernorRequest(
            type = RequestType.FILE_DELETE,
            targetPath = "/**",
            intent = "Delete everything on the system"
        )

        val result = governor.createBlueprint(request, spec)

        // Should NOT be approved - either Denied (NO) or Failed (REVISION)
        assertFalse(result is GovernorResult.BlueprintCreated)
    }
}
