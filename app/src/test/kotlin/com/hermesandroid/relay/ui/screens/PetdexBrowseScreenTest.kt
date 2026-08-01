package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.petdex.PetdexPet
import org.junit.Assert.assertEquals
import org.junit.Test

class PetdexBrowseScreenTest {
    private val pets = listOf(
        pet(slug = "tiny-crt", name = "Tiny CRT", creator = "Ada"),
        pet(slug = "boba-cat", name = "Boba", creator = "Lin"),
    )

    @Test
    fun blankQueryPreservesCatalogOrder() {
        assertEquals(pets, filterPetdexPets(pets, "  "))
    }

    @Test
    fun queryMatchesNameSlugOrCreatorIgnoringCase() {
        assertEquals(listOf(pets[0]), filterPetdexPets(pets, "crt"))
        assertEquals(listOf(pets[1]), filterPetdexPets(pets, "BOBA-CAT"))
        assertEquals(listOf(pets[0]), filterPetdexPets(pets, "ada"))
    }

    @Test
    fun unmatchedQueryReturnsEmptyList() {
        assertEquals(emptyList<PetdexPet>(), filterPetdexPets(pets, "dog"))
    }

    private fun pet(slug: String, name: String, creator: String) = PetdexPet(
        slug = slug,
        displayName = name,
        kind = "pet",
        submittedBy = creator,
        spritesheetUrl = "https://assets.petdex.dev/$slug.webp",
        petJsonUrl = "https://assets.petdex.dev/$slug.json",
    )
}
