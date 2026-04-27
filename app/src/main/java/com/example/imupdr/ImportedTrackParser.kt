package com.example.imupdr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

data class ImportedTrackResult(
    val fileName: String,
    val sourceType: String,
    val anchorPoint: GPSPoint?,
    val pdrPoints: List<GPSPoint>,
    val gnssPoints: List<GPSPoint>,
    val hybridPoints: List<GPSPoint>,
    val postProcessed: Boolean
) {
    fun pointsForMode(mode: NavigationMode): List<GPSPoint> {
        return when (mode) {
            NavigationMode.PDR -> pdrPoints.ifEmpty { hybridPoints.ifEmpty { gnssPoints } }
            NavigationMode.GNSS -> gnssPoints.ifEmpty { hybridPoints.ifEmpty { pdrPoints } }
            NavigationMode.HYBRID -> hybridPoints.ifEmpty { pdrPoints.ifEmpty { gnssPoints } }
        }
    }

    fun lastPointForMode(mode: NavigationMode): GPSPoint? = pointsForMode(mode).lastOrNull()

    fun pointCountForMode(mode: NavigationMode): Int = pointsForMode(mode).size
}

object ImportedTrackParser {
    private const val GNSS_STALE_MS = 6_000L

    fun parse(context: Context, uri: Uri, fallbackHeightCm: Float): ImportedTrackResult {
        val metadata = linkedMapOf<String, String>()
        val rawEvents = mutableListOf<RawEvent>()
        val recordedLocalPositions = mutableListOf<Pair<Double, Double>>()
        var headerIndex = emptyMap<String, Int>()
        var isRawSensorFile = false
        var headerReady = false

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        continue
                    }
                    if (trimmed.startsWith("#")) {
                        parseMetadataLine(trimmed, metadata)
                        continue
                    }
                    if (!headerReady) {
                        headerIndex = trimmed.split(',').map { it.trim() }.withIndex().associate { it.value to it.index }
                        isRawSensorFile = headerIndex.containsKey("sensor")
                        headerReady = true
                        continue
                    }
                    val fields = trimmed.split(',')
                    if (isRawSensorFile) {
                        collectRawRow(fields, headerIndex, rawEvents, recordedLocalPositions)
                    } else {
                        collectStepRow(fields, headerIndex, recordedLocalPositions)
                    }
                }
            }
        } ?: error("无法打开导入文件。")

        return if (isRawSensorFile) {
            processRawSensorFile(context, uri, metadata, rawEvents, recordedLocalPositions, fallbackHeightCm)
        } else {
            processStepFile(context, uri, metadata, recordedLocalPositions)
        }
    }

    private fun processRawSensorFile(
        context: Context,
        uri: Uri,
        metadata: Map<String, String>,
        rawEvents: List<RawEvent>,
        recordedLocalPositions: List<Pair<Double, Double>>,
        fallbackHeightCm: Float
    ): ImportedTrackResult {
        val heightCm = metadata["height_cm"]?.toFloatOrNull() ?: fallbackHeightCm
        val processor = PdrProcessor().apply {
            setModelConfig(createHeightModelConfig(heightCm))
        }

        val stepSamples = mutableListOf<StepSample>()
        val gnssSamples = mutableListOf<GnssSample>()
        val anchorPoint = parseAnchor(metadata)
        if (anchorPoint != null) {
            processor.setReferenceLocation(anchorPoint.lat, anchorPoint.lon)
        }

        rawEvents.forEach { event ->
            when (event) {
                is RawEvent.Accelerometer -> {
                    val step = processor.updateAccelerometer(event.values, event.wallTimeMs, event.eventTimestampNs)
                    if (step != null) {
                        stepSamples.add(StepSample(step.timestampMs, step.xMeters.toDouble(), step.yMeters.toDouble()))
                    }
                }

                is RawEvent.Magnetometer -> processor.updateMagnetometer(event.values)
                is RawEvent.Gyroscope -> processor.updateGyroscope(event.values, event.eventTimestampNs)
                is RawEvent.Gnss -> {
                    gnssSamples.addIfChanged(event.sample)
                    if (anchorPoint == null && gnssSamples.size == 1) {
                        processor.setReferenceLocation(
                            event.sample.point.lat,
                            event.sample.point.lon,
                            timeMillis = event.sample.timestampMs
                        )
                    }
                }
            }
        }

        val effectiveAnchorPoint = anchorPoint ?: gnssSamples.firstOrNull()?.point
        if (anchorPoint != null) {
            processor.setReferenceLocation(anchorPoint.lat, anchorPoint.lon)
        }
        val pdrPoints = when {
            effectiveAnchorPoint != null && stepSamples.isNotEmpty() -> projectStepSamples(effectiveAnchorPoint, stepSamples)
            effectiveAnchorPoint != null && recordedLocalPositions.isNotEmpty() -> projectLocalPositions(effectiveAnchorPoint, recordedLocalPositions)
            else -> emptyList()
        }
        val gnssPoints = gnssSamples.map { it.point }
        val hybridPoints = when {
            effectiveAnchorPoint != null && stepSamples.isNotEmpty() -> buildHybridTrack(effectiveAnchorPoint, stepSamples, gnssSamples)
            gnssPoints.isNotEmpty() -> gnssPoints
            else -> pdrPoints
        }

        if (pdrPoints.isEmpty() && gnssPoints.isEmpty() && hybridPoints.isEmpty()) {
            error("导入文件中没有可用于后处理的轨迹数据。")
        }
        if (effectiveAnchorPoint == null && recordedLocalPositions.isNotEmpty() && gnssPoints.isEmpty()) {
            error("文件缺少锚点信息，无法把局部轨迹投影到地图。")
        }

        return ImportedTrackResult(
            fileName = resolveDisplayName(context, uri),
            sourceType = "传感器数据后处理",
            anchorPoint = effectiveAnchorPoint,
            pdrPoints = pdrPoints,
            gnssPoints = gnssPoints,
            hybridPoints = hybridPoints,
            postProcessed = true
        )
    }

    private fun processStepFile(
        context: Context,
        uri: Uri,
        metadata: Map<String, String>,
        recordedLocalPositions: List<Pair<Double, Double>>
    ): ImportedTrackResult {
        if (recordedLocalPositions.isEmpty()) {
            error("导入文件中没有可绘制的轨迹点。")
        }
        val anchorPoint = parseAnchor(metadata)
            ?: error("步级轨迹文件缺少锚点信息，无法投影到地图。")
        val pdrPoints = projectLocalPositions(anchorPoint, recordedLocalPositions)
        return ImportedTrackResult(
            fileName = resolveDisplayName(context, uri),
            sourceType = "步级轨迹导入",
            anchorPoint = anchorPoint,
            pdrPoints = pdrPoints,
            gnssPoints = emptyList(),
            hybridPoints = emptyList(),
            postProcessed = false
        )
    }

    private fun collectRawRow(
        fields: List<String>,
        headerIndex: Map<String, Int>,
        rawEvents: MutableList<RawEvent>,
        recordedLocalPositions: MutableList<Pair<Double, Double>>
    ) {
        val timestampMs = fields.valueAt(headerIndex, "wall_time_ms").toLongOrNull()
            ?: fields.valueAt(headerIndex, "event_timestamp_ns").toLongOrNull()?.div(1_000_000L)
            ?: 0L
        val eventTimestampNs = fields.valueAt(headerIndex, "event_timestamp_ns").toLongOrNull()
            ?: timestampMs * 1_000_000L
        val sensor = fields.valueAt(headerIndex, "sensor").uppercase()
        val x = fields.valueAt(headerIndex, "x").toFloatOrNull()
        val y = fields.valueAt(headerIndex, "y").toFloatOrNull()
        val z = fields.valueAt(headerIndex, "z").toFloatOrNull()
        val posX = fields.valueAt(headerIndex, "pos_x_m").toDoubleOrNull()
        val posY = fields.valueAt(headerIndex, "pos_y_m").toDoubleOrNull()

        if (posX != null && posY != null) {
            recordedLocalPositions.addIfChanged(posX, posY)
        }

        when (sensor) {
            "ACC" -> if (x != null && y != null && z != null) {
                rawEvents.add(RawEvent.Accelerometer(timestampMs, eventTimestampNs, floatArrayOf(x, y, z)))
            }

            "MAG" -> if (x != null && y != null && z != null) {
                rawEvents.add(RawEvent.Magnetometer(timestampMs, eventTimestampNs, floatArrayOf(x, y, z)))
            }

            "GYR" -> if (x != null && y != null && z != null) {
                rawEvents.add(RawEvent.Gyroscope(timestampMs, eventTimestampNs, floatArrayOf(x, y, z)))
            }

            "GPS" -> if (x != null && y != null) {
                rawEvents.add(
                    RawEvent.Gnss(
                        GnssSample(
                            timestampMs = timestampMs,
                            point = GPSPoint(x.toDouble(), y.toDouble()),
                            accuracyMeters = z ?: Float.NaN
                        )
                    )
                )
            }
        }
    }

    private fun collectStepRow(
        fields: List<String>,
        headerIndex: Map<String, Int>,
        recordedLocalPositions: MutableList<Pair<Double, Double>>
    ) {
        val posX = fields.valueAt(headerIndex, "pos_x_m").toDoubleOrNull()
        val posY = fields.valueAt(headerIndex, "pos_y_m").toDoubleOrNull()
        if (posX != null && posY != null) {
            recordedLocalPositions.addIfChanged(posX, posY)
        }
    }

    private fun parseAnchor(metadata: Map<String, String>): GPSPoint? {
        val lat = metadata["anchor_lat"]?.toDoubleOrNull()
        val lon = metadata["anchor_lon"]?.toDoubleOrNull()
        return if (lat != null && lon != null) GPSPoint(lat, lon) else null
    }

    private fun projectStepSamples(anchorPoint: GPSPoint, stepSamples: List<StepSample>): List<GPSPoint> {
        return stepSamples.map { sample -> projectLocalPoint(anchorPoint, sample.xMeters, sample.yMeters) }
    }

    private fun projectLocalPositions(anchorPoint: GPSPoint, localPositions: List<Pair<Double, Double>>): List<GPSPoint> {
        return localPositions.map { (xMeters, yMeters) -> projectLocalPoint(anchorPoint, xMeters, yMeters) }
    }

    private fun projectLocalPoint(anchorPoint: GPSPoint, xMeters: Double, yMeters: Double): GPSPoint {
        val anchorXY = Transer.BL2XY(anchorPoint.lat, anchorPoint.lon)
        return Transer.XY2BL(anchorXY.x + xMeters, anchorXY.y + yMeters, anchorXY.n)
    }

    private fun buildHybridTrack(
        anchorPoint: GPSPoint,
        stepSamples: List<StepSample>,
        gnssSamples: List<GnssSample>
    ): List<GPSPoint> {
        if (stepSamples.isEmpty()) {
            return gnssSamples.map { it.point }
        }

        val hybridTrack = mutableListOf<GPSPoint>()
        var gnssIndex = 0
        var latestGnssSample: GnssSample? = null

        stepSamples.forEach { step ->
            while (gnssIndex < gnssSamples.size && gnssSamples[gnssIndex].timestampMs <= step.timestampMs) {
                latestGnssSample = gnssSamples[gnssIndex]
                gnssIndex += 1
            }
            val pdrPoint = projectLocalPoint(anchorPoint, step.xMeters, step.yMeters)
            val hybridPoint = computeHybridPoint(pdrPoint, latestGnssSample, step.timestampMs) ?: pdrPoint
            hybridTrack.addIfChanged(hybridPoint)
        }

        return if (hybridTrack.isNotEmpty()) hybridTrack else gnssSamples.map { it.point }
    }

    private fun computeHybridPoint(
        pdrPoint: GPSPoint,
        gnssSample: GnssSample?,
        timestampMs: Long
    ): GPSPoint? {
        val latestGnss = gnssSample ?: return pdrPoint
        if (timestampMs - latestGnss.timestampMs > GNSS_STALE_MS) {
            return pdrPoint
        }
        val pdrXY = Transer.BL2XY(pdrPoint.lat, pdrPoint.lon)
        val gnssXY = Transer.BL2XY(latestGnss.point.lat, latestGnss.point.lon)
        val alpha = when {
            !latestGnss.accuracyMeters.isFinite() -> 0.18
            latestGnss.accuracyMeters <= 8f -> 0.45
            latestGnss.accuracyMeters <= 15f -> 0.30
            latestGnss.accuracyMeters <= 30f -> 0.18
            else -> 0.10
        }
        val fusedX = pdrXY.x * (1.0 - alpha) + gnssXY.x * alpha
        val fusedY = pdrXY.y * (1.0 - alpha) + gnssXY.y * alpha
        return Transer.XY2BL(fusedX, fusedY, pdrXY.n)
    }

    private fun parseMetadataLine(line: String, metadata: MutableMap<String, String>) {
        val content = line.removePrefix("#").trim()
        val separator = content.indexOf('=')
        if (separator <= 0) {
            return
        }
        metadata[content.substring(0, separator).trim()] = content.substring(separator + 1).trim()
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment ?: "导入轨迹.csv"
    }

    private fun List<String>.valueAt(indexMap: Map<String, Int>, key: String): String {
        val index = indexMap[key] ?: return ""
        return getOrNull(index)?.trim().orEmpty()
    }

    private fun MutableList<Pair<Double, Double>>.addIfChanged(xMeters: Double, yMeters: Double) {
        val last = lastOrNull()
        if (last == null || abs(last.first - xMeters) > 0.05 || abs(last.second - yMeters) > 0.05) {
            add(xMeters to yMeters)
        }
    }

    private fun MutableList<GPSPoint>.addIfChanged(point: GPSPoint) {
        val last = lastOrNull()
        if (last == null || abs(last.lat - point.lat) > 1e-7 || abs(last.lon - point.lon) > 1e-7) {
            add(point)
        }
    }

    private fun MutableList<GnssSample>.addIfChanged(sample: GnssSample) {
        val last = lastOrNull()
        if (last == null || abs(last.point.lat - sample.point.lat) > 1e-7 || abs(last.point.lon - sample.point.lon) > 1e-7) {
            add(sample)
        }
    }

    private sealed class RawEvent {
        data class Accelerometer(val wallTimeMs: Long, val eventTimestampNs: Long, val values: FloatArray) : RawEvent()
        data class Gyroscope(val wallTimeMs: Long, val eventTimestampNs: Long, val values: FloatArray) : RawEvent()
        data class Magnetometer(val wallTimeMs: Long, val eventTimestampNs: Long, val values: FloatArray) : RawEvent()
        data class Gnss(val sample: GnssSample) : RawEvent()
    }

    private data class StepSample(
        val timestampMs: Long,
        val xMeters: Double,
        val yMeters: Double
    )

    private data class GnssSample(
        val timestampMs: Long,
        val point: GPSPoint,
        val accuracyMeters: Float
    )
}
