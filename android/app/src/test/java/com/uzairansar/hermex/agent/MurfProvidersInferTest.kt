package com.uzairansar.hermex.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class MurfProvidersInferTest {
    @Test
    fun anthropicKeyPrefix() {
        assertEquals(ProviderKind.Anthropic, MurfProviders.inferKindFromKey("sk-ant-api03-xxx"))
    }

    @Test
    fun skKeyDoesNotStickOnAnthropic() {
        assertEquals(
            ProviderKind.DeepSeek,
            MurfProviders.inferKindFromKey("sk-deepseek-xxx", ProviderKind.Anthropic),
        )
        assertEquals(
            ProviderKind.DeepSeek,
            MurfProviders.inferKindFromKey("sk-xxx", ProviderKind.DeepSeek),
        )
    }

    @Test
    fun otherPrefixes() {
        assertEquals(ProviderKind.OpenRouter, MurfProviders.inferKindFromKey("sk-or-v1-x"))
        assertEquals(ProviderKind.XAI, MurfProviders.inferKindFromKey("xai-abc"))
        assertEquals(ProviderKind.Google, MurfProviders.inferKindFromKey("AIzaSyXxx"))
    }
}
