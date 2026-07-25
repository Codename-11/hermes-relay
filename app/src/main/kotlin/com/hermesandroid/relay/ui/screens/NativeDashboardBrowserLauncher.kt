package com.hermesandroid.relay.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

internal fun launchNativeDashboardAuthorization(
    context: Context,
    authorizationUrl: String,
) {
    val uri = Uri.parse(authorizationUrl)
    val customTab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .build()
        .also {
            if (context !is Activity) {
                it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    try {
        customTab.launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
