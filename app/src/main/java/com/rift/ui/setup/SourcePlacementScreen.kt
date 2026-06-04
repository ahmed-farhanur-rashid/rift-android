package com.rift.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rift.core.data.WifiSource
import com.rift.ui.theme.NeonCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePlacementScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: SourcePlacementViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<WifiSource?>(null) }

    LaunchedEffect(sessionId) {
        viewModel.loadSession(sessionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Place WiFi Sources") },
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
        ) {
            // Coverage model selector
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Coverage Model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.useFreeSpacePathLoss,
                            onClick = { viewModel.setCoverageModel(true) },
                            label = { Text("Free-space") },
                            leadingIcon = if (state.useFreeSpacePathLoss) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = !state.useFreeSpacePathLoss,
                            onClick = { viewModel.setCoverageModel(false) },
                            label = { Text("Hybrid") },
                            leadingIcon = if (!state.useFreeSpacePathLoss) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (state.useFreeSpacePathLoss)
                            "Physics-based signal prediction from each source. Shows how signal decays with distance."
                        else
                            "Combines your real scan data with synthetic predictions in areas you didn't walk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Map area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A2E))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            viewModel.onMapTap(offset.x, offset.y)
                        }
                    }
            ) {
                // Grid
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

                // Source markers
                state.sources.forEach { source ->
                    val xPx = state.originX + source.xMeters * state.pixelsPerMeter
                    val yPx = state.originY + source.yMeters * state.pixelsPerMeter
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Source circle
                        drawCircle(NeonCyan, radius = 16f, center = Offset(xPx, yPx))
                        drawCircle(Color.White, radius = 6f, center = Offset(xPx, yPx))
                        // Coverage circle (approximate)
                        val coverageRadius = source.transmitPowerDbm * 3f // Rough visualization
                        drawCircle(
                            NeonCyan.copy(alpha = 0.15f),
                            radius = coverageRadius,
                            center = Offset(xPx, yPx),
                            style = Stroke(2f)
                        )
                    }
                }

                // Tap hint
                if (state.sources.isEmpty()) {
                    Text(
                        "Tap to place WiFi sources",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Source list
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Sources (${state.sources.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, "Add source", tint = NeonCyan)
                        }
                    }

                    if (state.sources.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No sources placed yet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.sources) { source ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Wifi,
                                        null,
                                        tint = NeonCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(source.name, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${source.transmitPowerDbm.toInt()} dBm • ${source.frequencyMhz} MHz",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { editingSource = source }) {
                                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { viewModel.deleteSource(source.id) }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = Color.Red, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add/Edit dialog
    if (showAddDialog || editingSource != null) {
        SourceDialog(
            source = editingSource,
            onDismiss = {
                showAddDialog = false
                editingSource = null
            },
            onConfirm = { source ->
                if (editingSource != null) {
                    viewModel.updateSource(source)
                } else {
                    viewModel.addSource(source)
                }
                showAddDialog = false
                editingSource = null
            }
        )
    }
}

@Composable
private fun SourceDialog(
    source: WifiSource?,
    onDismiss: () -> Unit,
    onConfirm: (WifiSource) -> Unit
) {
    var name by remember { mutableStateOf(source?.name ?: "Repeater ${System.currentTimeMillis() % 1000}") }
    var txPower by remember { mutableStateOf((source?.transmitPowerDbm ?: 23f).toString()) }
    var frequency by remember { mutableStateOf((source?.frequencyMhz ?: 2400).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (source != null) "Edit Source" else "Add Source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = txPower,
                    onValueChange = { txPower = it },
                    label = { Text("TX Power (dBm)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = frequency,
                    onValueChange = { frequency = it },
                    label = { Text("Frequency (MHz)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tx = txPower.toFloatOrNull() ?: 23f
                    val freq = frequency.toIntOrNull() ?: 2400
                    onConfirm(
                        (source ?: WifiSource(sessionId = "", bssid = "")).copy(
                            name = name,
                            transmitPowerDbm = tx,
                            frequencyMhz = freq
                        )
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
