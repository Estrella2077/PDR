package com.example.imupdr

data class PdrModelConfig(
    val displayName: String,
    val gravityAlpha: Float,
    val motionAlpha: Float,
    val headingWeight: Float,
    val ahrsProportionalGain: Float,
    val ahrsIntegralGain: Float,
    val minStepIntervalMs: Long,
    val maxStepIntervalMs: Long,
    val stepPeakThreshold: Float,
    val stepAmplitudeThreshold: Float,
    val stepModelHeightMeters: Float,
    val stepLengthScale: Float
)

enum class PdrModelPreset(val config: PdrModelConfig) {
    ROBUST(
        PdrModelConfig(
            displayName = "稳健模型",
            gravityAlpha = 0.92f,
            motionAlpha = 0.78f,
            headingWeight = 0.14f,
            ahrsProportionalGain = 1.45f,
            ahrsIntegralGain = 0.035f,
            minStepIntervalMs = 320L,
            maxStepIntervalMs = 1_150L,
            stepPeakThreshold = 1.02f,
            stepAmplitudeThreshold = 0.70f,
            stepModelHeightMeters = 1.75f,
            stepLengthScale = 0.67f
        )
    ),
    STANDARD(
        PdrModelConfig(
            displayName = "标准模型",
            gravityAlpha = 0.90f,
            motionAlpha = 0.70f,
            headingWeight = 0.18f,
            ahrsProportionalGain = 1.60f,
            ahrsIntegralGain = 0.04f,
            minStepIntervalMs = 280L,
            maxStepIntervalMs = 1_100L,
            stepPeakThreshold = 0.92f,
            stepAmplitudeThreshold = 0.55f,
            stepModelHeightMeters = 1.75f,
            stepLengthScale = 0.67f
        )
    ),
    SENSITIVE(
        PdrModelConfig(
            displayName = "灵敏模型",
            gravityAlpha = 0.86f,
            motionAlpha = 0.58f,
            headingWeight = 0.22f,
            ahrsProportionalGain = 1.78f,
            ahrsIntegralGain = 0.05f,
            minStepIntervalMs = 240L,
            maxStepIntervalMs = 1_050L,
            stepPeakThreshold = 0.80f,
            stepAmplitudeThreshold = 0.42f,
            stepModelHeightMeters = 1.75f,
            stepLengthScale = 0.67f
        )
    );

    override fun toString(): String = config.displayName
}

fun createHeightModelConfig(heightCm: Float, stepLengthScale: Float = PdrModelPreset.STANDARD.config.stepLengthScale): PdrModelConfig {
    val safeHeightCm = heightCm.coerceIn(120f, 220f)
    val safeScale = stepLengthScale.coerceIn(0.30f, 1.50f)
    val base = PdrModelPreset.STANDARD.config
    return base.copy(
        displayName = "身高 ${safeHeightCm.toInt()} cm",
        stepModelHeightMeters = safeHeightCm / 100.0f,
        stepLengthScale = safeScale
    )
}
