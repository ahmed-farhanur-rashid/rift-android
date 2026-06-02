package com.rift.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import timber.log.Timber

/**
 * Base class for all four ONNX expert models.
 *
 * Loads the model from assets/models/<modelFileName> once at construction.
 * The OrtSession is held open for the app lifetime — creation is expensive
 * (~50–200ms); inference per call is < 5ms on Cortex-A55.
 *
 * [env] and [session] are `internal` so subclasses in the same module can
 * call inference methods directly without reflection.
 */
abstract class OnnxModel(context: Context, modelFileName: String) {

    internal val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    internal val session: OrtSession

    init {
        val modelBytes = context.assets.open("models/$modelFileName").readBytes()
        val options = OrtSession.SessionOptions().apply {
            // 2 threads: sweet spot for small models on Cortex-A55.
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelBytes, options)
        Timber.d("OnnxModel[$modelFileName]: session ready. Inputs: ${session.inputNames}")
    }

    /**
     * Wraps a FloatArray into an OnnxTensor with shape [1, values.size].
     * The [1] is the batch dimension required by all our exported models.
     */
    internal fun floatTensor(values: FloatArray): OnnxTensor =
        OnnxTensor.createTensor(env, arrayOf(values))

    /** Must be called when the enclosing scope (app process) is being torn down. */
    fun close() {
        runCatching { session.close() }
        runCatching { env.close() }
    }
}
