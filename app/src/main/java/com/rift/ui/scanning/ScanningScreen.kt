package com.rift.ui.scanning

import android.Manifest
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.rift.core.data.ApReading
import com.rift.core.data.ScanDataPoint
import com.rift.core.ml.RiskLevel
import com.rift.ui.theme.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScanningScreen(
    sessionId: String,
    onSessionComplete: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ScanningViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showStopDialog by remember { mutableStateOf(false) }
    var anchorMode by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }
    val permissionsState = rememberMultiplePermissionsState(requiredPermissions)

    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted && !state.isScanning && state.session != null) {
            viewModel.startScanning()
        }
    }

    if (!permissionsState.allPermissionsGranted) {
        PermissionRequestScreen(onRequest = { permissionsState.launchMultiplePermissionRequest() })
        return
    }

    // Shield icon tint reflects current risk level
    val shieldTint = when (state.threatReport?.overallRiskLevel) {
        RiskLevel.CRITICAL -> SignalVeryPoor
        RiskLevel.WARNING  -> SignalPoor
        RiskLevel.CAUTION  -> SignalFair
        RiskLevel.SAFE     -> SignalExcellent
        null               -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.session?.name ?: "Scanning") },
                navigationIcon = {
                    IconButton(onClick = { showStopDialog = true }) {
                        Icon(Icons.Default.Close, "Stop session")
                    }
                },
                actions = {
                    // WiFi filter button
                    IconButton(
                        onClick = { viewModel.toggleWifiPicker() },
                        colors = if (state.showWifiPicker || state.selectedBssids.isNotEmpty())
                            IconButtonDefaults.iconButtonColors(containerColor = NeonCyan.copy(alpha = 0.15f))
                        else
                            IconButtonDefaults.iconButtonColors()
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            "Filter networks",
                            tint = if (state.selectedBssids.isNotEmpty()) NeonCyan else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Shield — opens ThreatPanel
                    IconButton(
                        onClick = { viewModel.toggleThreatPanel() },
                        colors = if (state.showThreatPanel)
                            IconButtonDefaults.iconButtonColors(containerColor = shieldTint.copy(alpha = 0.15f))
                        else
                            IconButtonDefaults.iconButtonColors()
                    ) {
                        Icon(Icons.Default.Shield, "RF analysis", tint = shieldTint)
                    }
                    // Anchor button
                    IconButton(
                        onClick = { anchorMode = !anchorMode },
                        colors = if (anchorMode)
                            IconButtonDefaults.iconButtonColors(containerColor = NeonCyan.copy(alpha = 0.2f))
                        else
                            IconButtonDefaults.iconButtonColors()
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            "Set anchor",
                            tint = if (anchorMode) NeonCyan else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = { ScanningBottomBar(state) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { size -> viewModel.onViewSized(size.width, size.height) }
            ) {
                // Layer 1: Blueprint or blank canvas
                val blueprintUri = state.session?.blueprintUri
                if (!blueprintUri.isNullOrBlank()) {
                    AsyncImage(
                        model = Uri.parse(blueprintUri),
                        contentDescription = "Floor plan",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(anchorMode) {
                                detectTapGestures { offset ->
                                    if (anchorMode) {
                                        viewModel.applyAnchor(offset.x, offset.y)
                                        anchorMode = false
                                    }
                                }
                            },
                        contentScale = ContentScale.Fit,
                        alpha = 0.55f
                    )
                } else {
                    // Blank canvas with grid
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A2E))
                            .pointerInput(anchorMode) {
                                detectTapGestures { offset ->
                                    if (anchorMode) {
                                        viewModel.applyAnchor(offset.x, offset.y)
                                        anchorMode = false
                                    }
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val gridSpacing = 50f
                            val gridColor = Color.White.copy(alpha = 0.1f)
                            var x = 0f
                            while (x < size.width) {
                                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                                x += gridSpacing
                            }
                            var y = 0f
                            while (y < size.height) {
                                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                y += gridSpacing
                            }
                        }
                    }
                }

                // Layer 2: Live heatmap
                androidx.compose.animation.AnimatedVisibility(visible = state.liveHeatmap != null, enter = fadeIn(), exit = fadeOut()) {
                    state.liveHeatmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Live WiFi heatmap",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                            alpha = 0.72f
                        )
                    }
                }

                // Layer 3: PDR trail
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPdrOverlay(
                        points = state.scanPoints,
                        currentXPx = state.currentPdrX.toFloat() * (state.session?.pixelsPerMeter ?: 50f) + (state.session?.originX ?: 0f),
                        currentYPx = state.currentPdrY.toFloat() * (state.session?.pixelsPerMeter ?: 50f) + (state.session?.originY ?: 0f),
                        confidence = state.currentConfidence
                    )
                }

                // Anchor overlay
                if (anchorMode) {
                    Box(modifier = Modifier.fillMaxSize().background(NeonCyan.copy(alpha = 0.08f)))
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        color = NeonCyan.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "Tap your current position",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            color = Color.Black,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Drift warning
                if (state.isScanning && state.currentConfidence < 0.55f && !anchorMode) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                        color = SignalFair.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "⚠ Drift building — tap 📌 to anchor",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.Black,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // LIVE indicator
                if (state.isScanning && state.scanPoints.size >= 3) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(SignalExcellent, androidx.compose.foundation.shape.CircleShape))
                        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = SignalExcellent, fontWeight = FontWeight.Bold)
                    }
                }

                // Risk level badge (bottom-left) — tappable to open ThreatPanel
                state.threatReport?.let { report ->
                    if (report.overallRiskLevel != RiskLevel.SAFE) {
                        val badgeColor = when (report.overallRiskLevel) {
                            RiskLevel.CRITICAL -> SignalVeryPoor
                            RiskLevel.WARNING  -> SignalPoor
                            RiskLevel.CAUTION  -> SignalFair
                            else -> SignalExcellent
                        }
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            color = badgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            onClick = { viewModel.toggleThreatPanel() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Shield, null, tint = badgeColor, modifier = Modifier.size(14.dp))
                                Text(report.overallRiskLevel.name, style = MaterialTheme.typography.labelSmall, color = badgeColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Status strip
            if (state.isScanning) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        text = state.statusMessage,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Stop dialog
    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Stop Scanning?") },
            text = { Text("${state.scanPoints.size} points collected. Stopping saves the session and takes you to the full heatmap.") },
            confirmButton = {
                Button(onClick = { showStopDialog = false; viewModel.stopScanning(onSessionComplete) }) { Text("Stop & View Results") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text("Keep Scanning") }
            }
        )
    }

    // Saving overlay
    if (state.isFinishing) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = NeonCyan)
                Text("Saving session…", color = Color.White)
            }
        }
    }

    // Threat panel sheet
    if (state.showThreatPanel) {
        state.threatReport?.let { report ->
            ThreatPanel(report = report, onDismiss = { viewModel.dismissThreatPanel() })
        }
    }

    // WiFi network picker sheet
    if (state.showWifiPicker) {
        WifiPickerSheet(
            availableNetworks = state.availableNetworks,
            selectedBssids = state.selectedBssids,
            connectedBssid = state.connectedBssid,
            onToggle = { viewModel.toggleBssidFilter(it) },
            onSelectAll = { viewModel.selectAllNetworks() },
            onClearAll = { viewModel.clearAllNetworks() },
            onSelectMyNetwork = { viewModel.selectMyNetwork() },
            onDismiss = { viewModel.dismissWifiPicker() }
        )
    }
}

// ── Canvas helpers ────────────────────────────────────────────────────────────

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPdrOverlay(
    points: List<ScanDataPoint>,
    currentXPx: Float,
    currentYPx: Float,
    confidence: Float
) {
    if (points.isEmpty()) return
    if (points.size > 1) {
        val path = Path()
        path.moveTo(points.first().xPixels, points.first().yPixels)
        points.drop(1).forEach { path.lineTo(it.xPixels, it.yPixels) }
        drawPath(path, NeonCyan.copy(alpha = 0.7f), style = Stroke(width = 3f))
    }
    for (pt in points) {
        val rssi = pt.readings.maxOfOrNull { it.rssi } ?: -90
        val color = rssiToColor(rssi)
        drawCircle(color.copy(alpha = 0.85f), radius = 5f, center = Offset(pt.xPixels, pt.yPixels))
    }
    val ringRadius = 18f + (1f - confidence) * 45f
    drawCircle(NeonCyan.copy(alpha = 0.25f * confidence), radius = ringRadius, center = Offset(currentXPx, currentYPx), style = Stroke(width = 2f))
    drawCircle(NeonCyan, radius = 10f, center = Offset(currentXPx, currentYPx))
    drawCircle(Color.White, radius = 4f, center = Offset(currentXPx, currentYPx))
}

private fun rssiToColor(rssi: Int): Color = when {
    rssi >= -50 -> SignalExcellent
    rssi >= -65 -> SignalGood
    rssi >= -75 -> SignalFair
    rssi >= -85 -> SignalPoor
    else -> SignalVeryPoor
}

// ── Bottom bar ────────────────────────────────────────────────────────────────

@Composable
private fun ScanningBottomBar(state: ScanningUiState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatChip("Points", state.scanPoints.size.toString(), NeonCyan)
            StatChip("Steps", state.stepCount.toString(), MaterialTheme.colorScheme.onSurface)
            StatChip("APs", state.visibleApCount.toString(), SignalGood)
            StatChip("Time", formatElapsed(state.elapsedSeconds), MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Wifi, null, modifier = Modifier.size(64.dp), tint = NeonCyan)
        Spacer(Modifier.height(24.dp))
        Text("Permissions Required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val permissionReasons = buildList {
            add("Location — required by Android to scan for WiFi networks")
            add("WiFi State — read scan results and connected network info")
            add("Activity Recognition — detect steps for indoor positioning")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add("Nearby WiFi Devices (Android 13+) — required for WiFi scanning on modern devices")
            }
        }
        permissionReasons.forEach { reason ->
            Text(
                text = "• $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Grant Permissions") }
    }
}

private fun formatElapsed(seconds: Long): String {
    val m = TimeUnit.SECONDS.toMinutes(seconds)
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiPickerSheet(
    availableNetworks: List<ApReading>,
    selectedBssids: Set<String>,
    connectedBssid: String?,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onSelectMyNetwork: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Filter WiFi Networks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Select which networks to include in threat analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // Quick action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSelectMyNetwork,
                    modifier = Modifier.weight(1f),
                    enabled = connectedBssid != null
                ) {
                    Text("My Network", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onSelectAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("All", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onClearAll,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("None", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(12.dp))

            if (selectedBssids.isNotEmpty()) {
                Text(
                    "${selectedBssids.size} of ${availableNetworks.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonCyan
                )
                Spacer(Modifier.height(8.dp))
            }

            // Network list
            if (availableNetworks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No networks detected yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(availableNetworks) { network ->
                        val isConnected = network.bssid == connectedBssid
                        val isSelected = network.bssid in selectedBssids
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) NeonCyan.copy(alpha = 0.08f)
                                    else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggle(network.bssid) },
                                colors = CheckboxDefaults.colors(checkedColor = NeonCyan)
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = network.ssid.ifBlank { "[Hidden]" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isConnected) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = NeonCyan.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "Connected",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NeonCyan
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = network.bssid,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${network.rssi} dBm",
                                style = MaterialTheme.typography.labelMedium,
                                color = rssiToColor(network.rssi)
                            )
                        }
                    }
                }
            }
        }
    }
}
