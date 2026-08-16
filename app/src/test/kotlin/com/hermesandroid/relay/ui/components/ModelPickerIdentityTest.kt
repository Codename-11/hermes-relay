package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelPickerIdentityTest {

    @Test
    fun distinctProviderSlugsDoNotCollapseWhenLabelsAndModelsMatch() {
        val direct = option(provider = "deepseek")
        val reseller = option(provider = "opencode-go")

        assertNotEquals(modelPickerOptionKey(direct), modelPickerOptionKey(reseller))
        assertNotEquals(
            modelPickerHeaderKey(ModelPickerGroupIdentity("deepseek", "DeepSeek")),
            modelPickerHeaderKey(ModelPickerGroupIdentity("opencode-go", "DeepSeek")),
        )
    }

    @Test
    fun duplicateExactDomainIdentityStillHasTheSameKey() {
        val first = option(provider = "DeepSeek")
        val duplicate = option(provider = " deepseek ")

        assertEquals(modelPickerOptionKey(first), modelPickerOptionKey(duplicate))
    }

    private fun option(provider: String) = ChatInputPickerOption(
        label = "deepseek-v4-flash",
        value = "deepseek-v4-flash",
        provider = provider,
        group = "DeepSeek",
    )
}
