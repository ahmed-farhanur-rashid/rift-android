package com.rift.ui.scanning

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.rift.core.ml.*
import com.rift.ui.theme.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QuickScanScreen(
    onBack: () -> Unit,
    onStartFullScan: () -> Unit,
    viewModel: QuickScanViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

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

    if (!permissionsState.allPermissionsGranted) {
        QuickScanPermissionScreen(onRequest = { permissionsState.launchMultiplePermissionRequest() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Threat Scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Expert toggles
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Experts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    ExpertToggle(
                        name = "Evil Twin Detection",
                        description = "Detects rogue access points impersonating known networks",
                        enabled = state.enableEvilTwin,
                        onToggle = { viewModel.toggleEvilTwin() },
                        enabledDuringScan = true
                    )
                    ExpertToggle(
                        name = "Risk Scoring",
                        description = "Evaluates encryption, WPS, vendor vulnerabilities per AP",
                        enabled = state.enableRiskScoring,
                        onToggle = { viewModel.toggleRiskScoring() },
                        enabledDuringScan = true
                    )
                    ExpertToggle(
                        name = "Interference Analysis",
                        description = "Detects channel congestion and recommends best channel",
                        enabled = state.enableInterference,
                        onToggle = { viewModel.toggleInterference() },
                        enabledDuringScan = true
                    )
                    ExpertToggle(
                        name = "Anomaly Detection",
                        description = "Requires 10 scans for baseline — unavailable in quick scan",
                        enabled = state.enableAnomalyDetection,
                        onToggle = { viewModel.toggleAnomalyDetection() },
                        enabledDuringScan = false
                    )
                }
            }

            // Scan duration
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Scan Duration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${state.scanDurationSeconds}s",
                            style = MaterialTheme.typography.titleMedium,
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = state.scanDurationSeconds.toFloat(),
                        onValueChange = { viewModel.setScanDuration(it.toLong()) },
                        valueRange = 5f..60f,
                        steps = 10,
                        enabled = !state.isScanning,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("5s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("60s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Start/Stop button
            Button(
                onClick = {
                    if (state.isScanning) viewModel.stopScan()
                    else viewModel.startScan()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isScanning) SignalVeryPoor else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = if (state.isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.isScanning) "Stop Scan" else "Start Scan",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Progress
            AnimatedVisibility(visible = state.isScanning, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    LinearProgressIndicator(
                        progress = { state.elapsedSeconds.toFloat() / state.scanDurationSeconds.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = NeonCyan,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Detected networks summary
            if (state.detectedNetworks.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Detected Networks",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            NetworkStat("Total", state.detectedNetworks.size.toString(), NeonCyan)
                            NetworkStat("Open", state.detectedNetworks.count {
                                it.capabilities.contains("OPEN")
                            }.toString(), SignalVeryPoor)
                            NetworkStat("WPA2/3", state.detectedNetworks.count {
                                it.capabilities.contains("WPA2") || it.capabilities.contains("WPA3")
                            }.toString(), SignalExcellent)
                        }
                    }
                }
            }

            // Threat Report
            state.threatReport?.let { report ->
                ThreatReportCard(report = report)
            }

            // Start Full Scan button
            if (!state.isScanning && state.threatReport != null) {
                OutlinedButton(
                    onClick = onStartFullScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Full Scan with Heatmap", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ExpertToggle(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    enabledDuringScan: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            enabled = enabledDuringScan,
            colors = SwitchDefaults.colors(checkedTrackColor = NeonCyan)
        )
    }
}

@Composable
private fun NetworkStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThreatReportCard(report: ThreatReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (report.overallRiskLevel) {
                RiskLevel.CRITICAL -> SignalVeryPoor.copy(alpha = 0.1f)
                RiskLevel.WARNING -> SignalPoor.copy(alpha = 0.1f)
                RiskLevel.CAUTION -> SignalFair.copy(alpha = 0.1f)
                RiskLevel.SAFE -> SignalExcellent.copy(alpha = 0.1f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Threat Report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val riskColor = when (report.overallRiskLevel) {
                    RiskLevel.CRITICAL -> SignalVeryPoor
                    RiskLevel.WARNING -> SignalPoor
                    RiskLevel.CAUTION -> SignalFair
                    RiskLevel.SAFE -> SignalExcellent
                }
                Surface(
                    color = riskColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        report.overallRiskLevel.name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = riskColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Evil Twin
            ReportRow(
                icon = Icons.Default.Warning,
                label = "Evil Twin",
                value = report.evilTwinResult?.let {
                    "Detected (${(it.confidence * 100).toInt()}%)"
                } ?: "None detected",
                color = if (report.evilTwinResult != null) SignalVeryPoor else SignalExcellent
            )

            // Risk Scores
            val highRiskAps = report.riskScores.filter { it.score > 0.7f }
            ReportRow(
                icon = Icons.Default.Shield,
                label = "High Risk APs",
                value = if (highRiskAps.isNotEmpty()) "${highRiskAps.size} flagged" else "None",
                color = if (highRiskAps.isNotEmpty()) SignalPoor else SignalExcellent
            )

            // Interference
            ReportRow(
                icon = Icons.Default.SignalCellularAlt,
                label = "Interference",
                value = report.interferenceReport?.let {
                    "${it.severity.name}${it.recommendedChannel?.let { ch -> " — Ch $ch recommended" } ?: ""}"
                } ?: "Low",
                color = when (report.interferenceReport?.severity) {
                    InterferenceSeverity.HIGH, InterferenceSeverity.SEVERE -> SignalPoor
                    InterferenceSeverity.MEDIUM -> SignalFair
                    else -> SignalExcellent
                }
            )

            // Anomalies
            val anomalyCount = report.anomalyReport?.anomalies?.size ?: 0
            ReportRow(
                icon = Icons.Default.BugReport,
                label = "Anomalies",
                value = if (anomalyCount > 0) "$anomalyCount detected" else "None",
                color = if (anomalyCount > 0) SignalPoor else SignalExcellent
            )
        }
    }
}

@Composable
private fun ReportRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QuickScanPermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Wifi, null, modifier = Modifier.size(64.dp), tint = NeonCyan)
        Spacer(Modifier.height(24.dp))
        Text("Permissions Required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Quick Threat Scan needs WiFi and location permissions to analyze nearby networks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Grant Permissions") }
    }
}
