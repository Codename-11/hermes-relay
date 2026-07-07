package com.hermesandroid.relay.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.hermesandroid.relay.R

/**
 * User-selectable typeface system for the whole app.
 *
 * Each [AppFont] maps to a Compose [FontFamily]. The active choice is persisted
 * in DataStore (ConnectionViewModel.appFont) and threaded into
 * [HermesRelayTheme] as `appFontId`, which rebuilds the Material [Typography]
 * from the selected body family. Because that flows straight through
 * `MaterialTheme(typography = …)`, picking a font re-themes every Text in the
 * app live — no restart, no per-call-site edits. Code/metadata styles keep
 * [FontFamily.Monospace] regardless (see [appTypography]).
 *
 * Bundled families ship as single variable-font TTFs under res/font and are
 * weight-instanced via [FontVariation]; [System] uses the platform sans with no
 * bundle. Only OFL/SIL-licensed fonts are bundled — see the licenses folder.
 */
enum class AppFont(
    val id: String,
    /** Display name in the picker. */
    val label: String,
    /** One-line sample rendered in this font in the picker. */
    val preview: String,
) {
    /** Default — Inter (SIL OFL). Clean, neutral UI sans. */
    Inter("inter", "Inter", "Sphinx of black quartz, judge my vow."),

    /** Nunito (SIL OFL). Rounded, friendlier sans. */
    Nunito("nunito", "Nunito", "Sphinx of black quartz, judge my vow."),

    /** Platform default sans — no bundled font, follows the device. */
    System("system", "System default", "Sphinx of black quartz, judge my vow.");

    /** The Compose [FontFamily] backing this choice, used for all body text. */
    fun fontFamily(): FontFamily = when (this) {
        Inter -> InterFontFamily
        Nunito -> NunitoFontFamily
        System -> FontFamily.SansSerif
    }

    companion object {
        /** Inter is the app default when nothing is persisted. */
        val DEFAULT: AppFont = Inter

        /** Resolve a persisted id back to an [AppFont]; unknown → [DEFAULT]. */
        fun byId(id: String?): AppFont = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

// ── Bundled variable-font families ──────────────────────────────────────────
// One TTF per family carries every weight; each logical weight is a single
// resource font pinned to its `wght` axis value via FontVariation. This is the
// lighter, Compose-idiomatic path vs. bundling a static instance per weight.

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: Int): Font = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter — Regular/Medium/SemiBold/Bold pinned off the variable `wght` axis. */
val InterFontFamily: FontFamily = FontFamily(
    variableFont(R.font.inter_variable, 400),
    variableFont(R.font.inter_variable, 500),
    variableFont(R.font.inter_variable, 600),
    variableFont(R.font.inter_variable, 700),
)

/** Nunito — Regular/Medium/SemiBold/Bold pinned off the variable `wght` axis. */
val NunitoFontFamily: FontFamily = FontFamily(
    variableFont(R.font.nunito_variable, 400),
    variableFont(R.font.nunito_variable, 500),
    variableFont(R.font.nunito_variable, 600),
    variableFont(R.font.nunito_variable, 700),
)
