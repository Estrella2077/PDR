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
    val stepBaseLengthMeters: Float,
    val stepFrequencyScale: Float,
    val minStepLengthMeters: Float,
    val maxStepLengthMeters: Float
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
            stepBaseLengthMeters = 0.66f,
            stepFrequencyScale = 0.22f,
            minStepLengthMeters = 0.32f,
            maxStepLengthMeters = 0.88f
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
            stepBaseLengthMeters = 0.70f,
            stepFrequencyScale = 0.24f,
            minStepLengthMeters = 0.35f,
            maxStepLengthMeters = 0.95f
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
            stepBaseLengthMeters = 0.73f,
            stepFrequencyScale = 0.26f,
            minStepLengthMeters = 0.34f,
            maxStepLengthMeters = 1.02f
        )
    );

    override fun toString(): String = config.displayName
}

fun createHeightModelConfig(heightCm: Float): PdrModelConfig {
    val safeHeightCm = heightCm.coerceIn(120f, 220f)
    val scale = safeHeightCm / 175f
    val base = PdrModelPreset.STANDARD.config
    return base.copy(
        displayName = "身高 ${safeHeightCm.toInt()} cm",
        stepBaseLengthMeters = (base.stepBaseLengthMeters + (scale - 1.0f) * 0.22f).coerceIn(0.52f, 0.92f),
        stepFrequencyScale = (base.stepFrequencyScale * (0.88f + 0.12f * scale)).coerceIn(0.16f, 0.34f),
        minStepLengthMeters = (base.minStepLengthMeters * scale).coerceIn(0.28f, 0.60f),
        maxStepLengthMeters = (base.maxStepLengthMeters * scale).coerceIn(0.75f, 1.20f)
    )
}
