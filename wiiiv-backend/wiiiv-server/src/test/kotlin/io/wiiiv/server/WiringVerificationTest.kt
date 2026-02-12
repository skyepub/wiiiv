package io.wiiiv.server

import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.dacs.SimpleDACS
import io.wiiiv.governor.LlmGovernor
import io.wiiiv.governor.SimpleGovernor
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Wiring Verification Test - Registry 연결 검증
 *
 * WiiivRegistry가 올바르게 LlmGovernor로 연결되었는지 검증
 * (API 키 없는 환경에서도 실행 가능)
 */
class WiringVerificationTest {

    @Test
    fun `Registry governor is LlmGovernor not SimpleGovernor`() {
        val governor = WiiivRegistry.governor
        assertTrue(
            governor is LlmGovernor,
            "Expected LlmGovernor but got ${governor::class.simpleName}"
        )
        assertFalse(
            governor is SimpleGovernor,
            "Governor should not be SimpleGovernor"
        )
    }

    @Test
    fun `Registry DACS is not AlwaysYesDACS`() {
        val dacs = WiiivRegistry.dacs
        assertFalse(
            dacs is io.wiiiv.dacs.AlwaysYesDACS,
            "DACS should not be AlwaysYesDACS"
        )
        // In no-key environment, should be SimpleDACS (Degraded Mode)
        // In key environment, should be HybridDACS
        val key = System.getenv("OPENAI_API_KEY") ?: ""
        if (key.isBlank()) {
            assertTrue(
                dacs is SimpleDACS,
                "Without API key, DACS should be SimpleDACS (Degraded Mode)"
            )
        } else {
            assertTrue(
                dacs is io.wiiiv.dacs.HybridDACS,
                "With API key, DACS should be HybridDACS"
            )
        }
    }

    @Test
    fun `Registry persona info reflects provider type`() {
        val personaInfos = WiiivRegistry.getPersonaInfos()
        assertEquals(3, personaInfos.size)

        val key = System.getenv("OPENAI_API_KEY") ?: ""
        val expectedProvider = if (key.isNotBlank()) "hybrid" else "rule-based"

        personaInfos.forEach { info ->
            assertEquals(
                expectedProvider,
                info.provider,
                "Persona ${info.name} provider should be $expectedProvider"
            )
        }
    }
}
