package com.rift.core.ml.experts

import android.content.Context
import com.rift.core.data.ApReading
import com.rift.core.ml.FeatureVectorBuilder
import com.rift.core.ml.OnnxModel
import com.rift.core.ml.OuiLookup
import com.rift.core.ml.RiskReason
import com.rift.core.ml.RiskScore
import com.rift.core.ml.RssiTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Expert 2 — Risk Scorer.
 *
 * Assigns a continuous risk score [0,1] to every visible AP based on observable
 * properties. Always runs — gate condition is unconditional.
 *
 * Model: GradientBoostingRegressor (sklearn) exported via skl2onnx.
 * Input: 7-feature vector per AP.
 * Output: single float [0,1] — higher = riskier.
 *
 * Fallback: rule-based score using the same logic as the training data generator
 * when the ONNX model is not yet available.
 */
@Singleton
class RiskScorer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rssiTracker: RssiTracker,
    private val ouiLookup: OuiLookup
) {
    companion object {
        private const val MODEL_FILE = "risk_scorer_int8.onnx"
    }

    private val model: InternalModel? = try {
        InternalModel(context)
    } catch (e: Exception) {
        Timber.w("RiskScorer: model not found ($MODEL_FILE) — using rule-based fallback")
        null
    }

    /** Score every AP in the current scan. Always returns a score for every reading. */
    fun scoreAll(readings: List<ApReading>): List<RiskScore> =
        readings.map { ap -> scoreOne(ap) }

    private fun scoreOne(ap: ApReading): RiskScore {
        val fv = FeatureVectorBuilder.build(ap, rssiTracker, ouiLookup)
        val features = floatArrayOf(
            fv.encryptionScore,
            fv.hasWps,
            fv.usesTkip,
            fv.vendorRiskScore,
            fv.rssiNorm,
            fv.rssiVariance,
            fv.isHidden
        )

        val rawScore = model?.infer(features) ?: ruleBasedScore(features)
        val score = rawScore.coerceIn(0f, 1f)
        val reason = derivePrimaryReason(fv, score)

        return RiskScore(
            bssid = ap.bssid,
            ssid = ap.ssid,
            score = score,
            primaryReason = reason
        )
    }

    /**
     * Derives the single most significant risk reason by inspecting which input
     * feature deviates most from the low-risk baseline. This is layered on top of
     * the model score — not a separate model output.
     */
    private fun derivePrimaryReason(
        fv: com.rift.core.ml.ApFeatureVector,
        score: Float
    ): RiskReason {
        if (score < 0.15f) return RiskReason.CLEAN
        return when {
            fv.encryptionScore < 0.05f              -> RiskReason.OPEN_NETWORK
            fv.encryptionScore < 0.55f && fv.usesTkip > 0.5f -> RiskReason.WEAK_ENCRYPTION
            fv.hasWps > 0.5f                        -> RiskReason.WPS_ENABLED
            fv.vendorRiskScore > 0.55f              -> RiskReason.VENDOR_VULNERABLE
            fv.rssiVariance > 0.3f                  -> RiskReason.UNSTABLE_SIGNAL
            fv.isHidden > 0.5f                      -> RiskReason.HIDDEN_SSID
            fv.encryptionScore < 0.55f              -> RiskReason.WEAK_ENCRYPTION
            else                                    -> RiskReason.CLEAN
        }
    }

    /**
     * Rule-based fallback that mirrors the training data generator in
     * ml/training/02_risk_scorer_train.py
     */
    private fun ruleBasedScore(features: FloatArray): Float {
        if (features.size < 7) return 0.5f
        val encScore = features[0]
        val hasWps = features[1]
        val usesTkip = features[2]
        val vendorRisk = features[3]
        val rssiVar = features[5]
        val isHidden = features[6]
        var base = 1.0f - encScore
        base += hasWps * 0.15f
        base += usesTkip * 0.10f
        base += vendorRisk * 0.10f
        base += rssiVar * 0.05f
        base += isHidden * 0.05f
        return base.coerceIn(0f, 1f)
    }

    private class InternalModel(context: Context) : OnnxModel(context, MODEL_FILE) {
        fun infer(features: FloatArray): Float {
            val tensor = floatTensor(features)
            return try {
                val results = session.run(mapOf("float_input" to tensor))
                // sklearn regressor output: variable node, shape [1, 1]
                val score = ((results[0].value as Array<FloatArray>)[0])[0]
                results.close()
                score
            } finally {
                tensor.close()
            }
        }
    }
}

private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]
private operator fun FloatArray.component5() = this[4]
private operator fun FloatArray.component6() = this[5]
private operator fun FloatArray.component7() = this[6]
