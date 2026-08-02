package com.hermesandroid.relay.ui.screens

import androidx.annotation.StringRes
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.PetTemperament

internal data class PetTemperamentOption(
    val temperament: PetTemperament,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
)

internal val petTemperamentOptions: List<PetTemperamentOption> = listOf(
    PetTemperamentOption(
        temperament = PetTemperament.Calm,
        labelRes = R.string.appearance_pet_temperament_calm,
        descriptionRes = R.string.appearance_pet_temperament_calm_desc,
    ),
    PetTemperamentOption(
        temperament = PetTemperament.Balanced,
        labelRes = R.string.appearance_pet_temperament_balanced,
        descriptionRes = R.string.appearance_pet_temperament_balanced_desc,
    ),
    PetTemperamentOption(
        temperament = PetTemperament.Playful,
        labelRes = R.string.appearance_pet_temperament_playful,
        descriptionRes = R.string.appearance_pet_temperament_playful_desc,
    ),
)

internal fun petTemperamentOption(temperament: PetTemperament): PetTemperamentOption =
    petTemperamentOptions.first { it.temperament == temperament }
