package com.rift.ui.scanning

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rift.core.data.ApReading
import com.rift.core.ml.MoEGate
import com.rift.core.ml.ThreatReport
import com.rift.core.scanner.ScanForegroundService
import com.rift.core.scanner.WifiScanEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class QuickScanUiState(
    val isScanning: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val scanDurationSeconds: Long = 10L,
    val detectedNetworks: List<ApReading> = emptyList(),
    val connectedBssid: String? = null,
    val threatReport: ThreatReport? = null,
    val statusMessage: String = "Ready to scan",
    // Expert toggles
    val enableEvilTwin: Boolean = true,
    val enableRiskScoring: Boolean = true,
    val enableInterference: Boolean = true,
    val enableAnomalyDetection: Boolean = false
)

@HiltViewModel
class QuickScanViewModel @Inject constructor(
    private val wifiScanEngine: WifiScanEngine,
    private val moeGate: MoEGate,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickScanUiState())
    val uiState: StateFlow<QuickScanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var timerJob: Job? = null

    fun setScanDuration(seconds: Long) {
        _uiState.update { it.copy(scanDurationSeconds = seconds.coerceIn(5, 60)) }
    }

    fun toggleEvilTwin() { _uiState.update { it.copy(enableEvilTwin = !it.enableEvilTwin) } }
    fun toggleRiskScoring() { _uiState.update { it.copy(enableRiskScoring = !it.enableRiskScoring) } }
    fun toggleInterference() { _uiState.update { it.copy(enableInterference = !it.enableInterference) } }
    fun toggleAnomalyDetection() { _uiState.update { it.copy(enableAnomalyDetection = !it.enableAnomalyDetection) } }

    fun startScan() {
        if (_uiState.value.isScanning) return

        // Start foreground service to keep app alive
        val serviceIntent = ScanForegroundService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        _uiState.update {
            it.copy(
                isScanning = true,
                elapsedSeconds = 0L,
                detectedNetworks = emptyList(),
                threatReport = null,
                statusMessage = "Scanning..."
            )
        }

        val duration = _uiState.value.scanDurationSeconds

        // Timer
        timerJob = viewModelScope.launch {
            while (_uiState.value.elapsedSeconds < duration) {
                delay(1000)
                _uiState.update {
                    it.copy(
                        elapsedSeconds = it.elapsedSeconds + 1,
                        statusMessage = "Scanning... ${it.elapsedSeconds + 1}s / ${duration}s"
                    )
                }
            }
        }

        // Scan loop
        scanJob = viewModelScope.launch {
            wifiScanEngine.scanResultsFlow().collect { readings ->
                val connectedBssid = wifiScanEngine.getConnectedBssid()

                _uiState.update {
                    it.copy(
                        detectedNetworks = readings,
                        connectedBssid = connectedBssid
                    )
                }

                // Run MoE evaluation
                viewModelScope.launch {
                    try {
                        val threat = moeGate.evaluate(readings)
                        _uiState.update { it.copy(threatReport = threat) }
                    } catch (e: Exception) {
                        Timber.e(e, "MoE evaluation failed during quick scan")
                    }
                }
            }
        }

        // Auto-stop after duration
        viewModelScope.launch {
            delay(duration * 1000)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        timerJob?.cancel()
        context.startService(ScanForegroundService.stopIntent(context))

        _uiState.update {
            it.copy(
                isScanning = false,
                statusMessage = if (it.threatReport != null) "Scan complete" else "Scan stopped"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        timerJob?.cancel()
        context.startService(ScanForegroundService.stopIntent(context))
    }
}
