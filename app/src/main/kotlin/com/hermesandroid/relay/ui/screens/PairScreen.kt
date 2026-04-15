package com.hermesandroid.relay.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hermesandroid.relay.ui.components.ConnectionWizard
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Full-screen pairing route. Wraps [ConnectionWizard] in a real Scaffold so
 * the chooser tiles, manual-entry forms, and camera viewport all get the
 * actual window — not a Compose Dialog that leaked the Settings cards
 * underneath. Reached via Settings → Connection → Pair (or any "Re-pair"
 * button), and pops back to wherever it came from on complete or cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair with your server") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ConnectionWizard(
                connectionViewModel = connectionViewModel,
                onComplete = {
                    Toast.makeText(context, "Paired successfully", Toast.LENGTH_SHORT).show()
                    onComplete()
                },
                onCancel = onCancel,
                showSkip = false,
            )
        }
    }
}
