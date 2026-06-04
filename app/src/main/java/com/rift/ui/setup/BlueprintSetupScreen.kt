package com.rift.ui.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.rift.ui.theme.NeonCyan
import com.rift.ui.theme.SignalFair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlueprintSetupScreen(
    onSetupComplete: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: BlueprintSetupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setBlueprintUri(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (state.step) {
                            SetupStep.CHOOSE_BLUEPRINT -> "Choose Floor Plan"
                            SetupStep.CALIBRATE_SCALE -> "Calibrate Scale"
                            SetupStep.SET_ORIGIN -> "Set Start Position"
                            SetupStep.CONFIRM -> "Confirm Setup"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state.step) {
                SetupStep.CHOOSE_BLUEPRINT -> ChooseBlueprintStep(
                    sessionName = state.sessionName,
                    onSessionNameChange = viewModel::setSessionName,
                    onPickFromGallery = { imagePickerLauncher.launch("image/*") },
                    onSkip = viewModel::skipBlueprint
                )

                SetupStep.CALIBRATE_SCALE -> state.blueprintUri?.let { uri ->
                    CalibrateScaleStep(
                        blueprintUri = uri,
                        point1 = state.calibrationPoint1,
                        point2 = state.calibrationPoint2,
                        realWorldDistance = state.realWorldDistance,
                        onPoint1Set = viewModel::setCalibrationPoint1,
                        onPoint2Set = viewModel::setCalibrationPoint2,
                        onDistanceChange = viewModel::setRealWorldDistance,
                        onConfirm = viewModel::confirmCalibration
                    )
                }

                SetupStep.SET_ORIGIN -> SetOriginStep(
                    blueprintUri = state.blueprintUri,
                    originPoint = state.originPoint,
                    onOriginSet = viewModel::setOriginPoint
                )

                SetupStep.CONFIRM -> ConfirmSetupStep(
                    state = state,
                    isLoading = state.isCreatingSession,
                    onConfirm = { viewModel.createSession(onSetupComplete) }
                )
            }
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text("OK") }
            }
        )
    }
}

@Composable
private fun ChooseBlueprintStep(
    sessionName: String,
    onSessionNameChange: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Name your scan session and optionally pick a floor plan image.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = sessionName,
            onValueChange = onSessionNameChange,
            label = { Text("Session name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text("Floor Plan Image (Optional)", style = MaterialTheme.typography.titleMedium)

        Button(
            onClick = onPickFromGallery,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Pick from Gallery")
        }

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Skip — Use Blank Canvas")
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "No floor plan? No problem.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "You'll get a blank canvas heatmap showing signal strength as you walk. Add a floor plan later for overlay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CalibrateScaleStep(
    blueprintUri: Uri,
    point1: Pair<Float, Float>?,
    point2: Pair<Float, Float>?,
    realWorldDistance: String,
    onPoint1Set: (Float, Float) -> Unit,
    onPoint2Set: (Float, Float) -> Unit,
    onDistanceChange: (String) -> Unit,
    onConfirm: () -> Unit
) {
    val tappingPoint1 = point1 == null
    val canConfirm = point1 != null && point2 != null && realWorldDistance.toFloatOrNull() != null

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (tappingPoint1)
                "Tap Point 1 — a known location on the floor plan"
            else
                "Tap Point 2 — another known location",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AsyncImage(
                model = blueprintUri,
                contentDescription = "Floor plan",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(point1) {
                        detectTapGestures { offset ->
                            if (tappingPoint1) {
                                onPoint1Set(offset.x, offset.y)
                            } else {
                                onPoint2Set(offset.x, offset.y)
                            }
                        }
                    },
                contentScale = ContentScale.Fit
            )

            // Draw calibration markers
            Canvas(modifier = Modifier.fillMaxSize()) {
                point1?.let { (x, y) ->
                    drawCircle(NeonCyan, radius = 12f, center = Offset(x, y))
                    drawCircle(NeonCyan, radius = 12f, center = Offset(x, y), style = Stroke(3f))
                    drawLine(NeonCyan, Offset(x - 20, y), Offset(x + 20, y), strokeWidth = 2f)
                    drawLine(NeonCyan, Offset(x, y - 20), Offset(x, y + 20), strokeWidth = 2f)
                }
                point2?.let { (x, y) ->
                    drawCircle(SignalFair, radius = 12f, center = Offset(x, y))
                    drawCircle(SignalFair, radius = 12f, center = Offset(x, y), style = Stroke(3f))
                    point1?.let { (x1, y1) ->
                        drawLine(NeonCyan, Offset(x1, y1), Offset(x, y), strokeWidth = 2f)
                    }
                }
            }
        }

        if (point1 != null && point2 != null) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = realWorldDistance,
                    onValueChange = onDistanceChange,
                    label = { Text("Real-world distance between points (meters)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onConfirm,
                    enabled = canConfirm,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm Calibration")
                }
            }
        }
    }
}

@Composable
private fun SetOriginStep(
    blueprintUri: Uri?,
    originPoint: Pair<Float, Float>?,
    onOriginSet: (Float, Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Tap where you'll start your walk. Stand there now.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (blueprintUri != null) {
                AsyncImage(
                    model = blueprintUri,
                    contentDescription = "Floor plan",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                onOriginSet(offset.x, offset.y)
                            }
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                // Blank canvas with grid
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A2E))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                onOriginSet(offset.x, offset.y)
                            }
                        }
                ) {
                    // Draw grid
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
                    Text(
                        "Blank Canvas Mode",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                originPoint?.let { (x, y) ->
                    drawCircle(NeonCyan, radius = 16f, center = Offset(x, y))
                    drawCircle(Color.White, radius = 6f, center = Offset(x, y))
                    // Pulse rings
                    drawCircle(NeonCyan.copy(alpha = 0.4f), radius = 28f, center = Offset(x, y), style = Stroke(2f))
                    drawCircle(NeonCyan.copy(alpha = 0.2f), radius = 42f, center = Offset(x, y), style = Stroke(1f))
                }
            }
        }
    }
}

@Composable
private fun ConfirmSetupStep(
    state: SetupUiState,
    isLoading: Boolean,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ready to scan!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Session: ${state.sessionName}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = if (state.blueprintUri != null) "Scale: ${state.pixelsPerMeter.toInt()} px/m"
                   else "Blank canvas mode — 1:1 scale",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Before you start:", style = MaterialTheme.typography.labelLarge, color = NeonCyan)
                listOf(
                    "Pocket your phone — don't hold it out",
                    "Walk at a normal, steady pace",
                    "Cover all rooms you want mapped",
                    "Tap 'Anchor' at known landmarks to reduce drift"
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onConfirm,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
            } else {
                Text("Begin Scanning", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
