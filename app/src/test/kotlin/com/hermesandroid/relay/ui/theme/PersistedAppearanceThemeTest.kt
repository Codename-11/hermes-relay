package com.hermesandroid.relay.ui.theme

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.AppearancePreferences
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.data.relayDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h800dp-432dpi")
class PersistedAppearanceThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun externalThemeRootRestoresEveryAppearanceAxis() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking {
            app.relayDataStore.edit {
                it.clear()
                it[AppearancePreferences.themeKey] = "light"
                it[AppearancePreferences.appThemeKey] = AppThemes.DEFAULT_ID
                it[AppearancePreferences.accentKey] = "#D84D91"
                it[AppearancePreferences.shapeKey] = AppearanceShape.SHARP.id
                it[AppearancePreferences.appFontKey] = AppFont.Nunito.id
                it[AppearancePreferences.fontScaleKey] = 1.3f
            }
        }

        var observed: ObservedAppearance? = null
        compose.setContent {
            PersistedHermesRelayTheme {
                val brand = LocalBrand.current
                val shape = LocalAppearanceShapeScale.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val typography = androidx.compose.material3.MaterialTheme.typography
                SideEffect {
                    observed = ObservedAppearance(
                        isDark = brand.isDark,
                        electric = brand.electric,
                        shape = shape.mode,
                        fontScale = density.fontScale,
                        bodyFont = typography.bodyLarge.fontFamily,
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            observed?.let {
                it.shape == AppearanceShape.SHARP && it.electric == Color(0xFFD84D91)
            } == true
        }
        val result = checkNotNull(observed)
        assertFalse(result.isDark)
        assertEquals(Color(0xFFD84D91), result.electric)
        assertEquals(AppearanceShape.SHARP, result.shape)
        assertEquals(1.3f, result.fontScale, 0.01f)
        assertEquals(AppFont.Nunito.fontFamily(), result.bodyFont)
    }

    @Test
    fun externalThemeRootRestoresSavedCustomTheme() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val preset = CustomThemePreset(
            id = "aurora",
            name = "Aurora",
            mode = CustomThemePreset.MODE_DARK,
            backgroundHex = "#0B0B0F",
            surfaceHex = "#141421",
            accentHex = "#5B6CFF",
            textHex = "#F5F6F7",
            shapeId = AppearanceShape.BALANCED.id,
        )
        runBlocking {
            app.relayDataStore.edit {
                it.clear()
                it[AppearancePreferences.customThemesKey] =
                    AppearancePreferences.encodeCustomThemes(listOf(preset))
                it[AppearancePreferences.appThemeKey] = preset.appThemeId
                it[AppearancePreferences.shapeKey] = preset.shapeId
            }
        }

        var observed: ObservedAppearance? = null
        compose.setContent {
            PersistedHermesRelayTheme {
                val brand = LocalBrand.current
                val shape = LocalAppearanceShapeScale.current
                SideEffect {
                    observed = ObservedAppearance(
                        isDark = brand.isDark,
                        electric = brand.electric,
                        shape = shape.mode,
                        fontScale = 1f,
                        bodyFont = null,
                    )
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            observed?.electric == accentColor("#5B6CFF") && observed?.shape == AppearanceShape.BALANCED
        }
        assertEquals(accentColor("#5B6CFF"), observed?.electric)
        assertEquals(AppearanceShape.BALANCED, observed?.shape)
    }

    private data class ObservedAppearance(
        val isDark: Boolean,
        val electric: Color,
        val shape: AppearanceShape,
        val fontScale: Float,
        val bodyFont: androidx.compose.ui.text.font.FontFamily?,
    )
}
