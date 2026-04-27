package com.example.imupdr

import android.content.Context
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class SessionFiles(
    val sessionDirectory: File,
    val rawFile: File,
    val stepFile: File
)

data class RawSensorRecord(
    val sensorTag: String,
    val wallTimeMs: Long,
    val eventTimestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val accuracy: Int,
    val headingDeg: Float,
    val steps: Int,
    val posXMeters: Float,
    val posYMeters: Float
) {
    fun toCsvLine(): String {
        return String.format(
            Locale.US,
            "%s,%d,%d,%.6f,%.6f,%.6f,%d,%.3f,%d,%.3f,%.3f",
            sensorTag,
            wallTimeMs,
            eventTimestampNs,
            x,
            y,
            z,
            accuracy,
            headingDeg,
            steps,
            posXMeters,
            posYMeters
        )
    }
}

class CsvSessionWriter(private val context: Context) {
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var rawWriter: BufferedWriter? = null
    private var stepWriter: BufferedWriter? = null
    private var rawFlushCounter = 0
    private var stepFlushCounter = 0

    fun startSession(anchorPoint: GPSPoint?, heightCm: Float, stepLengthScale: Float, navigationModeName: String): SessionFiles {
        stopSession()

        val baseDirectory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "imu_pdr_sessions"
        )
        if (!baseDirectory.exists()) {
            baseDirectory.mkdirs()
        }

        val sessionName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val sessionDirectory = File(baseDirectory, sessionName)
        if (!sessionDirectory.exists()) {
            sessionDirectory.mkdirs()
        }

        val rawFile = File(sessionDirectory, "imu_raw.csv")
        val stepFile = File(sessionDirectory, "pdr_steps.csv")

        rawWriter = BufferedWriter(FileWriter(rawFile, false))
        stepWriter = BufferedWriter(FileWriter(stepFile, false))
        writeMetadata(rawWriter, anchorPoint, heightCm, stepLengthScale, navigationModeName)
        writeMetadata(stepWriter, anchorPoint, heightCm, stepLengthScale, navigationModeName)
        rawWriter?.write("sensor,wall_time_ms,event_timestamp_ns,x,y,z,accuracy,heading_deg,steps,pos_x_m,pos_y_m")
        rawWriter?.newLine()
        stepWriter?.write("wall_time_ms,step_index,heading_deg,step_length_m,pos_x_m,pos_y_m,total_distance_m,filtered_motion,peak_motion")
        stepWriter?.newLine()
        rawWriter?.flush()
        stepWriter?.flush()
        rawFlushCounter = 0
        stepFlushCounter = 0

        return SessionFiles(
            sessionDirectory = sessionDirectory,
            rawFile = rawFile,
            stepFile = stepFile
        )
    }

    fun appendRaw(record: RawSensorRecord) {
        val writer = rawWriter ?: return
        val line = record.toCsvLine()
        ioExecutor.execute {
            writer.write(line)
            writer.newLine()
            rawFlushCounter += 1
            if (rawFlushCounter % FLUSH_EVERY == 0) {
                writer.flush()
            }
        }
    }

    fun appendStep(step: StepUpdate) {
        val writer = stepWriter ?: return
        val line = String.format(
            Locale.US,
            "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f",
            step.timestampMs,
            step.stepIndex,
            Math.toDegrees(step.headingRad.toDouble()),
            step.stepLengthMeters,
            step.xMeters,
            step.yMeters,
            step.totalDistanceMeters,
            step.filteredMotion,
            step.peakMotion
        )
        ioExecutor.execute {
            writer.write(line)
            writer.newLine()
            stepFlushCounter += 1
            if (stepFlushCounter % FLUSH_EVERY == 0) {
                writer.flush()
            }
        }
    }

    fun stopSession() {
        val activeRawWriter = rawWriter
        val activeStepWriter = stepWriter
        rawWriter = null
        stepWriter = null
        rawFlushCounter = 0
        stepFlushCounter = 0

        if (activeRawWriter == null && activeStepWriter == null) {
            return
        }

        ioExecutor.execute {
            activeRawWriter?.flush()
            activeRawWriter?.close()
            activeStepWriter?.flush()
            activeStepWriter?.close()
        }
    }

    fun shutdown() {
        stopSession()
        ioExecutor.shutdown()
    }

    private fun writeMetadata(
        writer: BufferedWriter?,
        anchorPoint: GPSPoint?,
        heightCm: Float,
        stepLengthScale: Float,
        navigationModeName: String
    ) {
        writer ?: return
        writer.write("# source=pdr")
        writer.newLine()
        writer.write("# anchor_lat=${anchorPoint?.lat ?: ""}")
        writer.newLine()
        writer.write("# anchor_lon=${anchorPoint?.lon ?: ""}")
        writer.newLine()
        writer.write(String.format(Locale.US, "# height_cm=%.1f", heightCm))
        writer.newLine()
        writer.write(String.format(Locale.US, "# step_length_scale=%.3f", stepLengthScale))
        writer.newLine()
        writer.write("# navigation_mode=$navigationModeName")
        writer.newLine()
    }

    companion object {
        private const val FLUSH_EVERY = 24
    }
}
