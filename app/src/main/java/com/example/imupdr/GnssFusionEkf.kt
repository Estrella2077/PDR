package com.example.imupdr

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class GnssFusionResult(
    val point: GPSPoint?,
    val initialized: Boolean,
    val measurementAccepted: Boolean = false
)

class GnssFusionEkf(
    private val config: Config = Config()
) {
    data class Config(
        val defaultGnssSigmaMeters: Double = 12.0,
        val minGnssSigmaMeters: Double = 3.0,
        val maxGnssAccuracyMeters: Double = 60.0,
        val maxMeasurementAgeMs: Long = 12_000L,
        val maxLastKnownAgeMs: Long = 15_000L,
        val processAccelerationSigma: Double = 1.2,
        val initialVelocitySigma: Double = 2.8,
        val pdrDistanceNoiseFactor: Double = 0.45,
        val pdrVelocityBlend: Double = 0.55,
        val pdrVelocityNoise: Double = 0.8,
        val minPdrStepNoiseMeters: Double = 0.35,
        val innovationScale: Double = 4.5,
        val minInnovationThresholdMeters: Double = 18.0,
        val mahalanobisGateSquared: Double = 25.0,
        val maxPredictionSeconds: Double = 4.0
    )

    private val state = DoubleArray(4)
    private val covariance = DoubleArray(16)

    private var anchorPoint: GPSPoint? = null
    private var anchorXY: OutputXY? = null
    private var initialized = false
    private var lastTimestampMs = 0L

    fun reset() {
        state.fill(0.0)
        covariance.fill(0.0)
        initialized = false
        lastTimestampMs = 0L
    }

    fun setAnchor(point: GPSPoint?) {
        if (point == null) {
            anchorPoint = null
            anchorXY = null
            reset()
            return
        }
        val changed = anchorPoint?.lat != point.lat || anchorPoint?.lon != point.lon
        anchorPoint = GPSPoint(point.lat, point.lon)
        anchorXY = Transer.BL2XY(point.lat, point.lon)
        if (changed) reset()
    }

    fun hasAnchor(): Boolean = anchorXY != null

    fun currentPoint(): GPSPoint? {
        if (!initialized) return null
        val anchor = anchorXY ?: return null
        return Transer.XY2BL(anchor.x + state[0], anchor.y + state[1], anchor.n)
    }

    fun processGnss(
        point: GPSPoint,
        accuracyMeters: Float,
        timestampMs: Long,
        isLastKnown: Boolean,
        measurementAgeMs: Long
    ): GnssFusionResult {
        if (measurementAgeMs > config.maxMeasurementAgeMs) {
            return GnssFusionResult(currentPoint(), initialized, measurementAccepted = false)
        }
        if (isLastKnown && measurementAgeMs > config.maxLastKnownAgeMs) {
            return GnssFusionResult(currentPoint(), initialized, measurementAccepted = false)
        }
        if (accuracyMeters.isFinite() && accuracyMeters > config.maxGnssAccuracyMeters) {
            return GnssFusionResult(currentPoint(), initialized, measurementAccepted = false)
        }
        if (!hasAnchor()) {
            setAnchor(point)
        }
        val localObservation = toLocal(point) ?: return GnssFusionResult(currentPoint(), initialized, measurementAccepted = false)
        val sigmaMeters = sanitizeGnssSigma(accuracyMeters)
        val safeTimestampMs = timestampMs.coerceAtLeast(0L)

        if (!initialized) {
            initialize(localObservation.x, localObservation.y, sigmaMeters, safeTimestampMs)
            return GnssFusionResult(currentPoint(), initialized = true, measurementAccepted = true)
        }

        predictTo(safeTimestampMs)

        val residualX = localObservation.x - state[0]
        val residualY = localObservation.y - state[1]
        val residualDistanceMeters = hypot(residualX, residualY)
        val measurementVariance = sigmaMeters * sigmaMeters

        val s00 = covariance[indexOf(0, 0)] + measurementVariance
        val s01 = covariance[indexOf(0, 1)]
        val s10 = covariance[indexOf(1, 0)]
        val s11 = covariance[indexOf(1, 1)] + measurementVariance
        val determinant = s00 * s11 - s01 * s10
        if (determinant <= 1e-9) {
            return GnssFusionResult(currentPoint(), initialized, measurementAccepted = false)
        }

        val invS00 = s11 / determinant
        val invS01 = -s01 / determinant
        val invS10 = -s10 / determinant
        val invS11 = s00 / determinant
        val mahalanobisSquared = residualX * (invS00 * residualX + invS01 * residualY) +
            residualY * (invS10 * residualX + invS11 * residualY)
        val innovationLimitMeters = max(config.minInnovationThresholdMeters, sigmaMeters * config.innovationScale)
        if (mahalanobisSquared > config.mahalanobisGateSquared && residualDistanceMeters > innovationLimitMeters) {
            return GnssFusionResult(currentPoint(), initialized, measurementAccepted = false)
        }

        val previousCovariance = covariance.copyOf()
        val k00 = previousCovariance[indexOf(0, 0)] * invS00 + previousCovariance[indexOf(0, 1)] * invS10
        val k01 = previousCovariance[indexOf(0, 0)] * invS01 + previousCovariance[indexOf(0, 1)] * invS11
        val k10 = previousCovariance[indexOf(1, 0)] * invS00 + previousCovariance[indexOf(1, 1)] * invS10
        val k11 = previousCovariance[indexOf(1, 0)] * invS01 + previousCovariance[indexOf(1, 1)] * invS11
        val k20 = previousCovariance[indexOf(2, 0)] * invS00 + previousCovariance[indexOf(2, 1)] * invS10
        val k21 = previousCovariance[indexOf(2, 0)] * invS01 + previousCovariance[indexOf(2, 1)] * invS11
        val k30 = previousCovariance[indexOf(3, 0)] * invS00 + previousCovariance[indexOf(3, 1)] * invS10
        val k31 = previousCovariance[indexOf(3, 0)] * invS01 + previousCovariance[indexOf(3, 1)] * invS11

        state[0] += k00 * residualX + k01 * residualY
        state[1] += k10 * residualX + k11 * residualY
        state[2] += k20 * residualX + k21 * residualY
        state[3] += k30 * residualX + k31 * residualY

        val gains = arrayOf(
            doubleArrayOf(k00, k01),
            doubleArrayOf(k10, k11),
            doubleArrayOf(k20, k21),
            doubleArrayOf(k30, k31)
        )
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                val correction = gains[row][0] * previousCovariance[indexOf(0, column)] +
                    gains[row][1] * previousCovariance[indexOf(1, column)]
                covariance[indexOf(row, column)] = previousCovariance[indexOf(row, column)] - correction
            }
        }
        symmetrizeCovariance()

        return GnssFusionResult(currentPoint(), initialized = true, measurementAccepted = true)
    }

    fun processPdrStep(
        deltaXMeters: Double,
        deltaYMeters: Double,
        timestampMs: Long
    ): GnssFusionResult {
        if (!initialized || !hasAnchor()) {
            return GnssFusionResult(currentPoint(), initialized, measurementAccepted = false)
        }
        val deltaSeconds = predictTo(timestampMs.coerceAtLeast(0L))
        state[0] += deltaXMeters
        state[1] += deltaYMeters

        if (deltaSeconds > 1e-3) {
            val observedVelocityX = deltaXMeters / deltaSeconds
            val observedVelocityY = deltaYMeters / deltaSeconds
            state[2] = state[2] * (1.0 - config.pdrVelocityBlend) + observedVelocityX * config.pdrVelocityBlend
            state[3] = state[3] * (1.0 - config.pdrVelocityBlend) + observedVelocityY * config.pdrVelocityBlend
        }

        val stepDistanceMeters = hypot(deltaXMeters, deltaYMeters)
        val stepNoiseMeters = max(config.minPdrStepNoiseMeters, stepDistanceMeters * config.pdrDistanceNoiseFactor)
        covariance[indexOf(0, 0)] += stepNoiseMeters * stepNoiseMeters
        covariance[indexOf(1, 1)] += stepNoiseMeters * stepNoiseMeters
        covariance[indexOf(2, 2)] += config.pdrVelocityNoise * config.pdrVelocityNoise
        covariance[indexOf(3, 3)] += config.pdrVelocityNoise * config.pdrVelocityNoise
        symmetrizeCovariance()

        return GnssFusionResult(currentPoint(), initialized = true, measurementAccepted = false)
    }

    private fun initialize(xMeters: Double, yMeters: Double, sigmaMeters: Double, timestampMs: Long) {
        state[0] = xMeters
        state[1] = yMeters
        state[2] = 0.0
        state[3] = 0.0
        covariance.fill(0.0)
        covariance[indexOf(0, 0)] = sigmaMeters * sigmaMeters
        covariance[indexOf(1, 1)] = sigmaMeters * sigmaMeters
        covariance[indexOf(2, 2)] = config.initialVelocitySigma * config.initialVelocitySigma
        covariance[indexOf(3, 3)] = config.initialVelocitySigma * config.initialVelocitySigma
        lastTimestampMs = timestampMs
        initialized = true
    }

    private fun predictTo(timestampMs: Long): Double {
        if (!initialized) {
            lastTimestampMs = timestampMs
            return 0.0
        }
        if (lastTimestampMs == 0L) {
            lastTimestampMs = timestampMs
            return 0.0
        }
        val rawDeltaSeconds = ((timestampMs - lastTimestampMs).coerceAtLeast(0L)) / 1000.0
        lastTimestampMs = timestampMs
        if (rawDeltaSeconds <= 0.0) {
            return 0.0
        }
        val deltaSeconds = rawDeltaSeconds.coerceAtMost(config.maxPredictionSeconds)
        state[0] += state[2] * deltaSeconds
        state[1] += state[3] * deltaSeconds

        val previousCovariance = covariance.copyOf()
        val stateTransition = doubleArrayOf(
            1.0, 0.0, deltaSeconds, 0.0,
            0.0, 1.0, 0.0, deltaSeconds,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        )
        val predictedCovariance = multiply4x4(multiply4x4(stateTransition, previousCovariance), transpose4x4(stateTransition))
        val processVariance = config.processAccelerationSigma * config.processAccelerationSigma
        val delta2 = deltaSeconds * deltaSeconds
        val delta3 = delta2 * deltaSeconds
        val delta4 = delta2 * delta2
        val processNoise = doubleArrayOf(
            0.25 * delta4 * processVariance, 0.0, 0.5 * delta3 * processVariance, 0.0,
            0.0, 0.25 * delta4 * processVariance, 0.0, 0.5 * delta3 * processVariance,
            0.5 * delta3 * processVariance, 0.0, delta2 * processVariance, 0.0,
            0.0, 0.5 * delta3 * processVariance, 0.0, delta2 * processVariance
        )
        for (index in covariance.indices) {
            covariance[index] = predictedCovariance[index] + processNoise[index]
        }
        symmetrizeCovariance()
        return deltaSeconds
    }

    private fun sanitizeGnssSigma(accuracyMeters: Float): Double {
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0f) {
            return config.defaultGnssSigmaMeters
        }
        return accuracyMeters.toDouble().coerceIn(config.minGnssSigmaMeters, config.maxGnssAccuracyMeters)
    }

    private fun toLocal(point: GPSPoint): OutputXY? {
        val anchor = anchorXY ?: return null
        val pointXY = Transer.BL2XY(point.lat, point.lon)
        return OutputXY().apply {
            x = pointXY.x - anchor.x
            y = pointXY.y - anchor.y
            n = anchor.n
        }
    }

    private fun symmetrizeCovariance() {
        for (row in 0 until 4) {
            for (column in row + 1 until 4) {
                val average = (covariance[indexOf(row, column)] + covariance[indexOf(column, row)]) * 0.5
                covariance[indexOf(row, column)] = average
                covariance[indexOf(column, row)] = average
            }
            covariance[indexOf(row, row)] = covariance[indexOf(row, row)].coerceAtLeast(1e-6)
        }
    }

    private fun multiply4x4(left: DoubleArray, right: DoubleArray): DoubleArray {
        val result = DoubleArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                var value = 0.0
                for (middle in 0 until 4) {
                    value += left[indexOf(row, middle)] * right[indexOf(middle, column)]
                }
                result[indexOf(row, column)] = value
            }
        }
        return result
    }

    private fun transpose4x4(matrix: DoubleArray): DoubleArray {
        val result = DoubleArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                result[indexOf(row, column)] = matrix[indexOf(column, row)]
            }
        }
        return result
    }

    private fun indexOf(row: Int, column: Int): Int = row * 4 + column
}
