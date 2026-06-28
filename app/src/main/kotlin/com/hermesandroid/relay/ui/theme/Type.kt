package com.hermesandroid.relay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Build the app [Typography] from a user-selected body [FontFamily].
 *
 * Every text role uses [body] except [Typography.labelSmall], which stays on
 * [FontFamily.Monospace] — it is the app's metadata voice (timestamps, token
 * counts, path badges, agent-name labels) and is intentionally monospaced
 * regardless of the chosen UI font. `HermesRelayTheme` calls this with the
 * active [AppFont]'s family so the whole app re-themes live when the choice
 * changes; see [AppFont].
 */
fun appTypography(body: FontFamily): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

/**
 * Default typography (system sans body) — back-compat for any reader that wants
 * a ready-made [Typography] without resolving an [AppFont]. The live app builds
 * its typography from the selected font via [appTypography] inside
 * `HermesRelayTheme`.
 */
val Typography = appTypography(FontFamily.SansSerif)
