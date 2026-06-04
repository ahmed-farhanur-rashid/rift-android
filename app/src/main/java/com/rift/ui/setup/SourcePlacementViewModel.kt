package com.rift.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rift.core.data.SessionRepository
import com.rift.core.data.WifiSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourcePlacementUiState(
    val sessionId: String = "",
    val sources: List<WifiSource> = emptyList(),
    val useFreeSpacePathLoss: Boolean = true,
    val originX: Float = 0f,
    val originY: Float = 0f,
    val pixelsPerMeter: Float = 50f,
    val pendingTapMeters: Pair<Float, Float>? = null
)

@HiltViewModel
class SourcePlacementViewModel @Inject constructor(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourcePlacementUiState())
    val uiState: StateFlow<SourcePlacementUiState> = _uiState.asStateFlow()

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getSessionById(sessionId) ?: return@launch
            val sources = sessionRepository.getWifiSources(sessionId)
            _uiState.update {
                it.copy(
                    sessionId = sessionId,
                    sources = sources,
                    originX = session.originX,
                    originY = session.originY,
                    pixelsPerMeter = session.pixelsPerMeter
                )
            }
        }
    }

    fun setCoverageModel(useFreeSpace: Boolean) {
        _uiState.update { it.copy(useFreeSpacePathLoss = useFreeSpace) }
    }

    fun onMapTap(xPx: Float, yPx: Float) {
        val state = _uiState.value
        val xMeters = (xPx - state.originX) / state.pixelsPerMeter
        val yMeters = (yPx - state.originY) / state.pixelsPerMeter
        _uiState.update { it.copy(pendingTapMeters = Pair(xMeters, yMeters)) }
    }

    fun addSource(source: WifiSource) {
        val state = _uiState.value
        val tapPos = state.pendingTapMeters
        viewModelScope.launch {
            val newSource = source.copy(
                sessionId = state.sessionId,
                xMeters = tapPos?.first ?: source.xMeters,
                yMeters = tapPos?.second ?: source.yMeters
            )
            val id = sessionRepository.saveWifiSource(newSource)
            val updatedSources = sessionRepository.getWifiSources(state.sessionId)
            _uiState.update {
                it.copy(
                    sources = updatedSources,
                    pendingTapMeters = null
                )
            }
        }
    }

    fun updateSource(source: WifiSource) {
        viewModelScope.launch {
            sessionRepository.updateWifiSource(source)
            val updatedSources = sessionRepository.getWifiSources(_uiState.value.sessionId)
            _uiState.update { it.copy(sources = updatedSources) }
        }
    }

    fun deleteSource(id: Long) {
        viewModelScope.launch {
            sessionRepository.deleteWifiSource(id)
            val updatedSources = sessionRepository.getWifiSources(_uiState.value.sessionId)
            _uiState.update { it.copy(sources = updatedSources) }
        }
    }
}
