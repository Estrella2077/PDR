package com.example.imupdr

import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

data class AttitudeSample(
    val yawRad: Float,
    val pitchRad: Float,
    val rollRad: Float,
    val ready: Boolean
)

class AhrsEstimator(
    private val proportionalGain: Float = 1.6f,
    private val integralGain: Float = 0.04f
) {
    private val acceleration = FloatArray(3)
    private val magnetometer = FloatArray(3)
    private val gyroscope = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val initializationAccelerationSum = FloatArray(3)
    private val initializationMagnetometerSum = FloatArray(3)
    private val previousInitializationAcceleration = FloatArray(3)
    private val previousInitializationMagnetometer = FloatArray(3)

    private var hasAcceleration = false
    private var hasMagnetometer = false
    private var hasGyroscope = false
    private var quaternionW = 1.0f
    private var quaternionX = 0.0f
    private var quaternionY = 0.0f
    private var quaternionZ = 0.0f
    private var integralErrorX = 0.0f
    private var integralErrorY = 0.0f
    private var integralErrorZ = 0.0f
    private var lastGyroscopeTimestampNs = 0L
    private var ready = false
    private var initializationSampleCount = 0
    private var hasPreviousInitializationSample = false

    fun reset() {
        acceleration.fill(0.0f)
        magnetometer.fill(0.0f)
        gyroscope.fill(0.0f)
        initializationAccelerationSum.fill(0.0f)
        initializationMagnetometerSum.fill(0.0f)
        previousInitializationAcceleration.fill(0.0f)
        previousInitializationMagnetometer.fill(0.0f)
        hasAcceleration = false
        hasMagnetometer = false
        hasGyroscope = false
        quaternionW = 1.0f
        quaternionX = 0.0f
        quaternionY = 0.0f
        quaternionZ = 0.0f
        integralErrorX = 0.0f
        integralErrorY = 0.0f
        integralErrorZ = 0.0f
        lastGyroscopeTimestampNs = 0L
        ready = false
        initializationSampleCount = 0
        hasPreviousInitializationSample = false
    }

    fun updateAccelerometer(values: FloatArray) {
        acceleration[0] = values[0]
        acceleration[1] = values[1]
        acceleration[2] = values[2]
        hasAcceleration = true
        ensureInitializedFromAccMag()
    }

    fun updateMagnetometer(values: FloatArray) {
        magnetometer[0] = values[0]
        magnetometer[1] = values[1]
        magnetometer[2] = values[2]
        hasMagnetometer = true
        ensureInitializedFromAccMag()
    }

    fun updateGyroscope(values: FloatArray, timestampNs: Long) {
        gyroscope[0] = values[0]
        gyroscope[1] = values[1]
        gyroscope[2] = values[2]
        hasGyroscope = true
        if (!hasAcceleration || !hasMagnetometer) {
            return
        }
        ensureInitializedFromAccMag()
        if (!ready) {
            return
        }

        if (lastGyroscopeTimestampNs == 0L) {
            lastGyroscopeTimestampNs = timestampNs
            return
        }

        val deltaSeconds = ((timestampNs - lastGyroscopeTimestampNs).coerceAtLeast(0L) / 1_000_000_000.0f)
            .coerceIn(0.0f, MAX_DELTA_SECONDS)
        lastGyroscopeTimestampNs = timestampNs
        if (deltaSeconds <= 0.0f) {
            return
        }

        val ax = acceleration[0]
        val ay = acceleration[1]
        val az = acceleration[2]
        val mx = magnetometer[0]
        val my = magnetometer[1]
        val mz = magnetometer[2]

        val accNorm = sqrt(ax * ax + ay * ay + az * az)
        val magNorm = sqrt(mx * mx + my * my + mz * mz)
        if (accNorm < 1e-6f || magNorm < 1e-6f) {
            return
        }

        val axn = ax / accNorm
        val ayn = ay / accNorm
        val azn = az / accNorm
        val mxn = mx / magNorm
        val myn = my / magNorm
        val mzn = mz / magNorm

        val q0 = quaternionW
        val q1 = quaternionX
        val q2 = quaternionY
        val q3 = quaternionZ

        val hx = 2.0f * mxn * (0.5f - q2 * q2 - q3 * q3) +
            2.0f * myn * (q1 * q2 - q0 * q3) +
            2.0f * mzn * (q1 * q3 + q0 * q2)
        val hy = 2.0f * mxn * (q1 * q2 + q0 * q3) +
            2.0f * myn * (0.5f - q1 * q1 - q3 * q3) +
            2.0f * mzn * (q2 * q3 - q0 * q1)
        val bx = sqrt(hx * hx + hy * hy)
        val bz = 2.0f * mxn * (q1 * q3 - q0 * q2) +
            2.0f * myn * (q2 * q3 + q0 * q1) +
            2.0f * mzn * (0.5f - q1 * q1 - q2 * q2)

        val vx = 2.0f * (q1 * q3 - q0 * q2)
        val vy = 2.0f * (q0 * q1 + q2 * q3)
        val vz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3

        val wx = 2.0f * bx * (0.5f - q2 * q2 - q3 * q3) + 2.0f * bz * (q1 * q3 - q0 * q2)
        val wy = 2.0f * bx * (q1 * q2 - q0 * q3) + 2.0f * bz * (q0 * q1 + q2 * q3)
        val wz = 2.0f * bx * (q0 * q2 + q1 * q3) + 2.0f * bz * (0.5f - q1 * q1 - q2 * q2)

        val errorX = (ayn * vz - azn * vy) + (myn * wz - mzn * wy)
        val errorY = (azn * vx - axn * vz) + (mzn * wx - mxn * wz)
        val errorZ = (axn * vy - ayn * vx) + (mxn * wy - myn * wx)

        integralErrorX += errorX * integralGain * deltaSeconds
        integralErrorY += errorY * integralGain * deltaSeconds
        integralErrorZ += errorZ * integralGain * deltaSeconds

        val correctedGyroX = values[0] + proportionalGain * errorX + integralErrorX
        val correctedGyroY = values[1] + proportionalGain * errorY + integralErrorY
        val correctedGyroZ = values[2] + proportionalGain * errorZ + integralErrorZ

        val halfDt = 0.5f * deltaSeconds
        val newQ0 = q0 + (-q1 * correctedGyroX - q2 * correctedGyroY - q3 * correctedGyroZ) * halfDt
        val newQ1 = q1 + (q0 * correctedGyroX + q2 * correctedGyroZ - q3 * correctedGyroY) * halfDt
        val newQ2 = q2 + (q0 * correctedGyroY - q1 * correctedGyroZ + q3 * correctedGyroX) * halfDt
        val newQ3 = q3 + (q0 * correctedGyroZ + q1 * correctedGyroY - q2 * correctedGyroX) * halfDt
        normalizeAndSetQuaternion(newQ0, newQ1, newQ2, newQ3)
    }

    fun snapshot(): AttitudeSample {
        if (!ready) {
            return AttitudeSample(
                yawRad = 0.0f,
                pitchRad = 0.0f,
                rollRad = 0.0f,
                ready = false
            )
        }

        val standardYaw = atan2(
            2.0f * (quaternionW * quaternionZ + quaternionX * quaternionY),
            1.0f - 2.0f * (quaternionY * quaternionY + quaternionZ * quaternionZ)
        )
        val pitch = asin(
            (2.0f * (quaternionW * quaternionY - quaternionZ * quaternionX)).coerceIn(-1.0f, 1.0f)
        )
        val roll = atan2(
            2.0f * (quaternionW * quaternionX + quaternionY * quaternionZ),
            1.0f - 2.0f * (quaternionX * quaternionX + quaternionY * quaternionY)
        )
        return AttitudeSample(
            yawRad = standardYawToAndroidAzimuth(standardYaw),
            pitchRad = pitch,
            rollRad = roll,
            ready = true
        )
    }

    private fun ensureInitializedFromAccMag() {
        if (ready || !hasAcceleration || !hasMagnetometer) {
            return
        }
        val accNorm = sqrt(acceleration[0] * acceleration[0] + acceleration[1] * acceleration[1] + acceleration[2] * acceleration[2])
        val magNorm = sqrt(magnetometer[0] * magnetometer[0] + magnetometer[1] * magnetometer[1] + magnetometer[2] * magnetometer[2])
        val gyroNorm = if (hasGyroscope) {
            sqrt(gyroscope[0] * gyroscope[0] + gyroscope[1] * gyroscope[1] + gyroscope[2] * gyroscope[2])
        } else {
            0.0f
        }
        val isStaticEnough = accNorm in MIN_INIT_ACCELERATION_NORM..MAX_INIT_ACCELERATION_NORM &&
            magNorm >= MIN_INIT_MAGNETIC_NORM &&
            (!hasGyroscope || gyroNorm <= MAX_INIT_GYRO_NORM) &&
            (!hasPreviousInitializationSample || (
                vectorDistance(acceleration, previousInitializationAcceleration) <= MAX_INIT_ACCELERATION_DELTA &&
                    vectorDistance(magnetometer, previousInitializationMagnetometer) <= MAX_INIT_MAGNETIC_DELTA
                ))

        if (!isStaticEnough) {
            resetInitializationWarmup()
            copyVector(acceleration, previousInitializationAcceleration)
            copyVector(magnetometer, previousInitializationMagnetometer)
            hasPreviousInitializationSample = true
            return
        }

        for (index in 0..2) {
            initializationAccelerationSum[index] += acceleration[index]
            initializationMagnetometerSum[index] += magnetometer[index]
        }
        initializationSampleCount += 1
        copyVector(acceleration, previousInitializationAcceleration)
        copyVector(magnetometer, previousInitializationMagnetometer)
        hasPreviousInitializationSample = true
        if (initializationSampleCount < REQUIRED_INIT_SAMPLES) {
            return
        }

        val averagedAcceleration = FloatArray(3)
        val averagedMagnetometer = FloatArray(3)
        for (index in 0..2) {
            averagedAcceleration[index] = initializationAccelerationSum[index] / initializationSampleCount
            averagedMagnetometer[index] = initializationMagnetometerSum[index] / initializationSampleCount
        }

        if (!SensorManager.getRotationMatrix(rotationMatrix, null, averagedAcceleration, averagedMagnetometer)) {
            return
        }
        SensorManager.getOrientation(rotationMatrix, orientation)
        setQuaternionFromYawPitchRoll(
            androidAzimuthToStandardYaw(orientation[0]),
            orientation[1],
            orientation[2]
        )
        lastGyroscopeTimestampNs = 0L
        ready = true
        resetInitializationWarmup()
    }

    private fun resetInitializationWarmup() {
        initializationAccelerationSum.fill(0.0f)
        initializationMagnetometerSum.fill(0.0f)
        initializationSampleCount = 0
    }

    private fun vectorDistance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun copyVector(source: FloatArray, target: FloatArray) {
        target[0] = source[0]
        target[1] = source[1]
        target[2] = source[2]
    }

    private fun setQuaternionFromYawPitchRoll(yaw: Float, pitch: Float, roll: Float) {
        val halfYaw = yaw * 0.5f
        val halfPitch = pitch * 0.5f
        val halfRoll = roll * 0.5f

        val cy = kotlin.math.cos(halfYaw)
        val sy = kotlin.math.sin(halfYaw)
        val cp = kotlin.math.cos(halfPitch)
        val sp = kotlin.math.sin(halfPitch)
        val cr = kotlin.math.cos(halfRoll)
        val sr = kotlin.math.sin(halfRoll)

        val q0 = cr * cp * cy + sr * sp * sy
        val q1 = sr * cp * cy - cr * sp * sy
        val q2 = cr * sp * cy + sr * cp * sy
        val q3 = cr * cp * sy - sr * sp * cy
        normalizeAndSetQuaternion(q0, q1, q2, q3)
    }

    private fun normalizeAndSetQuaternion(q0: Float, q1: Float, q2: Float, q3: Float) {
        val norm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (norm < 1e-6f) {
            quaternionW = 1.0f
            quaternionX = 0.0f
            quaternionY = 0.0f
            quaternionZ = 0.0f
            return
        }
        quaternionW = q0 / norm
        quaternionX = q1 / norm
        quaternionY = q2 / norm
        quaternionZ = q3 / norm
    }

    private fun androidAzimuthToStandardYaw(azimuthRad: Float): Float {
        return AngleUtils.normalizeRadians(HALF_PI - azimuthRad)
    }

    private fun standardYawToAndroidAzimuth(standardYawRad: Float): Float {
        return AngleUtils.normalizeRadians(HALF_PI - standardYawRad)
    }

    private companion object {
        private const val HALF_PI = (Math.PI / 2.0).toFloat()
        private const val MAX_DELTA_SECONDS = 0.05f
        private const val REQUIRED_INIT_SAMPLES = 18
        private const val MIN_INIT_ACCELERATION_NORM = 8.5f
        private const val MAX_INIT_ACCELERATION_NORM = 10.8f
        private const val MIN_INIT_MAGNETIC_NORM = 15.0f
        private const val MAX_INIT_GYRO_NORM = 0.35f
        private const val MAX_INIT_ACCELERATION_DELTA = 0.8f
        private const val MAX_INIT_MAGNETIC_DELTA = 3.5f
    }
}
