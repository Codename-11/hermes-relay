package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.ui.theme.LocalBrand

/** Optional and specialized features kept off the primary Settings surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    supervisedPolicy: SupervisedModePolicy,
    onNavigateToSupervisedControls: () -> Unit,
    onBack: () -> Unit,
) {
    val isDarkTheme = LocalBrand.current.isDark

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_advanced)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_advanced_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingsCategoryRow(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.settings_supervised_mode),
                subtitle = when {
                    supervisedPolicy.isActive -> stringResource(
                        R.string.settings_supervised_on_profile,
                        supervisedPolicy.pinnedProfileName.orEmpty(),
                    )
                    supervisedPolicy.isConfigured -> stringResource(
                        R.string.settings_supervised_ready_profile,
                        supervisedPolicy.pinnedProfileName.orEmpty(),
                    )
                    else -> stringResource(R.string.settings_supervised_desc)
                },
                badge = supervisedPolicy.takeIf { it.isActive }?.let {
                    SettingsStatusPillModel(
                        label = stringResource(R.string.settings_supervised_on),
                        tone = SettingsStatusTone.Good,
                    )
                },
                onClick = onNavigateToSupervisedControls,
                isDarkTheme = isDarkTheme,
                petPerchKey = null,
            )
        }
    }
}
