package io.wiiiv.dacs

import io.wiiiv.governor.RequestType
import io.wiiiv.governor.Spec
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * DACS Tests
 *
 * DACS 합의 엔진 검증
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1
 */
class DACSTest {

    // ==================== Basic DACS Tests ====================

    @Test
    fun `DACS should return YES for well-defined Spec`() = runBlocking {
        // Given
        val dacs = SimpleDACS.DEFAULT
        val spec = Spec(
            id = "spec-valid",
            name = "Valid Spec",
            description = "A well-defined spec for file operations",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/tmp/**")
        )
        val request = DACSRequest(spec = spec)

        // When
        val result = dacs.evaluate(request)

        // Then
        assertTrue(result.isYes, "Expected YES but got ${result.consensus}: ${result.reason}")
        assertTrue(result.canCreateBlueprint)
        assertEquals(3, result.personaOpinions.size)
    }

    @Test
    fun `DACS should return REVISION for Spec without restrictions and description`() = runBlocking {
        // Given
        val dacs = SimpleDACS.DEFAULT
        val spec = Spec(
            id = "spec-minimal",
            name = "Minimal Spec"
            // No allowedOperations, no allowedPaths, no description
        )
        val request = DACSRequest(spec = spec)

        // When
        val result = dacs.evaluate(request)

        // Then
        assertTrue(result.isRevision, "Expected REVISION but got ${result.consensus}: ${result.reason}")
        assertFalse(result.canCreateBlueprint)
    }

    @Test
    fun `DACS should return REVISION for dangerous Spec without path restrictions`() = runBlocking {
        // Given - REVISION 우선 원칙: 위험 요소는 NO가 아니라 REVISION
        // NO는 명백한 금지 패턴에만 사용
        val dacs = SimpleDACS.DEFAULT
        val spec = Spec(
            id = "spec-dangerous",
            name = "Dangerous Spec",
            allowedOperations = listOf(RequestType.FILE_DELETE)
            // No path restrictions - needs clarification, not outright rejection
        )
        val request = DACSRequest(spec = spec)

        // When
        val result = dacs.evaluate(request)

        // Then - REVISION (추가 맥락 필요), NOT NO
        assertTrue(result.isRevision, "Expected REVISION but got ${result.consensus}: ${result.reason}")
        assertFalse(result.canCreateBlueprint)
    }

    @Test
    fun `DACS should return NO only for explicitly prohibited patterns`() = runBlocking {
        // Given - 명백한 금지 패턴 (전체 시스템 접근)
        val dacs = SimpleDACS.DEFAULT
        val spec = Spec(
            id = "spec-prohibited",
            name = "Prohibited Spec",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/**")  // 전체 시스템 접근 - 명백한 금지
        )
        val request = DACSRequest(spec = spec)

        // When
        val result = dacs.evaluate(request)

        // Then - NO (명백한 금지 패턴)
        assertTrue(result.isNo, "Expected NO but got ${result.consensus}: ${result.reason}")
        assertFalse(result.canCreateBlueprint)
    }

    // ==================== Persona Tests ====================

    @Test
    fun `Architect persona should approve valid structure`() = runBlocking {
        // Given
        val architect = RuleBasedArchitect()
        val spec = Spec(id = "valid-id", name = "Valid Name")

        // When
        val opinion = architect.evaluate(spec, null)

        // Then
        assertEquals(PersonaType.ARCHITECT, opinion.persona)
        assertEquals(Vote.APPROVE, opinion.vote)
        assertTrue(opinion.concerns.isEmpty())
    }

    @Test
    fun `Architect persona should abstain for empty ID`() = runBlocking {
        // Given
        val architect = RuleBasedArchitect()
        val spec = Spec(id = "", name = "Some Name")

        // When
        val opinion = architect.evaluate(spec, null)

        // Then
        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertTrue(opinion.concerns.any { it.contains("ID is empty") })
    }

    @Test
    fun `Reviewer persona should approve clear requirements`() = runBlocking {
        // Given
        val reviewer = RuleBasedReviewer()
        val spec = Spec(
            id = "spec-1",
            name = "Clear Spec",
            allowedOperations = listOf(RequestType.FILE_READ)
        )

        // When
        val opinion = reviewer.evaluate(spec, null)

        // Then
        assertEquals(PersonaType.REVIEWER, opinion.persona)
        assertEquals(Vote.APPROVE, opinion.vote)
    }

    @Test
    fun `Reviewer persona should abstain for permissive Spec without description`() = runBlocking {
        // Given
        val reviewer = RuleBasedReviewer()
        val spec = Spec(
            id = "spec-permissive",
            name = "Permissive Spec"
            // No restrictions, no description
        )

        // When
        val opinion = reviewer.evaluate(spec, null)

        // Then
        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertTrue(opinion.concerns.any { it.contains("No restrictions") })
    }

    @Test
    fun `Adversary persona should approve safe Spec`() = runBlocking {
        // Given
        val adversary = RuleBasedAdversary()
        val spec = Spec(
            id = "spec-safe",
            name = "Safe Spec",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/tmp/**")
        )

        // When
        val opinion = adversary.evaluate(spec, null)

        // Then
        assertEquals(PersonaType.ADVERSARY, opinion.persona)
        assertEquals(Vote.APPROVE, opinion.vote)
    }

    @Test
    fun `Adversary persona should abstain for unrestricted DELETE (REVISION 우선)`() = runBlocking {
        // Given - REVISION 우선 원칙: 위험 요소는 REJECT가 아니라 ABSTAIN
        val adversary = RuleBasedAdversary()
        val spec = Spec(
            id = "spec-dangerous",
            name = "Dangerous Spec",
            allowedOperations = listOf(RequestType.FILE_DELETE)
            // No path restrictions - needs clarification
        )

        // When
        val opinion = adversary.evaluate(spec, null)

        // Then - ABSTAIN (추가 맥락 필요), NOT REJECT
        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertTrue(opinion.concerns.any { it.contains("scope clarification") })
    }

    @Test
    fun `Adversary persona should reject explicitly prohibited patterns`() = runBlocking {
        // Given - 명백한 금지 패턴만 REJECT
        val adversary = RuleBasedAdversary()
        val spec = Spec(
            id = "spec-prohibited",
            name = "Prohibited Spec",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/**")  // 전체 시스템 접근
        )

        // When
        val opinion = adversary.evaluate(spec, null)

        // Then - REJECT (명백한 금지 패턴)
        assertEquals(Vote.REJECT, opinion.vote)
        assertTrue(opinion.concerns.any { it.contains("prohibited") })
    }

    @Test
    fun `Adversary persona should abstain for COMMAND operations`() = runBlocking {
        // Given
        val adversary = RuleBasedAdversary()
        val spec = Spec(
            id = "spec-command",
            name = "Command Spec",
            allowedOperations = listOf(RequestType.COMMAND),
            allowedPaths = listOf("/tmp/**") // Has path restriction
        )

        // When
        val opinion = adversary.evaluate(spec, null)

        // Then
        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertTrue(opinion.concerns.any { it.contains("Command execution") })
    }

    @Test
    fun `Adversary persona should abstain for sensitive paths (REVISION 우선)`() = runBlocking {
        // Given - 민감 경로는 REJECT가 아니라 ABSTAIN (Gate에서 최종 판단)
        val adversary = RuleBasedAdversary()
        val spec = Spec(
            id = "spec-sensitive",
            name = "Sensitive Spec",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/etc/**")
        )

        // When
        val opinion = adversary.evaluate(spec, null)

        // Then - ABSTAIN (명시적 승인 필요), NOT REJECT
        assertEquals(Vote.ABSTAIN, opinion.vote)
        assertTrue(opinion.concerns.any { it.contains("approval") })
    }

    // ==================== Consensus Engine Tests ====================

    @Test
    fun `Veto engine should return YES when all approve`() {
        // Given
        val engine = VetoConsensusEngine()
        val opinions = listOf(
            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK")
        )

        // When
        val (consensus, _) = engine.derive(opinions)

        // Then
        assertEquals(Consensus.YES, consensus)
    }

    @Test
    fun `Veto engine should return NO when any reject`() {
        // Given
        val engine = VetoConsensusEngine()
        val opinions = listOf(
            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.ADVERSARY, Vote.REJECT, "Security issue")
        )

        // When
        val (consensus, reason) = engine.derive(opinions)

        // Then
        assertEquals(Consensus.NO, consensus)
        assertTrue(reason.contains("ADVERSARY"))
    }

    @Test
    fun `Veto engine should return REVISION when abstain present`() {
        // Given
        val engine = VetoConsensusEngine()
        val opinions = listOf(
            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.REVIEWER, Vote.ABSTAIN, "Needs clarification", listOf("Missing info")),
            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK")
        )

        // When
        val (consensus, _) = engine.derive(opinions)

        // Then
        assertEquals(Consensus.REVISION, consensus)
    }

    @Test
    fun `Strict engine should require unanimous approval for YES`() {
        // Given
        val engine = StrictConsensusEngine()
        val opinions = listOf(
            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.ADVERSARY, Vote.ABSTAIN, "Not sure")
        )

        // When
        val (consensus, _) = engine.derive(opinions)

        // Then
        assertEquals(Consensus.REVISION, consensus)
    }

    @Test
    fun `Strict engine should return YES only when all approve`() {
        // Given
        val engine = StrictConsensusEngine()
        val opinions = listOf(
            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK"),
            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK")
        )

        // When
        val (consensus, _) = engine.derive(opinions)

        // Then
        assertEquals(Consensus.YES, consensus)
    }

    // ==================== Integration Tests ====================

    @Test
    fun `DACS should preserve request ID in result`() = runBlocking {
        // Given
        val dacs = SimpleDACS.DEFAULT
        val request = DACSRequest(
            requestId = "test-request-123",
            spec = Spec(id = "spec", name = "Test")
        )

        // When
        val result = dacs.evaluate(request)

        // Then
        assertEquals("test-request-123", result.requestId)
    }

    @Test
    fun `DACS should be stateless - same input same output`() = runBlocking {
        // Given
        val dacs = SimpleDACS.DEFAULT
        val spec = Spec(
            id = "spec-stateless",
            name = "Stateless Test",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/tmp/**")
        )

        // When
        val result1 = dacs.evaluate(DACSRequest(spec = spec))
        val result2 = dacs.evaluate(DACSRequest(spec = spec))
        val result3 = dacs.evaluate(DACSRequest(spec = spec))

        // Then - All should be YES
        assertTrue(result1.isYes)
        assertTrue(result2.isYes)
        assertTrue(result3.isYes)
    }

    @Test
    fun `DACS should include all persona opinions in result`() = runBlocking {
        // Given
        val dacs = SimpleDACS.DEFAULT
        val request = DACSRequest(
            spec = Spec(id = "spec", name = "Test", allowedOperations = listOf(RequestType.FILE_READ))
        )

        // When
        val result = dacs.evaluate(request)

        // Then
        assertEquals(3, result.personaOpinions.size)
        assertTrue(result.personaOpinions.any { it.persona == PersonaType.ARCHITECT })
        assertTrue(result.personaOpinions.any { it.persona == PersonaType.REVIEWER })
        assertTrue(result.personaOpinions.any { it.persona == PersonaType.ADVERSARY })
    }

    // ==================== Test Utilities ====================

    @Test
    fun `AlwaysYesDACS should always return YES`() = runBlocking {
        // Given
        val dacs = AlwaysYesDACS()
        val dangerousSpec = Spec(
            id = "dangerous",
            name = "Dangerous",
            allowedOperations = listOf(RequestType.FILE_DELETE, RequestType.COMMAND)
        )

        // When
        val result = dacs.evaluate(DACSRequest(spec = dangerousSpec))

        // Then
        assertTrue(result.isYes)
    }

    @Test
    fun `AlwaysNoDACS should always return NO`() = runBlocking {
        // Given
        val dacs = AlwaysNoDACS("Test rejection")
        val safeSpec = Spec(
            id = "safe",
            name = "Safe",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/tmp/**")
        )

        // When
        val result = dacs.evaluate(DACSRequest(spec = safeSpec))

        // Then
        assertTrue(result.isNo)
        assertEquals("Test rejection", result.reason)
    }

    @Test
    fun `ConfigurableDACS should return configured result`() = runBlocking {
        // Given
        val dacs = ConfigurableDACS()
        val spec = Spec(id = "test", name = "Test")

        // When - Default is YES
        val result1 = dacs.evaluate(DACSRequest(spec = spec))
        assertTrue(result1.isYes)

        // When - Configure to NO
        dacs.setNextResult(Consensus.NO, "Configured NO")
        val result2 = dacs.evaluate(DACSRequest(spec = spec))
        assertTrue(result2.isNo)

        // When - Configure to REVISION
        dacs.setNextResult(Consensus.REVISION, "Need more info")
        val result3 = dacs.evaluate(DACSRequest(spec = spec))
        assertTrue(result3.isRevision)
    }

    // ==================== canCreateBlueprint Tests ====================

    @Test
    fun `canCreateBlueprint should be true only for YES`() = runBlocking {
        // Given
        val spec = Spec(id = "test", name = "Test")

        // YES
        val yesResult = AlwaysYesDACS().evaluate(DACSRequest(spec = spec))
        assertTrue(yesResult.canCreateBlueprint)

        // NO
        val noResult = AlwaysNoDACS().evaluate(DACSRequest(spec = spec))
        assertFalse(noResult.canCreateBlueprint)

        // REVISION
        val configurable = ConfigurableDACS()
        configurable.setNextResult(Consensus.REVISION, "Need revision")
        val revisionResult = configurable.evaluate(DACSRequest(spec = spec))
        assertFalse(revisionResult.canCreateBlueprint)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `Empty persona list should return REVISION`() {
        // Given
        val engine = VetoConsensusEngine()

        // When
        val (consensus, _) = engine.derive(emptyList())

        // Then
        assertEquals(Consensus.REVISION, consensus)
    }

    @Test
    fun `DACS with custom personas should work`() = runBlocking {
        // Given - Custom persona that always approves
        val alwaysApprovePersona = object : Persona {
            override val type = PersonaType.ARCHITECT
            override suspend fun evaluate(spec: Spec, context: String?) =
                PersonaOpinion(type, Vote.APPROVE, "Always OK")
        }

        val dacs = SimpleDACS(
            personas = listOf(alwaysApprovePersona),
            consensusEngine = VetoConsensusEngine()
        )

        val spec = Spec(id = "any", name = "Any")

        // When
        val result = dacs.evaluate(DACSRequest(spec = spec))

        // Then
        assertTrue(result.isYes)
        assertEquals(1, result.personaOpinions.size)
    }

    @Test
    fun `DACS with strict engine should be more conservative`() = runBlocking {
        // Given
        val strictDacs = SimpleDACS(
            consensusEngine = StrictConsensusEngine()
        )

        // Spec that would cause one ABSTAIN (COMMAND without full approval)
        val spec = Spec(
            id = "command-spec",
            name = "Command Spec",
            description = "Executes commands", // Has description
            allowedOperations = listOf(RequestType.COMMAND),
            allowedPaths = listOf("/tmp/**")
        )

        // When
        val result = strictDacs.evaluate(DACSRequest(spec = spec))

        // Then - Should be REVISION because Adversary will ABSTAIN for COMMAND
        assertTrue(result.isRevision, "Expected REVISION but got ${result.consensus}: ${result.reason}")
    }
}
