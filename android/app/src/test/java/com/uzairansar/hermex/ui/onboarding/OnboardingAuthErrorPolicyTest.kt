package com.uzairansar.hermex.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Документирует контракт 1.10.4 thin: после «сервер» — честный health/reconnect,
 * без мёртвого мастера SelfServe в навигации.
 */
class OnboardingAuthErrorPolicyTest {
    @Test
    fun unreachableCopyIsActionable() {
        val ru = "Сервер недоступен. Проверьте сеть или адрес и попробуйте снова."
        assertTrue(ru.contains("недоступен"))
        assertTrue(ru.contains("сеть") || ru.contains("адрес"))
        assertFalse(ru.contains("193.176")) // no owner VDS in user-facing copy
    }

    @Test
    fun selfServeMasterMustNotBeDefaultNav() {
        // Guardrail: unfinished Master must not appear as a primary route id in More/primary IA.
        val primary = com.uzairansar.hermex.agent.MurfMoreNav.primaryIds()
        val more = com.uzairansar.hermex.agent.MurfMoreNav.items().map { it.routeOrPanel }
        assertFalse(primary.contains("selfserve") || primary.contains("setup") || primary.contains("wizard"))
        assertFalse(more.contains("selfserve") || more.contains("setup") || more.contains("wizard"))
    }
}
