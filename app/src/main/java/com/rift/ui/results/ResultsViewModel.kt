package com.rift.ui.results

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rift.core.data.ApIdentity
import com.rift.core.data.ApReading
import com.rift.core.data.ScanDataPoint
import com.rift.core.data.SessionEntity
import com.rift.core.data.SessionRepository
import com.rift.core.ml.RiskScore
import com.rift.core.ml.MoEGate
import com.rift.core.positioning.HeatmapRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

enum class HeatmapMode { SIGNAL_STRENGTH, RISK_MAP }

data class ResultsUiState(
    val session: SessionEntity? = null,
    val allPoints: List<ScanDataPoint> = emptyList(),
    val availableAps: List<ApIdentity> = emptyList(),
    val selectedBssid: String? = null,  // null = all combined
    val heatmapBitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val exportedUri: Uri? = null,
    val averageRssi: Int = 0,
    val coveragePercent: Int = 0,
    // Risk overlay mode
    val heatmapMode: HeatmapMode = HeatmapMode.SIGNAL_STRENGTH,
    val riskScores: List<RiskScore> = emptyList()
)

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val heatmapRenderer: HeatmapRenderer,
    private val moeGate: MoEGate,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultsUiState())
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    private var viewWidth = 1080
    private var viewHeight = 1920

    fun loadSession(sessionId: String, width: Int = viewWidth, height: Int = viewHeight) {
        viewWidth = width
        viewHeight = height
        viewModelScope.launch {
            val session = sessionRepository.getSessionById(sessionId) ?: return@launch
            val points = sessionRepository.getFullSessionData(sessionId)
            val aps = sessionRepository.getDistinctApsForSession(sessionId)
            val avgRssi = points.flatMap { it.readings }.map { it.rssi }.average().toInt()

            _uiState.update {
                it.copy(
                    session = session,
                    allPoints = points,
                    availableAps = aps,
                    averageRssi = avgRssi,
                    coveragePercent = estimateCoverage(points, width, height)
                )
            }

            // Compute session-level risk scores in background
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val allReadings = points.flatMap { it.readings }.distinctBy { it.bssid }
                    val report = moeGate.evaluate(allReadings)
                    _uiState.update { it.copy(riskScores = report.riskScores) }
                } catch (e: Exception) {
                    Timber.w(e, "ResultsViewModel: risk scoring failed — risk map unavailable")
                }
            }

            renderHeatmap(points, null)
        }
    }

    fun selectAp(bssid: String?) {
        _uiState.update { it.copy(selectedBssid = bssid, isLoading = true) }
        viewModelScope.launch {
            val mode = _uiState.value.heatmapMode
            if (mode == HeatmapMode.RISK_MAP) {
                renderRiskOverlay(_uiState.value.allPoints, bssid)
            } else {
                renderHeatmap(_uiState.value.allPoints, bssid)
            }
        }
    }

    fun setHeatmapMode(mode: HeatmapMode) {
        if (_uiState.value.heatmapMode == mode) return
        _uiState.update { it.copy(heatmapMode = mode, isLoading = true) }
        viewModelScope.launch {
            val state = _uiState.value
            if (mode == HeatmapMode.RISK_MAP) {
                renderRiskOverlay(state.allPoints, state.selectedBssid)
            } else {
                renderHeatmap(state.allPoints, state.selectedBssid)
            }
        }
    }

    fun exportHeatmap() {
        val bitmap = _uiState.value.heatmapBitmap ?: return
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                val prefix = if (_uiState.value.heatmapMode == HeatmapMode.RISK_MAP) "risk_map" else "wifi_heatmap"
                val fileName = "${prefix}_${System.currentTimeMillis()}.png"
                val file = File(context.getExternalFilesDir(null), fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                _uiState.update { it.copy(isExporting = false, exportedUri = Uri.fromFile(file)) }
            } catch (e: Exception) {
                Timber.e(e, "Export failed")
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun clearExportedUri() {
        _uiState.update { it.copy(exportedUri = null) }
    }

    private suspend fun renderHeatmap(points: List<ScanDataPoint>, bssid: String?) {
        if (points.isEmpty()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        val binnedPoints = points.map { pt ->
            val readings = if (bssid != null) pt.readings.filter { it.bssid == bssid } else pt.readings
            pt.copy(readings = readings)
        }.filter { it.readings.isNotEmpty() }

        val bitmap = heatmapRenderer.renderCombined(binnedPoints, viewWidth, viewHeight, bssid)
        _uiState.update { it.copy(heatmapBitmap = bitmap, isLoading = false) }
    }

    /**
     * Renders a risk map by substituting RSSI values with risk scores from the MoE gate.
     *
     * Reuses HeatmapRenderer.renderCombined() with the same IDW interpolation,
     * tiling, and confidence weighting — only the value domain changes.
     *
     * The risk score is mapped to a synthetic "RSSI-equivalent" value in [-95, -30]
     * so that the existing renderer colour pipeline applies. Risk score 0.0 → -95 (green),
     * risk score 1.0 → -30 (red). This inverts the visual — red = high risk.
     * A separate colour palette flag on HeatmapRenderer is set via useRiskPalette=true.
     */
    private suspend fun renderRiskOverlay(points: List<ScanDataPoint>, bssid: String?) {
        if (points.isEmpty() || _uiState.value.riskScores.isEmpty()) {
            // Fall back to signal heatmap if risk data isn't ready
            renderHeatmap(points, bssid)
            return
        }

        val riskByBssid = _uiState.value.riskScores.associateBy { it.bssid }

        // Synthesise scan data points with RSSI replaced by risk-score-derived values
        val riskPoints = points.map { pt ->
            val riskReadings = pt.readings.mapNotNull { reading ->
                val targetBssid = bssid
                if (targetBssid != null && reading.bssid != targetBssid) return@mapNotNull null
                val riskScore = riskByBssid[reading.bssid]?.score ?: 0.4f  // neutral for unknown
                // Map [0,1] risk → [-95,-30] RSSI-equivalent (higher risk = higher RSSI equiv = more red)
                val syntheticRssi = (-95 + (riskScore * 65f)).toInt().coerceIn(-95, -30)
                reading.copy(rssi = syntheticRssi)
            }
            pt.copy(readings = riskReadings)
        }.filter { it.readings.isNotEmpty() }

        try {
            val bitmap = heatmapRenderer.renderCombined(
                points = riskPoints,
                width = viewWidth,
                height = viewHeight,
                filterBssid = bssid,
                useRiskPalette = true
            )
            _uiState.update { it.copy(heatmapBitmap = bitmap, isLoading = false) }
        } catch (e: Exception) {
            Timber.e(e, "Risk overlay render failed — falling back to signal heatmap")
            renderHeatmap(points, bssid)
        }
    }

    private fun estimateCoverage(points: List<ScanDataPoint>, width: Int, height: Int): Int {
        if (points.isEmpty()) return 0
        val gridSize = 50
        val covered = mutableSetOf<Pair<Int, Int>>()
        for (pt in points) {
            covered.add(Pair((pt.xPixels / gridSize).toInt(), (pt.yPixels / gridSize).toInt()))
        }
        val totalCells = (width / gridSize) * (height / gridSize)
        return if (totalCells > 0) ((covered.size * 100) / totalCells).coerceAtMost(100) else 0
    }
}
