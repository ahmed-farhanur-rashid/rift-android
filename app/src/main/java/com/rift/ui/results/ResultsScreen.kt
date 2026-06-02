package com.rift.ui.results

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.rift.core.data.ApIdentity
import com.rift.ui.theme.*
import androidx.compose.foundation.Image

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: ResultsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var viewSize by remember { mutableStateOf(IntSize(1080, 1920)) }

    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }

    LaunchedEffect(state.exportedUri) {
        state.exportedUri?.let { uri ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Heatmap"))
            viewModel.clearExportedUri()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.session?.name ?: "Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::exportHeatmap,
                        enabled = !state.isExporting && state.heatmapBitmap != null
                    ) {
                        if (state.isExporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Share, "Export")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Stats row
            state.session?.let { session ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ResultStat("Points", session.totalPoints.toString(), NeonCyan)
                        ResultStat("Avg RSSI", "${state.averageRssi} dBm", SignalFair)
                        ResultStat("Coverage", "${state.coveragePercent}%", SignalGood)
                        ResultStat("APs", state.availableAps.size.toString(), MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // ── Signal / Risk mode toggle ──────────────────────────────────────
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "View:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HeatmapMode.entries.forEach { mode ->
                        val isSelected = state.heatmapMode == mode
                        val (label, icon) = when (mode) {
                            HeatmapMode.SIGNAL_STRENGTH -> "Signal" to Icons.Default.Wifi
                            HeatmapMode.RISK_MAP        -> "Risk Map" to Icons.Default.Shield
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setHeatmapMode(mode) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            leadingIcon = {
                                Icon(icon, null, modifier = Modifier.size(14.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = when (mode) {
                                    HeatmapMode.SIGNAL_STRENGTH -> NeonCyan.copy(alpha = 0.2f)
                                    HeatmapMode.RISK_MAP        -> SignalPoor.copy(alpha = 0.2f)
                                },
                                selectedLabelColor = when (mode) {
                                    HeatmapMode.SIGNAL_STRENGTH -> NeonCyan
                                    HeatmapMode.RISK_MAP        -> SignalPoor
                                }
                            )
                        )
                    }
                    // Risk score hint when risk map is selected but scores not ready
                    if (state.heatmapMode == HeatmapMode.RISK_MAP && state.riskScores.isEmpty()) {
                        Text(
                            "Computing…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // AP Selector
            if (state.availableAps.isNotEmpty()) {
                ApSelector(
                    availableAps = state.availableAps,
                    selectedBssid = state.selectedBssid,
                    onApSelected = viewModel::selectAp
                )
            }

            // Heatmap display
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged {
                        if (it.width > 0 && it.height > 0 && it != viewSize) {
                            viewSize = it
                            viewModel.loadSession(sessionId, it.width, it.height)
                        }
                    }
            ) {
                // Blueprint background
                state.session?.blueprintUri?.let { uriStr ->
                    AsyncImage(
                        model = Uri.parse(uriStr),
                        contentDescription = "Floor plan",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alpha = 0.6f
                    )
                }

                // Heatmap overlay
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = NeonCyan)
                } else {
                    state.heatmapBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = if (state.heatmapMode == HeatmapMode.RISK_MAP) "Risk map" else "WiFi heatmap",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                }

                // Legend — changes with mode
                HeatmapLegend(
                    isRiskMode = state.heatmapMode == HeatmapMode.RISK_MAP,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                )

                // Risk mode watermark
                if (state.heatmapMode == HeatmapMode.RISK_MAP) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        color = SignalPoor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Shield, null, tint = SignalPoor, modifier = Modifier.size(12.dp))
                            Text("RF Risk Map", style = MaterialTheme.typography.labelSmall, color = SignalPoor)
                        }
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ApSelector(
    availableAps: List<ApIdentity>,
    selectedBssid: String?,
    onApSelected: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            ApChip(label = "All APs", isSelected = selectedBssid == null, onClick = { onApSelected(null) })
        }
        items(availableAps) { ap ->
            ApChip(
                label = ap.ssid.ifEmpty { ap.bssid.takeLast(8) },
                isSelected = selectedBssid == ap.bssid,
                onClick = { onApSelected(ap.bssid) }
            )
        }
    }
}

@Composable
private fun ApChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
            selectedLabelColor = NeonCyan
        )
    )
}

@Composable
private fun ResultStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeatmapLegend(isRiskMode: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (isRiskMode) "Risk" else "Signal",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            if (isRiskMode) {
                listOf(
                    SignalExcellent to "Low",
                    SignalFair to "Moderate",
                    SignalPoor to "High",
                    SignalVeryPoor to "Critical"
                )
            } else {
                listOf(
                    SignalExcellent to "Excellent",
                    SignalGood to "Good",
                    SignalFair to "Fair",
                    SignalPoor to "Poor",
                    SignalVeryPoor to "Weak"
                )
            }.forEach { (color, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}
