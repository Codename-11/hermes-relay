package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.DEFAULT_PET_TEMPERAMENT
import com.hermesandroid.relay.data.PetTemperament
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PetTemperamentUiTest {
    @Test
    fun `options are shown calm through playful`() {
        assertEquals(
            listOf(PetTemperament.Calm, PetTemperament.Balanced, PetTemperament.Playful),
            petTemperamentOptions.map(PetTemperamentOption::temperament),
        )
    }

    @Test
    fun `every option has distinct label and explanation`() {
        assertEquals(3, petTemperamentOptions.map { it.labelRes }.distinct().size)
        assertEquals(3, petTemperamentOptions.map { it.descriptionRes }.distinct().size)
        petTemperamentOptions.forEach { option ->
            assertNotEquals(option.labelRes, option.descriptionRes)
        }
    }

    @Test
    fun `default option resolves to balanced`() {
        assertEquals(
            PetTemperament.Balanced,
            petTemperamentOption(DEFAULT_PET_TEMPERAMENT).temperament,
        )
    }
}
