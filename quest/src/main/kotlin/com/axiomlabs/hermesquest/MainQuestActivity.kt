package com.axiomlabs.hermesquest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiomlabs.hermesrelay.core.pairing.QuestSessionStore
import com.axiomlabs.hermesrelay.core.terminal.QuestAuthState
import com.axiomlabs.hermesrelay.core.terminal.QuestTerminalController
import com.axiomlabs.hermesrelay.core.terminal.SpecialKey
import com.axiomlabs.hermesrelay.core.transport.ConnectionState
import com.axiomlabs.hermesrelay.ui.scanner.QuestQrScanner
import com.axiomlabs.hermesrelay.ui.sphere.MorphingSphere
import com.axiomlabs.hermesrelay.ui.sphere.SphereState
import com.axiomlabs.hermesrelay.ui.terminal.ExtraKeysToolbar
import com.axiomlabs.hermesrelay.ui.terminal.RelayTerminalWebView
import androidx.core.net.toUri
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.composePanel
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.mruk.MRUKFeature
import com.meta.spatial.mruk.MRUKLoadDeviceResult
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.GLXFInfo
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.SpatialActivityManager
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.addName
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainQuestActivity : AppSystemActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sceneRoot: Entity? = null
    private var fallbackPanel: Entity? = null
    private lateinit var mrukFeature: MRUKFeature

    override fun registerFeatures(): List<SpatialFeature> {
        mrukFeature = MRUKFeature(this, systemManager)
        val features = mutableListOf<SpatialFeature>(
            VRFeature(this),
            ComposeFeature(),
            mrukFeature,
        )
        if (BuildConfig.DEBUG) {
            features.add(CastInputForwardFeature(this))
            features.add(HotReloadFeature(this))
            features.add(OVRMetricsFeature(this, OVRMetricsDataModel { numberOfMeshes() }))
            features.add(DataModelInspectorFeature(spatial, componentManager))
        }
        return features
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        NetworkedAssetLoader.init(
            File(applicationContext.cacheDir.canonicalPath),
            OkHttpAssetFetcher(),
        )
        setMixedRealityEnabled(true)
        loadRoomSceneIfAllowed()
        loadSpatialScene()
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(5.8f, 6.4f, 6.0f),
            sunDirection = -Vector3(0.8f, 2.4f, -1.6f),
            environmentIntensity = 0.18f,
        )
        scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)
        setMixedRealityEnabled(true)
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            PanelRegistration(R.id.quest_terminal_panel) {
                config {
                    themeResourceId = R.style.PanelAppThemeTransparent
                    layoutWidthInDp = 1720f
                    layoutHeightInDp = 980f
                    layerConfig = LayerConfig()
                    enableTransparent = true
                    includeGlass = false
                }
                composePanel {
                    setContent { HermesQuestApp() }
                }
            }
        )
    }

    fun setMixedRealityEnabled(enabled: Boolean) {
        runCatching {
            systemManager.findSystem<LocomotionSystem>().enableLocomotion(!enabled)
        }.onFailure { Log.w(TAG, "Locomotion toggle failed", it) }
        runCatching {
            scene.enablePassthrough(enabled)
            scene.enableEnvironmentDepth(enabled)
        }.onFailure { Log.w(TAG, "Passthrough toggle failed", it) }
    }

    override fun onSpatialShutdown() {
        activityScope.cancel()
        super.onSpatialShutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_USE_SCENE && permissions.firstOrNull() == PERMISSION_USE_SCENE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                loadRoomSceneFromDevice()
            } else {
                Log.w(TAG, "Scene permission denied; passthrough panel will continue without room mesh")
            }
        }
    }

    private fun loadSpatialScene(): Job {
        sceneRoot = Entity.create()
        return activityScope.launch {
            runCatching {
                glXFManager.inflateGLXF(
                    "apk:///scenes/Composition.glxf".toUri(),
                    rootEntity = sceneRoot!!,
                    keyName = GLXF_SCENE,
                    onLoaded = { _: GLXFInfo -> setMixedRealityEnabled(true) },
                )
            }.onFailure {
                Log.w(TAG, "Spatial scene load failed; using programmatic panel", it)
                createFallbackPanel()
            }
        }
    }

    private fun createFallbackPanel() {
        if (fallbackPanel != null) return
        fallbackPanel = Entity.createPanelEntity(
            R.id.quest_terminal_panel,
            Transform(
                Pose(
                    Vector3(0f, 1.22f, -1.85f),
                    Quaternion(3.1415927f, 0f, 3.1415927f),
                ),
            ),
            Grabbable(),
            PanelDimensions(Vector2(1.72f, 0.98f)),
        ).addName("HermesQuestTerminalPanel")
    }

    private fun loadRoomSceneIfAllowed() {
        if (checkSelfPermission(PERMISSION_USE_SCENE) == PackageManager.PERMISSION_GRANTED) {
            loadRoomSceneFromDevice()
        } else {
            requestPermissions(arrayOf(PERMISSION_USE_SCENE), REQUEST_CODE_USE_SCENE)
        }
    }

    private fun loadRoomSceneFromDevice() {
        runCatching {
            mrukFeature.loadSceneFromDevice().whenComplete { result: MRUKLoadDeviceResult, error ->
                if (error != null || result != MRUKLoadDeviceResult.SUCCESS) {
                    Log.w(TAG, "Room scene load result=$result", error)
                } else {
                    Log.d(TAG, "Room scene loaded")
                }
            }
        }.onFailure { Log.w(TAG, "Room scene load failed", it) }
    }

    companion object {
        private const val TAG = "HermesQuest"
        private const val GLXF_SCENE = "HERMES_QUEST_SCENE"
        private const val PERMISSION_USE_SCENE = "com.oculus.permission.USE_SCENE"
        private const val REQUEST_CODE_USE_SCENE = 42
    }
}

@Composable
private fun HermesQuestApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { QuestSessionStore(context.applicationContext) }
    val controller = remember { QuestTerminalController(store, scope) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showPairing by remember { mutableStateOf(store.sessionToken == null) }

    LaunchedEffect(controller) {
        if (store.sessionToken != null) controller.connectStored()
    }
    LaunchedEffect(controller) {
        controller.eventMessages.collect { snackbarHostState.showSnackbar(it) }
    }
    DisposableEffect(controller) {
        onDispose { controller.disconnect() }
    }

    HermesQuestTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color(0xFF07110E),
        ) { padding ->
            AnimatedContent(
                targetState = showPairing,
                label = "quest-shell",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { pairing ->
                if (pairing) {
                    PairingCockpit(
                        controller = controller,
                        store = store,
                        onDone = { showPairing = false },
                    )
                } else {
                    TerminalCockpit(
                        controller = controller,
                        onPairing = { showPairing = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingCockpit(
    controller: QuestTerminalController,
    store: QuestSessionStore,
    onDone: () -> Unit,
) {
    var relayUrl by remember { mutableStateOf(store.relayUrl ?: "ws://") }
    var code by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var scanOpen by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val authState by controller.authState.collectAsStateWithLifecycle()
    val connectionState by controller.connectionState.collectAsStateWithLifecycle()

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scanOpen = granted
        if (!granted) cameraError = "Camera denied"
    }

    LaunchedEffect(authState) {
        if (authState is QuestAuthState.Paired) onDone()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(spaceGradient())
            .padding(28.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SurfacePanel(
            modifier = Modifier
                .weight(0.92f)
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeaderLine(
                    title = "Hermes Quest",
                    subtitle = "Pair relay",
                    state = sphereStateFor(connectionState, authState, false),
                )

                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = { Text("Relay URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(6).uppercase() },
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { controller.pairManually(relayUrl, code) },
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pair")
                    }
                    OutlinedButton(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR")
                    }
                }

                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text("Pair payload") },
                    minLines = 4,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = { controller.pairFromQrPayload(payload) },
                    enabled = payload.isNotBlank(),
                ) {
                    Text("Import payload")
                }

                StatusText(authState, connectionState)
            }
        }

        SurfacePanel(
            modifier = Modifier
                .weight(1.08f)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (scanOpen) {
                    QuestQrScanner(
                        onPayload = {
                            payload = it
                            scanOpen = false
                            controller.pairFromQrPayload(it)
                        },
                        onError = { cameraError = it },
                        modifier = Modifier
                            .fillMaxHeight(0.92f)
                            .aspectRatio(1.2f),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MorphingSphere(
                            state = sphereStateFor(connectionState, authState, false),
                            modifier = Modifier.size(280.dp),
                        )
                        Text(
                            text = cameraError ?: "QR pairing ready",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalCockpit(
    controller: QuestTerminalController,
    onPairing: () -> Unit,
) {
    val tabs by controller.tabs.collectAsStateWithLifecycle()
    val activeTabId by controller.activeTabId.collectAsStateWithLifecycle()
    val connectionState by controller.connectionState.collectAsStateWithLifecycle()
    val authState by controller.authState.collectAsStateWithLifecycle()
    var passthroughMode by remember { mutableStateOf(true) }
    val activeTab = tabs.firstOrNull { it.tabId == activeTabId } ?: tabs.first()
    val sphereState = sphereStateFor(connectionState, authState, activeTab.attached, activeTab.error)
    val backgroundModifier = if (passthroughMode) {
        Modifier.background(Color(0xCC06110E))
    } else {
        Modifier.background(spaceGradient())
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(backgroundModifier)
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SessionRail(
            tabs = tabs,
            activeTabId = activeTabId,
            connectionState = connectionState,
            onSelect = controller::selectTab,
            onAdd = { controller.openNewTab() },
            onPairing = onPairing,
            modifier = Modifier.width(184.dp),
        )

        SurfacePanel(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Column(Modifier.fillMaxSize()) {
                TerminalHeader(
                    tab = activeTab,
                    connectionState = connectionState,
                    passthroughMode = passthroughMode,
                    onModeToggle = {
                        val next = !passthroughMode
                        passthroughMode = next
                        updateSpatialMixedReality(next)
                    },
                    onStart = { controller.startSession(activeTab.tabId) },
                    onDetach = { controller.detachTab(activeTab.tabId) },
                    onKill = { controller.killTab(activeTab.tabId) },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF050908)),
                ) {
                    RelayTerminalWebView(
                        controller = controller,
                        tabId = activeTab.tabId,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                ExtraKeysToolbar(
                    ctrlActive = activeTab.ctrlActive,
                    altActive = activeTab.altActive,
                    onEsc = { controller.sendKey(activeTab.tabId, SpecialKey.ESC) },
                    onTab = { controller.sendKey(activeTab.tabId, SpecialKey.TAB) },
                    onCtrlToggle = { controller.toggleCtrl(activeTab.tabId) },
                    onAltToggle = { controller.toggleAlt(activeTab.tabId) },
                    onArrow = { controller.sendKey(activeTab.tabId, it) },
                    onPageUp = { controller.sendKey(activeTab.tabId, SpecialKey.PAGE_UP) },
                    onPageDown = { controller.sendKey(activeTab.tabId, SpecialKey.PAGE_DOWN) },
                )
            }
        }

        AmbientPanel(
            sphereState = sphereState,
            connectionState = connectionState,
            tab = activeTab,
            modifier = Modifier.width(222.dp),
        )
    }
}

private fun updateSpatialMixedReality(enabled: Boolean) {
    runCatching {
        SpatialActivityManager.executeOnVrActivity<MainQuestActivity> { activity ->
            activity.setMixedRealityEnabled(enabled)
        }
    }
}

@Composable
private fun SessionRail(
    tabs: List<QuestTerminalController.TabState>,
    activeTabId: Int,
    connectionState: ConnectionState,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SurfacePanel(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Hermes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            ConnectionPill(connectionState)
            tabs.forEach { tab ->
                TabChip(
                    tab = tab,
                    active = tab.tabId == activeTabId,
                    onClick = { onSelect(tab.tabId) },
                )
            }
            IconButton(onClick = onAdd, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.Add, contentDescription = "New tab")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPairing, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Pairing")
            }
        }
    }
}

@Composable
private fun TerminalHeader(
    tab: QuestTerminalController.TabState,
    connectionState: ConnectionState,
    passthroughMode: Boolean,
    onModeToggle: () -> Unit,
    onStart: () -> Unit,
    onDetach: () -> Unit,
    onKill: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = tab.sessionName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "${connectionState.name.lowercase()} · ${tab.cols}x${tab.rows}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onModeToggle) {
            Icon(
                if (passthroughMode) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = "Toggle passthrough mode",
            )
        }
        FilledTonalButton(onClick = onStart) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (tab.attached) "Attach" else "Start")
        }
        OutlinedButton(onClick = onDetach) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Detach")
        }
        Button(
            onClick = onKill,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Kill")
        }
    }
}

@Composable
private fun AmbientPanel(
    sphereState: SphereState,
    connectionState: ConnectionState,
    tab: QuestTerminalController.TabState,
    modifier: Modifier = Modifier,
) {
    SurfacePanel(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MorphingSphere(
                state = sphereState,
                intensity = if (tab.attached) 0.5f else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Text(
                text = sphereState.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = connectionState.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tab.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    tab: QuestTerminalController.TabState,
    active: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    val border = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (tab.attached) Color(0xFF6BE58B) else MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "tab ${tab.tabId}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun HeaderLine(
    title: String,
    subtitle: String,
    state: SphereState,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            MorphingSphere(state = state, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionPill(state: ConnectionState) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    when (state) {
                        ConnectionState.Connected -> Color(0xFF6BE58B)
                        ConnectionState.Connecting, ConnectionState.Reconnecting -> Color(0xFFFFD166)
                        ConnectionState.Disconnected -> Color(0xFFFF6B6B)
                    },
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(state.name, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatusText(
    authState: QuestAuthState,
    connectionState: ConnectionState,
) {
    val text = when (authState) {
        QuestAuthState.Unpaired -> "Unpaired"
        QuestAuthState.Pairing -> "Pairing"
        is QuestAuthState.Paired -> "Paired"
        is QuestAuthState.Failed -> authState.reason
    }
    Text(
        text = "$text · ${connectionState.name}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun sphereStateFor(
    connectionState: ConnectionState,
    authState: QuestAuthState,
    terminalAttached: Boolean,
    error: String? = null,
): SphereState = when {
    error != null || authState is QuestAuthState.Failed -> SphereState.Error
    authState is QuestAuthState.Pairing || connectionState == ConnectionState.Connecting -> SphereState.Thinking
    connectionState == ConnectionState.Reconnecting -> SphereState.Thinking
    terminalAttached -> SphereState.Streaming
    authState is QuestAuthState.Paired -> SphereState.Idle
    else -> SphereState.Idle
}

@Composable
private fun SurfacePanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
private fun HermesQuestTheme(content: @Composable () -> Unit) {
    val colorScheme = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF6BE58B),
        onPrimary = Color(0xFF03240F),
        secondary = Color(0xFF65D8E8),
        onSecondary = Color(0xFF062126),
        tertiary = Color(0xFFFFD166),
        onTertiary = Color(0xFF2E2200),
        background = Color(0xFF07110E),
        onBackground = Color(0xFFE7F6EA),
        surface = Color(0xFF0C1714),
        onSurface = Color(0xFFE7F6EA),
        surfaceContainerHigh = Color(0xFF14211E),
        onSurfaceVariant = Color(0xFFB2C8BC),
        outline = Color(0xFF60786A),
        outlineVariant = Color(0xFF2D4639),
        error = Color(0xFFFF6B6B),
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

private fun spaceGradient(): Brush = Brush.linearGradient(
    listOf(
        Color(0xFF07110E),
        Color(0xFF102822),
        Color(0xFF061B22),
        Color(0xFF0F151D),
    ),
)
