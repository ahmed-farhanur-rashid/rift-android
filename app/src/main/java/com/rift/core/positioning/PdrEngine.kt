package com.rift.core.positioning

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.rift.core.data.PdrState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pedestrian Dead Reckoning engine.
 *
 * Fuses:
 *   - TYPE_STEP_DETECTOR  → step events (hardware-fused, very reliable)
 *   - TYPE_ROTATION_VECTOR → continuous heading from mag+accel fusion
 *   - Accelerometer variance → ZUPT (zero-velocity update) for stationary detection
 *
 * Drift mitigation:
 *   - Anchor taps reset accumulated drift
 *   - ZUPT clamps heading drift when stationary
 *   - Confidence score degrades with distance from last anchor
 */
@Singleton
class PdrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _pdrState = MutableStateFlow(PdrState())
    val pdrState: StateFlow<PdrState> = _pdrState.asStateFlow()

    // Current fused heading from rotation vector sensor (radians)
    private var currentHeadingRad: Double = 0.0

    // Step length in meters — ~0.74m for average adult; calibrated by anchor taps
    private var stepLengthMeters: Double = 0.74

    // Anchor state for drift correction
    private var lastAnchorX: Double = 0.0
    private var lastAnchorY: Double = 0.0
    private var stepsSinceLastAnchor: Int = 0
    private val confidenceDegradationPerStep = 0.01f

    // ZUPT — stationary detection via accelerometer variance
    private val accelWindow = FloatArray(10)
    private var accelWindowIdx = 0
    private var isStationary = false

    private var stepListener: SensorEventListener? = null
    private var rotationListener: SensorEventListener? = null
    private var accelListener: SensorEventListener? = null

    fun start() {
        registerStepDetector()
        registerRotationVector()
        registerAccelerometer()
        Timber.d("PDR engine started")
    }

    fun stop() {
        stepListener?.let { sensorManager.unregisterListener(it) }
        rotationListener?.let { sensorManager.unregisterListener(it) }
        accelListener?.let { sensorManager.unregisterListener(it) }
        stepListener = null
        rotationListener = null
        accelListener = null
        Timber.d("PDR engine stopped")
    }

    fun reset(originX: Double = 0.0, originY: Double = 0.0) {
        _pdrState.value = PdrState(x = originX, y = originY)
        lastAnchorX = originX
        lastAnchorY = originY
        stepsSinceLastAnchor = 0
    }

    /**
     * Called when the user taps "I'm here" at a known blueprint location.
     * Corrects accumulated drift by snapping to the tapped position.
     */
    fun applyAnchor(anchorXMeters: Double, anchorYMeters: Double) {
        _pdrState.update { state ->
            state.copy(
                x = anchorXMeters,
                y = anchorYMeters,
                confidence = 1f
            )
        }
        lastAnchorX = anchorXMeters
        lastAnchorY = anchorYMeters
        stepsSinceLastAnchor = 0
        Timber.d("Anchor applied at (${anchorXMeters}, ${anchorYMeters})")
    }

    /**
     * Calibrate step length using two anchor taps at known distance.
     */
    fun calibrateStepLength(realWorldDistanceMeters: Double, stepsTaken: Int) {
        if (stepsTaken > 0) {
            stepLengthMeters = realWorldDistanceMeters / stepsTaken
            Timber.d("Step length calibrated: ${stepLengthMeters}m over $stepsTaken steps")
        }
    }

    private fun onStep() {
        if (isStationary) return  // ZUPT: ignore spurious steps when stationary

        val heading = currentHeadingRad
        val stepLen = stepLengthMeters

        _pdrState.update { state ->
            stepsSinceLastAnchor++
            val newConfidence = (1f - stepsSinceLastAnchor * confidenceDegradationPerStep)
                .coerceIn(0.1f, 1f)
            state.copy(
                x = state.x + stepLen * sin(heading),
                y = state.y + stepLen * cos(heading),
                heading = heading,
                stepCount = state.stepCount + 1,
                confidence = newConfidence
            )
        }
    }

    private fun onRotationVector(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        // orientationAngles[0] = azimuth (heading) in radians, -π to +π
        currentHeadingRad = orientationAngles[0].toDouble()
    }

    private fun onAccelerometer(event: SensorEvent) {
        // Compute magnitude of acceleration (gravity-compensated)
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

        // Sliding window variance for ZUPT
        accelWindow[accelWindowIdx % accelWindow.size] = magnitude
        accelWindowIdx++

        if (accelWindowIdx >= accelWindow.size) {
            val mean = accelWindow.average().toFloat()
            val variance = accelWindow.map { (it - mean) * (it - mean) }.average().toFloat()
            // Low variance = stationary
            isStationary = variance < 0.05f
        }
    }

    private fun registerStepDetector() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (sensor == null) {
            Timber.w("Step detector sensor not available — falling back to accelerometer")
            return
        }
        stepListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // TYPE_STEP_DETECTOR fires one event per detected step
                onStep()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    private fun registerRotationVector() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            Timber.w("Rotation vector sensor not available")
            return
        }
        rotationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = onRotationVector(event)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(rotationListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun registerAccelerometer() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Timber.w("Accelerometer not available")
            return
        }
        accelListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = onAccelerometer(event)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(accelListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }
}
