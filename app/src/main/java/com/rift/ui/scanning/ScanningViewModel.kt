package com.rift.ui.scanning

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rift.core.data.ApReading
import com.rift.core.data.ScanDataPoint
import com.rift.core.data.SessionEntity
import com.rift.core.data.SessionRepository
import com.rift.core.ml.MoEGate
import com.rift.core.ml.ThreatReport
import com.rift.core.ml.experts.AnomalyDetector
import com.rift.core.positioning.HeatmapRenderer
import com.rift.core.positioning.PdrEngine
import com.rift.core.scanner.ScanForegroundService
import com.rift.core.scanner.WifiScanEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class ScanningUiState(
    val session: SessionEntity? = null,
    val isScanning: Boolean = false,
    val scanPoints: List<ScanDataPoint> = emptyList(),
    val currentPdrX: Double = 0.0,
    val currentPdrY: Double = 0.0,
    val currentConfidence: Float = 1f,
    val stepCount: Int = 0,
    val visibleApCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val statusMessage: String = "Initializing...",
    val isFinishing: Boolean = false,
    // Live heatmap rendered every HEATMAP_REFRESH_INTERVAL_MS
    val liveHeatmap: Bitmap? = null,
    val heatmapWidth: Int = 0,
    val heatmapHeight: Int = 0,
    // ML threat analysis — updated each scan cycle
    val threatReport: ThreatReport? = null,
    val showThreatPanel: Boolean = false,
    // WiFi network filtering
    val availableNetworks: List<ApReading> = emptyList(),
    val selectedBssids: Set<String> = emptySet(),
    val showWifiPicker: Boolean = false,
    val connectedBssid: String? = null
)

@HiltViewModel
class ScanningViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val wifiScanEngine: WifiScanEngine,
    private val pdrEngine: PdrEngine,
    private val heatmapRenderer: HeatmapRenderer,
    private val moeGate: MoEGate,
    private val anomalyDetector: AnomalyDetector,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanningUiState())
    val uiState: StateFlow<ScanningUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var timerJob: Job? = null
    private var heatmapJob: Job? = null
    private var sessionId: String? = null
    private var pixelsPerMeter: Float = 50f
    private var startTime: Long = 0L

    companion object {
        private const val HEATMAP_REFRESH_MS = 2000L
    }

    fun loadSession(id: String) {
        sessionId = id
        viewModelScope.launch {
            val session = sessionRepository.getSessionById(id) ?: return@launch
            pixelsPerMeter = session.pixelsPerMeter
            _uiState.update { it.copy(session = session, statusMessage = "Ready — press Start") }
        }
    }

    fun onViewSized(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            _uiState.update { it.copy(heatmapWidth = width, heatmapHeight = height) }
        }
    }

    fun toggleThreatPanel() {
        _uiState.update { it.copy(showThreatPanel = !it.showThreatPanel) }
    }

    fun dismissThreatPanel() {
        _uiState.update { it.copy(showThreatPanel = false) }
    }

    // ── WiFi Network Selection ──────────────────────────────────────────────

    fun toggleWifiPicker() {
        _uiState.update { it.copy(showWifiPicker = !it.showWifiPicker) }
    }

    fun dismissWifiPicker() {
        _uiState.update { it.copy(showWifiPicker = false) }
    }

    fun toggleBssidFilter(bssid: String) {
        _uiState.update { state ->
            val newSelected = if (bssid in state.selectedBssids) {
                state.selectedBssids - bssid
            } else {
                state.selectedBssids + bssid
            }
            state.copy(selectedBssids = newSelected)
        }
    }

    fun selectAllNetworks() {
        val allBssids = _uiState.value.availableNetworks.map { it.bssid }.toSet()
        _uiState.update { it.copy(selectedBssids = allBssids) }
    }

    fun clearAllNetworks() {
        _uiState.update { it.copy(selectedBssids = emptySet()) }
    }

    fun selectMyNetwork() {
        val connected = _uiState.value.connectedBssid ?: return
        _uiState.update { it.copy(selectedBssids = setOf(connected)) }
    }

    fun startScanning() {
        val sid = sessionId ?: return
        val session = _uiState.value.session ?: return

        val serviceIntent = ScanForegroundService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        pdrEngine.reset(
            originX = session.originX.toDouble() / pixelsPerMeter,
            originY = session.originY.toDouble() / pixelsPerMeter
        )
        pdrEngine.start()

        // Reset anomaly detector baseline for new session
        anomalyDetector.resetBaseline()

        startTime = System.currentTimeMillis()
        _uiState.update { it.copy(isScanning = true, statusMessage = "Walk normally…") }

        // ── WiFi scan loop ────────────────────────────────────────────────────
        scanJob = viewModelScope.launch {
            wifiScanEngine.scanResultsFlow().collect { allReadings ->
                val pdr = pdrEngine.pdrState.value
                val (xPx, yPx) = metersToPixels(pdr.x, pdr.y, session.originX, session.originY)

                // Track connected BSSID
                val connectedBssid = wifiScanEngine.getConnectedBssid()

                // Determine which readings to use based on filter
                val state = _uiState.value
                val filteredReadings = if (state.selectedBssids.isEmpty()) {
                    allReadings
                } else {
                    allReadings.filter { it.bssid in state.selectedBssids }
                }

                val dataPoint = ScanDataPoint(
                    xMeters = pdr.x,
                    yMeters = pdr.y,
                    xPixels = xPx,
                    yPixels = yPx,
                    timestamp = System.currentTimeMillis(),
                    confidence = pdr.confidence,
                    readings = filteredReadings
                )

                val scanPointId = sessionRepository.saveScanDataPoint(sid, dataPoint)

                _uiState.update { s ->
                    s.copy(
                        scanPoints = s.scanPoints + dataPoint,
                        currentPdrX = pdr.x,
                        currentPdrY = pdr.y,
                        currentConfidence = pdr.confidence,
                        stepCount = pdr.stepCount,
                        visibleApCount = filteredReadings.size,
                        availableNetworks = allReadings,
                        connectedBssid = connectedBssid,
                        statusMessage = "${s.scanPoints.size + 1} pts · ${filteredReadings.size} APs visible"
                    )
                }

                // ── MoE evaluation — runs on Default dispatcher, never blocks UI ──
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val threat = moeGate.evaluate(filteredReadings)
                        sessionRepository.saveThreatReport(scanPointId, threat)
                        _uiState.update { it.copy(threatReport = threat) }
                    } catch (e: Exception) {
                        Timber.e(e, "MoE evaluation failed — continuing without threat report")
                    }
                }
            }
        }

        // ── PDR position updates ──────────────────────────────────────────────
        viewModelScope.launch {
            pdrEngine.pdrState.collect { pdr ->
                _uiState.update { state ->
                    state.copy(
                        currentPdrX = pdr.x,
                        currentPdrY = pdr.y,
                        currentConfidence = pdr.confidence,
                        stepCount = pdr.stepCount
                    )
                }
            }
        }

        // ── Elapsed timer ─────────────────────────────────────────────────────
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update { it.copy(elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000) }
            }
        }

        // ── Live heatmap re-render loop ───────────────────────────────────────
        heatmapJob = viewModelScope.launch(Dispatchers.Default) {
            var lastRenderedCount = 0
            while (isActive) {
                delay(HEATMAP_REFRESH_MS)
                val state = _uiState.value
                val points = state.scanPoints
                val w = state.heatmapWidth
                val h = state.heatmapHeight

                if (points.size != lastRenderedCount && w > 0 && h > 0) {
                    try {
                        val bitmap = heatmapRenderer.renderCombined(
                            points = points,
                            width = w,
                            height = h,
                            filterBssid = null
                        )
                        lastRenderedCount = points.size
                        _uiState.update { it.copy(liveHeatmap = bitmap) }
                    } catch (e: Exception) {
                        Timber.e(e, "Live heatmap render failed")
                    }
                }
            }
        }
    }

    fun applyAnchor(xPixels: Float, yPixels: Float) {
        val session = _uiState.value.session ?: return
        val xMeters = (xPixels - session.originX) / pixelsPerMeter
        val yMeters = (yPixels - session.originY) / pixelsPerMeter
        pdrEngine.applyAnchor(xMeters.toDouble(), yMeters.toDouble())
        _uiState.update { it.copy(statusMessage = "Anchor applied ✓ — drift reset") }
    }

    fun stopScanning(onComplete: (String) -> Unit) {
        val sid = sessionId ?: return

        _uiState.update { it.copy(isFinishing = true, statusMessage = "Saving session…") }

        pdrEngine.stop()
        scanJob?.cancel()
        timerJob?.cancel()
        heatmapJob?.cancel()

        context.startService(ScanForegroundService.stopIntent(context))

        viewModelScope.launch {
            sessionRepository.finalizeSession(sid)
            _uiState.update { it.copy(isScanning = false, isFinishing = false) }
            onComplete(sid)
        }
    }

    private fun metersToPixels(
        xMeters: Double, yMeters: Double,
        originX: Float, originY: Float
    ): Pair<Float, Float> = Pair(
        originX + (xMeters * pixelsPerMeter).toFloat(),
        originY + (yMeters * pixelsPerMeter).toFloat()
    )

    override fun onCleared() {
        super.onCleared()
        pdrEngine.stop()
        scanJob?.cancel()
        timerJob?.cancel()
        heatmapJob?.cancel()
    }
}
