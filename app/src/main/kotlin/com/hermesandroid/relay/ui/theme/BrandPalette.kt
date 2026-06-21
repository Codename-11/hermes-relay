package com.hermesandroid.relay.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Theme-scoped brand token bundle.
 *
 * Historically the app carried a single dark-only [RelayRefresh] object of
 * `val Color(...)` constants, which every screen referenced directly — so the
 * Material light scheme was bypassed and chat (and friends) were effectively
 * hardcoded dark. [BrandPalette] makes those same tokens a *value* that the
 * active [AppTheme] supplies, so the brand chrome follows the chosen theme and
 * light/dark mode. [RelayRefresh] now reads its tokens from the active palette,
 * which is how 150+ existing call sites become theme-reactive without edits.
 *
 * New code should prefer [LocalBrand] (the composition-correct accessor);
 * `RelayRefresh.X` remains as the legacy façade over the same palette.
 *
 * Every token carries a mode-appropriate value, so a palette is self-contained:
 * surfaces, body text, hairlines, and accents are all authored for the palette's
 * own light/dark intent. The Material [ColorScheme] is derived from the palette
 * via [toColorScheme] rather than authored separately, keeping the two in sync.
 */
@Immutable
data class BrandPalette(
    /** Whether this palette paints light glyphs on dark surfaces. Drives [toColorScheme]. */
    val isDark: Boolean,
    // ── Foreground / neutrals ───────────────────────────────────────────
    /** Primary readable body text on [background]. */
    val ink: Color,
    /** Foreground used on elevated panels + as on-accent text (cream in dark themes). */
    val paper: Color,
    /** Secondary / de-emphasized text. */
    val muted: Color,
    /** Tertiary / disabled text. */
    val dim: Color,
    // ── Surfaces ────────────────────────────────────────────────────────
    /** Page background. */
    val background: Color,
    /** Lowest container step (deepest in dark, brightest white in light). */
    val surfaceLowest: Color,
    /** Low container step. */
    val surfaceLow: Color,
    /** Elevated panel base (Material surfaceContainer). */
    val navy: Color,
    /** Elevated panel (Material surfaceContainerHigh / [relayPanel] default). */
    val navy2: Color,
    /** Highest elevated panel (Material surfaceContainerHighest). */
    val navy3: Color,
    // ── Accents ─────────────────────────────────────────────────────────
    /** Soft brand accent — icon tints, accent labels. */
    val relay: Color,
    /** Secondary accent. */
    val purple: Color,
    /** Strong brand accent — primary filled chips. */
    val electric: Color,
    /** Softened [electric] for large filled surfaces. */
    val electricMuted: Color,
    /** Tertiary accent. */
    val cyan: Color,
    // ── Semantic ────────────────────────────────────────────────────────
    val green: Color,
    val amber: Color,
    val danger: Color,
    // ── Hairlines ───────────────────────────────────────────────────────
    val line: Color,
    val lineStrong: Color,
)

/** Active palette accessor for new, composition-correct code. */
val LocalBrand = staticCompositionLocalOf { BrandPalettes.HermesDark }

private fun Color.lighten(f: Float): Color = lerp(this, Color.White, f)
private fun Color.darken(f: Float): Color = lerp(this, Color.Black, f)

/**
 * Derive a complete Material 3 [ColorScheme] from the palette. Dark and light
 * palettes use distinct mappings; the "text on a deep accent chip" slots use
 * explicit on-colors so they stay readable regardless of mode.
 */
fun BrandPalette.toColorScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = relay,
        onPrimary = background,
        primaryContainer = electric,
        onPrimaryContainer = paper,
        secondary = purple,
        onSecondary = paper,
        secondaryContainer = navy3,
        onSecondaryContainer = paper,
        tertiary = cyan,
        onTertiary = background,
        tertiaryContainer = purple.copy(alpha = 0.42f),
        onTertiaryContainer = paper,
        background = background,
        onBackground = ink,
        surface = background,
        onSurface = ink,
        surfaceVariant = navy2,
        onSurfaceVariant = muted,
        surfaceContainerLowest = surfaceLowest,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = navy,
        surfaceContainerHigh = navy2,
        surfaceContainerHighest = navy3,
        error = danger,
        onError = background,
        errorContainer = danger.copy(alpha = 0.18f),
        onErrorContainer = paper,
        outline = lineStrong,
        outlineVariant = line,
    )
} else {
    lightColorScheme(
        primary = electric,
        onPrimary = Color.White,
        primaryContainer = relay.copy(alpha = 0.22f),
        onPrimaryContainer = electric.darken(0.32f),
        secondary = purple,
        onSecondary = Color.White,
        secondaryContainer = purple.copy(alpha = 0.16f),
        onSecondaryContainer = purple.darken(0.38f),
        tertiary = cyan,
        onTertiary = Color.White,
        tertiaryContainer = cyan.copy(alpha = 0.16f),
        onTertiaryContainer = cyan.darken(0.42f),
        background = background,
        onBackground = ink,
        surface = background,
        onSurface = ink,
        surfaceVariant = navy2,
        onSurfaceVariant = muted,
        surfaceContainerLowest = surfaceLowest,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = navy,
        surfaceContainerHigh = navy2,
        surfaceContainerHighest = navy3,
        error = danger,
        onError = Color.White,
        errorContainer = danger.copy(alpha = 0.14f),
        onErrorContainer = danger.darken(0.42f),
        outline = dim,
        outlineVariant = line,
    )
}

/**
 * Helper for the Nous-derived dark themes: fill the surface ramp + hairlines
 * from a couple of seed colors so each theme only specifies what's distinctive.
 * Hand-authored palettes (Hermes Relay) bypass this for exact control.
 */
private fun darkBrand(
    background: Color,
    panel: Color,
    relay: Color,
    purple: Color,
    electric: Color,
    cyan: Color,
    ink: Color = Color(0xFFF3F2F7),
    paper: Color = Color(0xFFF7F3EA),
    muted: Color = Color(0xFFA7A4B7),
    dim: Color = Color(0xFF68647D),
    electricMuted: Color = electric.lighten(0.22f),
    green: Color = Color(0xFF58D36F),
    amber: Color = Color(0xFFF2B14B),
    danger: Color = Color(0xFFFF6B78),
): BrandPalette = BrandPalette(
    isDark = true,
    ink = ink,
    paper = paper,
    muted = muted,
    dim = dim,
    background = background,
    surfaceLowest = background.darken(0.30f),
    surfaceLow = background.lighten(0.04f),
    navy = panel.darken(0.16f),
    navy2 = panel,
    navy3 = panel.lighten(0.12f),
    relay = relay,
    purple = purple,
    electric = electric,
    electricMuted = electricMuted,
    cyan = cyan,
    green = green,
    amber = amber,
    danger = danger,
    line = ink.copy(alpha = 0.14f),
    lineStrong = ink.copy(alpha = 0.28f),
)

/**
 * Built-in brand palettes. The Hermes Relay pair preserves the shipped brand
 * look; the rest are ports of the canonical Nous Hermes dashboard themes
 * (upstream `web/src/themes/presets.ts`), re-derived for native Material 3.
 */
object BrandPalettes {

    /** Shipped dark brand — preserved byte-for-byte from the original RelayRefresh. */
    val HermesDark = BrandPalette(
        isDark = true,
        ink = Color(0xFFF7F6F0),
        paper = Color(0xFFF7F3EA),
        muted = Color(0xFFA7A4B7),
        dim = Color(0xFF68647D),
        background = Color(0xFF08090D),
        surfaceLowest = Color(0xFF05060A),
        surfaceLow = Color(0xFF0B0C12),
        navy = Color(0xFF121426),
        navy2 = Color(0xFF191B31),
        navy3 = Color(0xFF22243C),
        relay = Color(0xFFAEBFFF),
        purple = Color(0xFF8C5CFF),
        electric = Color(0xFF0E18D6),
        electricMuted = Color(0xFF4F5BD5),
        cyan = Color(0xFF6BDCFF),
        green = Color(0xFF58D36F),
        amber = Color(0xFFF2B14B),
        danger = Color(0xFFFF6B78),
        line = Color(0x24F7F6F0),
        lineStrong = Color(0x47F7F6F0),
    )

    /** Clean light variant of the Hermes Relay brand — fully inverted surfaces + text. */
    val HermesLight = BrandPalette(
        isDark = false,
        ink = Color(0xFF171826),
        paper = Color(0xFF23202E),
        muted = Color(0xFF5A576C),
        dim = Color(0xFF83809A),
        background = Color(0xFFF6F4FB),
        surfaceLowest = Color(0xFFFFFFFF),
        surfaceLow = Color(0xFFF0EEF7),
        navy = Color(0xFFECE9F4),
        navy2 = Color(0xFFE4E1EE),
        navy3 = Color(0xFFD9D5E6),
        relay = Color(0xFF3D4BC9),
        purple = Color(0xFF6A3FE0),
        electric = Color(0xFF1A24D6),
        electricMuted = Color(0xFF4F5BD5),
        cyan = Color(0xFF0E84B8),
        green = Color(0xFF2E9E48),
        amber = Color(0xFFB5791E),
        danger = Color(0xFFC7283A),
        line = Color(0xFF171826).copy(alpha = 0.10f),
        lineStrong = Color(0xFF171826).copy(alpha = 0.22f),
    )

    /** Hermes Teal — the canonical Hermes look: deep teal + warm cream/amber. */
    val HermesTeal = darkBrand(
        background = Color(0xFF04201F),
        panel = Color(0xFF0C2B2A),
        relay = Color(0xFF86E5D9),
        purple = Color(0xFFF0B24E),
        electric = Color(0xFF0FA295),
        cyan = Color(0xFF54CFC4),
        ink = Color(0xFFEAF5F3),
        paper = Color(0xFFFFE6CB),
        amber = Color(0xFFF5B450),
    )

    /** Nous Blue — the light Hermes look: vivid Nous-blue on a cream canvas. */
    val NousBlue = BrandPalette(
        isDark = false,
        ink = Color(0xFF0A1F3C),
        paper = Color(0xFF0A1F3C),
        muted = Color(0xFF4A6079),
        dim = Color(0xFF7089A3),
        background = Color(0xFFE8F2FD),
        surfaceLowest = Color(0xFFFFFFFF),
        surfaceLow = Color(0xFFF2F7FE),
        navy = Color(0xFFDDEAFB),
        navy2 = Color(0xFFCFE0F7),
        navy3 = Color(0xFFBFD4F2),
        relay = Color(0xFF0053FD),
        purple = Color(0xFF3B6FE0),
        electric = Color(0xFF0053FD),
        electricMuted = Color(0xFF3D74E8),
        cyan = Color(0xFF0091C2),
        green = Color(0xFF1B8E4B),
        amber = Color(0xFFB07A12),
        danger = Color(0xFFD2342A),
        line = Color(0xFF0A1F3C).copy(alpha = 0.10f),
        lineStrong = Color(0xFF0A1F3C).copy(alpha = 0.22f),
    )

    /** Midnight — deep blue-violet with cool accents. */
    val Midnight = darkBrand(
        background = Color(0xFF0A0A1F),
        panel = Color(0xFF15162E),
        relay = Color(0xFFC9BBFF),
        purple = Color(0xFFA78BFA),
        electric = Color(0xFF7C5CFF),
        cyan = Color(0xFF8AB4FF),
        ink = Color(0xFFEDEAFF),
        paper = Color(0xFFD4C8FF),
    )

    /** Ember — warm crimson + bronze, forge vibes. */
    val Ember = darkBrand(
        background = Color(0xFF1A0A06),
        panel = Color(0xFF2A130C),
        relay = Color(0xFFFFB07A),
        purple = Color(0xFFC2410C),
        electric = Color(0xFFE5562A),
        cyan = Color(0xFFF0A060),
        ink = Color(0xFFF8E6DA),
        paper = Color(0xFFFFD8B0),
        amber = Color(0xFFF97316),
        danger = Color(0xFFC92D0F),
    )

    /** Mono — clean grayscale, minimal and focused. */
    val Mono = darkBrand(
        background = Color(0xFF0E0E0E),
        panel = Color(0xFF1C1C1C),
        relay = Color(0xFFE0E0E0),
        purple = Color(0xFF9A9A9A),
        electric = Color(0xFFBABABA),
        cyan = Color(0xFFC8C8C8),
        ink = Color(0xFFEAEAEA),
        paper = Color(0xFFEAEAEA),
        muted = Color(0xFF9A9A9A),
        dim = Color(0xFF6A6A6A),
        green = Color(0xFFB8B8B8),
        amber = Color(0xFFD0D0D0),
        danger = Color(0xFFE88A8A),
    )

    /** Cyberpunk — neon green on near-black, matrix terminal. */
    val Cyberpunk = darkBrand(
        background = Color(0xFF040608),
        panel = Color(0xFF0A140E),
        relay = Color(0xFF9BFFCF),
        purple = Color(0xFF00D9C0),
        electric = Color(0xFF00FF88),
        cyan = Color(0xFF54FFE0),
        ink = Color(0xFFD6FFE9),
        paper = Color(0xFF9BFFCF),
        green = Color(0xFF00FF88),
        amber = Color(0xFFFFD700),
        danger = Color(0xFFFF0055),
    )

    /** Rosé — soft pink + warm ivory, easy on the eyes. */
    val Rose = darkBrand(
        background = Color(0xFF1A0F15),
        panel = Color(0xFF2A1822),
        relay = Color(0xFFFFB8CE),
        purple = Color(0xFFC77DFF),
        electric = Color(0xFFE86A9C),
        cyan = Color(0xFFF4A8C8),
        ink = Color(0xFFFAE6EE),
        paper = Color(0xFFFFD4E1),
    )
}

/** Whether a theme honors a separate light/dark mode toggle, or is a fixed look. */
enum class ThemeMode { DARK_ONLY, LIGHT_ONLY, BOTH }

/**
 * A selectable app theme. Themes that support [ThemeMode.BOTH] carry a distinct
 * [darkPalette] and [lightPalette] and honor the Auto/Light/Dark mode toggle;
 * fixed-mode themes are their own complete look (matching how the Nous dashboard
 * ships themes — light mode lives as the separate Nous Blue theme).
 */
@Immutable
data class AppTheme(
    val id: String,
    val label: String,
    val description: String,
    val mode: ThemeMode,
    val darkPalette: BrandPalette,
    val lightPalette: BrandPalette,
    /** Three representative colors for the picker swatch (bg, accent, fg). */
    val swatch: List<Color>,
    /** Optional sphere skin this theme prefers when the user hasn't overridden it. */
    val defaultSphereSkinId: String? = null,
) {
    /** Resolve whether to paint dark, given the user's mode pref + system setting. */
    fun resolveDark(modePref: String, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.DARK_ONLY -> true
        ThemeMode.LIGHT_ONLY -> false
        ThemeMode.BOTH -> when (modePref) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }
    }

    fun paletteFor(dark: Boolean): BrandPalette = if (dark) darkPalette else lightPalette
}

/** Registry of built-in app themes, in picker order. */
object AppThemes {
    const val DEFAULT_ID = "hermes-relay"

    val HermesRelay = AppTheme(
        id = DEFAULT_ID,
        label = "Hermes Relay",
        description = "The signature electric-blue brand — follows light/dark",
        mode = ThemeMode.BOTH,
        darkPalette = BrandPalettes.HermesDark,
        lightPalette = BrandPalettes.HermesLight,
        swatch = listOf(Color(0xFF08090D), Color(0xFF0E18D6), Color(0xFFAEBFFF)),
    )

    val HermesTeal = AppTheme(
        id = "hermes-teal",
        label = "Hermes Teal",
        description = "Classic dark teal — the canonical Hermes look",
        mode = ThemeMode.DARK_ONLY,
        darkPalette = BrandPalettes.HermesTeal,
        lightPalette = BrandPalettes.HermesTeal,
        swatch = listOf(Color(0xFF04201F), Color(0xFF0FA295), Color(0xFFFFE6CB)),
    )

    val NousBlue = AppTheme(
        id = "nous-blue",
        label = "Nous Blue",
        description = "Light mode — vivid Nous-blue accents on a cream canvas",
        mode = ThemeMode.LIGHT_ONLY,
        darkPalette = BrandPalettes.NousBlue,
        lightPalette = BrandPalettes.NousBlue,
        swatch = listOf(Color(0xFFE8F2FD), Color(0xFF0053FD), Color(0xFF0A1F3C)),
    )

    val Midnight = AppTheme(
        id = "midnight",
        label = "Midnight",
        description = "Deep blue-violet with cool accents",
        mode = ThemeMode.DARK_ONLY,
        darkPalette = BrandPalettes.Midnight,
        lightPalette = BrandPalettes.Midnight,
        swatch = listOf(Color(0xFF0A0A1F), Color(0xFF7C5CFF), Color(0xFFD4C8FF)),
    )

    val Ember = AppTheme(
        id = "ember",
        label = "Ember",
        description = "Warm crimson and bronze — forge vibes",
        mode = ThemeMode.DARK_ONLY,
        darkPalette = BrandPalettes.Ember,
        lightPalette = BrandPalettes.Ember,
        swatch = listOf(Color(0xFF1A0A06), Color(0xFFE5562A), Color(0xFFFFD8B0)),
    )

    val Mono = AppTheme(
        id = "mono",
        label = "Mono",
        description = "Clean grayscale — minimal and focused",
        mode = ThemeMode.DARK_ONLY,
        darkPalette = BrandPalettes.Mono,
        lightPalette = BrandPalettes.Mono,
        swatch = listOf(Color(0xFF0E0E0E), Color(0xFFBABABA), Color(0xFFEAEAEA)),
    )

    val Cyberpunk = AppTheme(
        id = "cyberpunk",
        label = "Cyberpunk",
        description = "Neon green on black — matrix terminal",
        mode = ThemeMode.DARK_ONLY,
        darkPalette = BrandPalettes.Cyberpunk,
        lightPalette = BrandPalettes.Cyberpunk,
        swatch = listOf(Color(0xFF040608), Color(0xFF00FF88), Color(0xFF9BFFCF)),
    )

    val Rose = AppTheme(
        id = "rose",
        label = "Rosé",
        description = "Soft pink and warm ivory — easy on the eyes",
        mode = ThemeMode.DARK_ONLY,
        darkPalette = BrandPalettes.Rose,
        lightPalette = BrandPalettes.Rose,
        swatch = listOf(Color(0xFF1A0F15), Color(0xFFE86A9C), Color(0xFFFFD4E1)),
    )

    /** All themes in picker order: brand first, then the Nous baselines. */
    val ALL: List<AppTheme> = listOf(
        HermesRelay,
        HermesTeal,
        NousBlue,
        Midnight,
        Ember,
        Mono,
        Cyberpunk,
        Rose,
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String?): AppTheme = byId[id] ?: HermesRelay
}
