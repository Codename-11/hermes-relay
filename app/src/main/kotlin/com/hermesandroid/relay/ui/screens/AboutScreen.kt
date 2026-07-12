@file:Suppress("LocalContextGetResourceValueCall")

package com.hermesandroid.relay.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.hermesandroid.relay.ui.theme.LocalBrand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.update.UpdateCheckResult
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.UpdateViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Dedicated About screen. Hosts version info, build metadata, credits,
 * license, GitHub + docs links, and the tap-7x version-number unlock for
 * Developer options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onUnlockDeveloperOptions: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDarkTheme = LocalBrand.current.isDark

    // Pre-resolved Toast messages (Toast is not a composable scope)
    val devUnlockedMsg = stringResource(R.string.about_dev_options_unlocked)
    val updateCopiedMsg = stringResource(R.string.about_update_copied)
    val emDash = stringResource(R.string.about_em_dash)

    // Tap-7x unlock state (mirrors the old SettingsScreen locals)
    val devOptionsUnlocked by FeatureFlags.devOptionsUnlocked(context).collectAsState(initial = FeatureFlags.isDevBuild)
    var versionTapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    var showWhatsNew by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.about_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // About section
            Text(
                text = stringResource(R.string.about_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Logo
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1A1A2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = stringResource(R.string.about_app_name),
                            modifier = Modifier.size(80.dp)
                        )
                    }

                    Text(
                        text = stringResource(R.string.about_app_name),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.about_app_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Version info (dynamic)
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: emDash
                        } catch (_: Exception) { emDash }
                    }
                    val versionCode = remember {
                        try {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
                        } catch (_: Exception) { emDash }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (devOptionsUnlocked) return@clickable
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime > 2000) {
                                    versionTapCount = 1
                                } else {
                                    versionTapCount++
                                }
                                lastTapTime = now
                                val remaining = 7 - versionTapCount
                                when {
                                    remaining <= 0 -> {
                                        scope.launch { FeatureFlags.unlockDevOptions(context) }
                                        Toast.makeText(context, devUnlockedMsg, Toast.LENGTH_SHORT).show()
                                        versionTapCount = 0
                                        onUnlockDeveloperOptions()
                                    }
                                    remaining <= 3 -> {
                                        val tapsMsg = context.getString(R.string.about_taps_to_unlock, remaining)
                                        Toast.makeText(context, tapsMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.about_version),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$versionName ($versionCode)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // === PHASE3-flavor-split: build flavor badge ===
                    // Surfaces which release track the user is running. Tier 3/4/6
                    // Bridge surfaces differ between googlePlay and sideload, so it
                    // helps bug reports to see the active flavor right next to the
                    // version. Small, unobtrusive — shares the same row style as
                    // the Version label above.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.about_track),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = BuildFlavor.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // === END PHASE3-flavor-split ===

                    // Connected relay's plugin version + a soft "newer release
                    // available" nudge — both flavors, since the relay is
                    // server-side regardless of app track. Source:
                    // ConnectionViewModel.relayUpdateInfo, refreshed on each
                    // auth.ok from the relay's /relay/update-check (the app and
                    // relay version *independently* — this is the relay's own
                    // track, not an app-vs-relay numeric compare). Null until a
                    // paired relay answers, so it simply doesn't render offline.
                    val relayUpdate by connectionViewModel.relayUpdateInfo.collectAsState()
                    relayUpdate?.let { ru ->
                        HorizontalDivider()
                        val clipboard = LocalClipboardManager.current
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.about_relay),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val subtitle = when {
                                    ru.updateAvailable && !ru.latest.isNullOrBlank() ->
                                        stringResource(R.string.about_update_available, ru.current, ru.latest)
                                    ru.current.isNotBlank() -> stringResource(R.string.about_up_to_date, ru.current)
                                    else -> stringResource(R.string.about_connected)
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ru.updateAvailable) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (ru.updateAvailable && !ru.updateCommand.isNullOrBlank()) {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(ru.updateCommand))
                                    Toast.makeText(
                                        context,
                                        updateCopiedMsg,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }) {
                                    Text(stringResource(R.string.about_copy_fix))
                                }
                            }
                        }
                        if (ru.updateAvailable && !ru.updateCommand.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.about_update_command_body, ru.updateCommand),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // Sideload-only: in-app update check. googlePlay builds
                    // get updates through the Play Store, so we hide this
                    // row on that track. UpdateViewModel also short-circuits
                    // internally for belt-and-braces safety.
                    if (BuildFlavor.isSideload) {
                        HorizontalDivider()
                        val updateVm: UpdateViewModel = viewModel()
                        val state by updateVm.uiState.collectAsState()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.about_updates),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val subtitle = when (val s = state) {
                                    UpdateCheckResult.Idle -> stringResource(R.string.about_updates_tap)
                                    UpdateCheckResult.Checking -> stringResource(R.string.about_updates_checking)
                                    UpdateCheckResult.UpToDate -> stringResource(R.string.about_updates_latest)
                                    is UpdateCheckResult.Available -> stringResource(R.string.about_updates_available, s.update.latestVersion)
                                    is UpdateCheckResult.Error -> stringResource(R.string.about_updates_failed, s.message)
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                enabled = state !is UpdateCheckResult.Checking,
                                onClick = {
                                    when (val s = state) {
                                        is UpdateCheckResult.Available -> {
                                            val target = s.update.apkUrl ?: s.update.releasePageUrl
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                            context.startActivity(intent)
                                        }
                                        else -> updateVm.check()
                                    }
                                },
                            ) {
                                Text(
                                    text = when (state) {
                                        is UpdateCheckResult.Available -> stringResource(R.string.about_download)
                                        UpdateCheckResult.Checking -> "…"
                                        else -> stringResource(R.string.about_check)
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // Links
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Codename-11/hermes-relay"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.about_github))
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://codename-11.github.io/hermes-relay/"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.about_app_docs))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(stringResource(R.string.about_hermes_docs))
                        }
                    }

                    // Privacy policy link
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Codename-11/hermes-relay/blob/main/docs/privacy.md"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(stringResource(R.string.about_privacy_policy))
                    }

                    // What's New
                    TextButton(onClick = { showWhatsNew = true }) {
                        Text(stringResource(R.string.about_whats_new))
                    }

                    // Credits
                    Text(
                        text = stringResource(R.string.about_credits),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // What's New dialog
    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }
}
