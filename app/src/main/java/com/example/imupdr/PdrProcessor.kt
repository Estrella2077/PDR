package com.example.imupdr

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class PdrState(
    val headingRad: Float = 0.0f,
    val headingReady: Boolean = false,
    val pitchRad: Float = 0.0f,
    val rollRad: Float = 0.0f,
    val positionXMeters: Float = 0.0f,
    val positionYMeters: Float = 0.0f,
    val totalDistanceMeters: Float = 0.0f,
    val steps: Int = 0,
    val latestStepLengthMeters: Float = 0.0f,
    val filteredMotion: Float = 0.0f
)

data class StepUpdate(
    val timestampMs: Long,
    val stepIndex: Int,
    val headingRad: Float,
    val stepLengthMeters: Float,
    val xMeters: Float,
    val yMeters: Float,
    val totalDistanceMeters: Float,
    val filteredMotion: Float,
    val peakMotion: Float
)

private data class MotionSample(
    val timestampMs: Long,
    val magnitude: Float
)

class PdrProcessor {
    private var ahrsEstimator = AhrsEstimator()
    private val gravity = FloatArray(3)
    private val motionSamples = ArrayDeque<MotionSample>()
    private val recentStepIntervalsMs = ArrayDeque<Long>()

    private var hasAccelerometer = false
    private var headingRad = 0.0f
    private var headingReady = false
    private var pitchRad = 0.0f
    private var rollRad = 0.0f
    private var magneticDeclinationRad = 0.0f
    private var externalHeadingRad = 0.0f
    private var externalHeadingReady = false
    private var externalPitchRad = 0.0f
    private var externalRollRad = 0.0f
    private var filteredMotion = 0.0f
    private var valleyMotion = Float.MAX_VALUE
    private var lastStepTimestampMs = 0L
    private var previousStepIntervalMs = 0L
    private var positionXMeters = 0.0f
    private var positionYMeters = 0.0f
    private var totalDistanceMeters = 0.0f
    private var steps = 0
    private var latestStepLengthMeters = 0.0f
    private var modelConfig = PdrModelPreset.STANDARD.config

    fun setModelPreset(preset: PdrModelPreset) {
        modelConfig = preset.config
    }

    fun setModelConfig(config: PdrModelConfig) {
        modelConfig = config
        ahrsEstimator = AhrsEstimator(config.ahrsProportionalGain, config.ahrsIntegralGain)
    }

    fun getModelPresetName(): String {
        return modelConfig.displayName
    }

    fun getModelConfig(): PdrModelConfig {
        return modelConfig.copy()
    }

    fun reset() {
        gravity.fill(0.0f)
        motionSamples.clear()
        recentStepIntervalsMs.clear()
        ahrsEstimator.reset()
        hasAccelerometer = false
        headingRad = 0.0f
        headingReady = false
        pitchRad = 0.0f
        rollRad = 0.0f
        externalHeadingRad = 0.0f
        externalHeadingReady = false
        externalPitchRad = 0.0f
        externalRollRad = 0.0f
        filteredMotion = 0.0f
        valleyMotion = Float.MAX_VALUE
        lastStepTimestampMs = 0L
        previousStepIntervalMs = 0L
        positionXMeters = 0.0f
        positionYMeters = 0.0f
        totalDistanceMeters = 0.0f
        steps = 0
        latestStepLengthMeters = 0.0f
    }

    fun setReferenceLocation(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double = 0.0,
        timeMillis: Long = System.currentTimeMillis()
    ) {
        magneticDeclinationRad = GeomagneticHelper.declinationRadians(latitude, longitude, altitudeMeters, timeMillis)
    }

    fun updateMagnetometer(values: FloatArray) {
        ahrsEstimator.updateMagnetometer(values)
        updateAttitude()
    }

    fun setExternalAttitude(
        headingRad: Float,
        headingReady: Boolean,
        pitchRad: Float,
        rollRad: Float
    ) {
        externalHeadingRad = AngleUtils.normalizeRadians(headingRad)
        externalHeadingReady = headingReady
        externalPitchRad = pitchRad
        externalRollRad = rollRad
    }

    fun updateGyroscope(values: FloatArray, timestampNs: Long) {
        ahrsEstimator.updateGyroscope(values, timestampNs)
        updateAttitude()
    }

    fun updateAccelerometer(values: FloatArray, timestampMs: Long, timestampNs: Long): StepUpdate? {
        for (i in 0..2) {
            gravity[i] = if (hasAccelerometer) {
                modelConfig.gravityAlpha * gravity[i] + (1.0f - modelConfig.gravityAlpha) * values[i]
            } else {
                values[i]
            }
        }
        hasAccelerometer = true
        ahrsEstimator.updateAccelerometer(values)
        updateAttitude()

        val linearX = values[0] - gravity[0]
        val linearY = values[1] - gravity[1]
        val linearZ = values[2] - gravity[2]
        val motion = sqrt(linearX * linearX + linearY * linearY + linearZ * linearZ)
        filteredMotion = if (motionSamples.isEmpty()) {
            motion
        } else {
            modelConfig.motionAlpha * filteredMotion + (1.0f - modelConfig.motionAlpha) * motion
        }

        if (valleyMotion == Float.MAX_VALUE) {
            valleyMotion = filteredMotion
        } else {
            valleyMotion = min(valleyMotion, filteredMotion)
        }

        motionSamples.addLast(MotionSample(timestampMs, filteredMotion))
        while (motionSamples.size > 3) {
            motionSamples.removeFirst()
        }

        if (motionSamples.size < 3) {
            return null
        }

        val previous = motionSamples.elementAt(0)
        val current = motionSamples.elementAt(1)
        val next = motionSamples.elementAt(2)
        val timeSinceLastStep = current.timestampMs - lastStepTimestampMs
        val isPeak = current.magnitude > previous.magnitude && current.magnitude >= next.magnitude
        val amplitude = current.magnitude - valleyMotion

        if (!headingReady || !isPeak || timeSinceLastStep < modelConfig.minStepIntervalMs) {
            if (next.magnitude < valleyMotion) {
                valleyMotion = next.magnitude
            }
            return null
        }

        if (timeSinceLastStep > modelConfig.maxStepIntervalMs && lastStepTimestampMs != 0L) {
            previousStepIntervalMs = 0L
            recentStepIntervalsMs.clear()
        }

        if (current.magnitude < modelConfig.stepPeakThreshold || amplitude < modelConfig.stepAmplitudeThreshold) {
            if (next.magnitude < valleyMotion) {
                valleyMotion = next.magnitude
            }
            return null
        }

        if (!isCadenceConsistent(timeSinceLastStep)) {
            if (next.magnitude < valleyMotion) {
                valleyMotion = next.magnitude
            }
            return null
        }

        val stepLengthMeters = estimateStepLength(timeSinceLastStep)
        steps += 1
        latestStepLengthMeters = stepLengthMeters
        totalDistanceMeters += stepLengthMeters
        positionXMeters += (stepLengthMeters * cos(headingRad))
        positionYMeters += (stepLengthMeters * sin(headingRad))
        if (lastStepTimestampMs != 0L) {
            previousStepIntervalMs = timeSinceLastStep
            recentStepIntervalsMs.addLast(timeSinceLastStep)
            while (recentStepIntervalsMs.size > 4) {
                recentStepIntervalsMs.removeFirst()
            }
        }
        lastStepTimestampMs = current.timestampMs
        valleyMotion = next.magnitude

        return StepUpdate(
            timestampMs = current.timestampMs,
            stepIndex = steps,
            headingRad = headingRad,
            stepLengthMeters = stepLengthMeters,
            xMeters = positionXMeters,
            yMeters = positionYMeters,
            totalDistanceMeters = totalDistanceMeters,
            filteredMotion = filteredMotion,
            peakMotion = current.magnitude
        )
    }

    fun snapshot(): PdrState {
        return PdrState(
            headingRad = headingRad,
            headingReady = headingReady,
            pitchRad = pitchRad,
            rollRad = rollRad,
            positionXMeters = positionXMeters,
            positionYMeters = positionYMeters,
            totalDistanceMeters = totalDistanceMeters,
            steps = steps,
            latestStepLengthMeters = latestStepLengthMeters,
            filteredMotion = filteredMotion
        )
    }

    private fun updateAttitude() {
        if (externalHeadingReady) {
            headingRad = if (headingReady) {
                AngleUtils.smoothAngle(headingRad, externalHeadingRad, modelConfig.headingWeight)
            } else {
                externalHeadingRad
            }
            pitchRad = externalPitchRad
            rollRad = externalRollRad
            headingReady = true
            return
        }
        val sample = ahrsEstimator.snapshot()
        if (!sample.ready) {
            headingReady = false
            return
        }
        val measuredHeading = AngleUtils.normalizeRadians(sample.yawRad + magneticDeclinationRad)
        headingRad = if (headingReady) {
            AngleUtils.smoothAngle(headingRad, measuredHeading, modelConfig.headingWeight)
        } else {
            measuredHeading
        }
        pitchRad = sample.pitchRad
        rollRad = sample.rollRad
        headingReady = true
    }

    private fun isCadenceConsistent(intervalMs: Long): Boolean {
        if (lastStepTimestampMs == 0L || previousStepIntervalMs == 0L) {
            return true
        }
        val intervalDelta = kotlin.math.abs(intervalMs - previousStepIntervalMs)
        return intervalDelta <= 360L || intervalMs in modelConfig.minStepIntervalMs..modelConfig.maxStepIntervalMs
    }

    private fun estimateStepLength(intervalMs: Long): Float {
        val currentFrequencyHz = if (intervalMs > 0L) 1000.0f / intervalMs else 0.0f
        val previousFrequencyHz = if (previousStepIntervalMs > 0L) 1000.0f / previousStepIntervalMs else currentFrequencyHz
        val stepFrequencyHz = if (previousStepIntervalMs > 0L) {
            currentFrequencyHz * 0.65f + previousFrequencyHz * 0.35f
        } else {
            currentFrequencyHz
        }
        val heightMeters = modelConfig.stepModelHeightMeters
        return (
            PDR_MAIN_STEP_BASE_METERS +
                PDR_MAIN_HEIGHT_COEFFICIENT * (heightMeters - PDR_MAIN_REFERENCE_HEIGHT_METERS) +
                PDR_MAIN_FREQUENCY_COEFFICIENT * (stepFrequencyHz - PDR_MAIN_REFERENCE_FREQUENCY_HZ) * heightMeters / PDR_MAIN_REFERENCE_HEIGHT_METERS
            ) * modelConfig.stepLengthScale
    }

    private companion object {
        private const val PDR_MAIN_STEP_BASE_METERS = 0.7f
        private const val PDR_MAIN_HEIGHT_COEFFICIENT = 0.371f
        private const val PDR_MAIN_FREQUENCY_COEFFICIENT = 0.227f
        private const val PDR_MAIN_REFERENCE_HEIGHT_METERS = 1.75f
        private const val PDR_MAIN_REFERENCE_FREQUENCY_HZ = 1.79f
    }
}
