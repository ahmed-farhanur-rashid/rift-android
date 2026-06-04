package com.rift.ui.setup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rift.core.data.SessionRepository
import com.rift.core.positioning.ScaleCalibration
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val step: SetupStep = SetupStep.CHOOSE_BLUEPRINT,
    val blueprintUri: Uri? = null,
    val sessionName: String = "Scan Session",
    val calibrationPoint1: Pair<Float, Float>? = null,
    val calibrationPoint2: Pair<Float, Float>? = null,
    val realWorldDistance: String = "",
    val originPoint: Pair<Float, Float>? = null,
    val pixelsPerMeter: Float = 50f,
    val isCreatingSession: Boolean = false,
    val error: String? = null
)

enum class SetupStep {
    CHOOSE_BLUEPRINT,
    CALIBRATE_SCALE,
    SET_ORIGIN,
    CONFIRM
}

@HiltViewModel
class BlueprintSetupViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun setBlueprintUri(uri: Uri) {
        // Copy to app-internal storage for persistence
        viewModelScope.launch {
            val internalUri = copyBlueprintToInternal(context, uri)
            _uiState.update { it.copy(blueprintUri = internalUri, step = SetupStep.CALIBRATE_SCALE) }
        }
    }

    fun skipBlueprint() {
        // Skip floor plan - use blank canvas mode with 1:1 pixel-to-meter ratio
        _uiState.update {
            it.copy(
                blueprintUri = null,
                pixelsPerMeter = 1f,
                step = SetupStep.SET_ORIGIN
            )
        }
    }

    fun setSessionName(name: String) {
        _uiState.update { it.copy(sessionName = name) }
    }

    fun setCalibrationPoint1(x: Float, y: Float) {
        _uiState.update { it.copy(calibrationPoint1 = Pair(x, y)) }
    }

    fun setCalibrationPoint2(x: Float, y: Float) {
        val state = _uiState.value
        val p1 = state.calibrationPoint1 ?: return
        _uiState.update { it.copy(calibrationPoint2 = Pair(x, y)) }
    }

    fun setRealWorldDistance(distance: String) {
        _uiState.update { it.copy(realWorldDistance = distance) }
    }

    fun confirmCalibration() {
        val state = _uiState.value
        val p1 = state.calibrationPoint1 ?: return
        val p2 = state.calibrationPoint2 ?: return
        val distance = state.realWorldDistance.toFloatOrNull() ?: return

        val calibration = ScaleCalibration(p1, p2, distance)
        _uiState.update {
            it.copy(
                pixelsPerMeter = calibration.pixelsPerMeter,
                step = SetupStep.SET_ORIGIN
            )
        }
    }

    fun setOriginPoint(x: Float, y: Float) {
        _uiState.update { it.copy(originPoint = Pair(x, y), step = SetupStep.CONFIRM) }
    }

    fun createSession(onSuccess: (String) -> Unit) {
        val state = _uiState.value
        val origin = state.originPoint ?: return

        _uiState.update { it.copy(isCreatingSession = true) }

        viewModelScope.launch {
            try {
                val sessionId = sessionRepository.createSession(
                    name = state.sessionName,
                    blueprintUri = state.blueprintUri?.toString() ?: "",
                    pixelsPerMeter = state.pixelsPerMeter,
                    originX = origin.first,
                    originY = origin.second
                )
                onSuccess(sessionId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isCreatingSession = false, error = e.message) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun copyBlueprintToInternal(context: Context, uri: Uri): Uri {
        return try {
            val fileName = "blueprint_${System.currentTimeMillis()}.png"
            val outputFile = java.io.File(context.filesDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(outputFile)
        } catch (e: Exception) {
            uri // fallback to original uri
        }
    }
}
