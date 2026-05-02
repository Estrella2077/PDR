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
    fun parse(context: Context, uri: Uri, fallbackHeightCm: Float, fallbackStepLengthScale: Float): ImportedTrackResult {
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
                    if (trimmed.isEmpty()) continue
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
            processRawSensorFile(context, uri, metadata, rawEvents, recordedLocalPositions, fallbackHeightCm, fallbackStepLengthScale)
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
        fallbackHeightCm: Float,
        fallbackStepLengthScale: Float
    ): ImportedTrackResult {
        val heightCm = metadata["height_cm"]?.toFloatOrNull() ?: fallbackHeightCm
        val stepLengthScale = metadata["step_length_scale"]?.toFloatOrNull() ?: fallbackStepLengthScale
        val processor = PdrProcessor().apply {
            setModelConfig(createHeightModelConfig(heightCm, stepLengthScale))
        }
        val gnssFusion = GnssFusionEkf()
        val hybridFusion = GnssFusionEkf()
        val pdrPoints = mutableListOf<GPSPoint>()
        val gnssPoints = mutableListOf<GPSPoint>()
        val hybridPoints = mutableListOf<GPSPoint>()

        var effectiveAnchorPoint = parseAnchor(metadata)
        var lastStepXMeters = 0.0
        var lastStepYMeters = 0.0

        effectiveAnchorPoint?.let { anchor ->
            processor.setReferenceLocation(anchor.lat, anchor.lon)
            gnssFusion.setAnchor(anchor)
            hybridFusion.setAnchor(anchor)
        }

        rawEvents.forEach { event ->
            when (event) {
                is RawEvent.Accelerometer -> {
                    val step = processor.updateAccelerometer(event.values, event.wallTimeMs, event.eventTimestampNs)
                    if (step != null) {
                        val deltaXMeters = step.xMeters.toDouble() - lastStepXMeters
                        val deltaYMeters = step.yMeters.toDouble() - lastStepYMeters
                        lastStepXMeters = step.xMeters.toDouble()
                        lastStepYMeters = step.yMeters.toDouble()

                        effectiveAnchorPoint?.let { anchor ->
                            pdrPoints.addIfChanged(projectLocalPoint(anchor, step.xMeters.toDouble(), step.yMeters.toDouble()))
                        }
                        hybridFusion.processPdrStep(deltaXMeters, deltaYMeters, step.timestampMs).point?.let { point ->
                            hybridPoints.addIfChanged(point)
                        }
                    }
                }

                is RawEvent.Magnetometer -> processor.updateMagnetometer(event.values)
                is RawEvent.Gyroscope -> processor.updateGyroscope(event.values, event.eventTimestampNs)
                is RawEvent.Gnss -> {
                    if (effectiveAnchorPoint == null) {
                        effectiveAnchorPoint = event.sample.point
                        processor.setReferenceLocation(
                            event.sample.point.lat,
                            event.sample.point.lon,
                            timeMillis = event.sample.timestampMs
                        )
                        gnssFusion.setAnchor(event.sample.point)
                        hybridFusion.setAnchor(event.sample.point)
                    }

                    gnssFusion.processGnss(
                        point = event.sample.point,
                        accuracyMeters = event.sample.accuracyMeters,
                        timestampMs = event.sample.timestampMs,
                        isLastKnown = false,
                        measurementAgeMs = 0L
                    ).let { update ->
                        if (update.measurementAccepted && update.point != null) {
                            gnssPoints.addIfChanged(update.point)
                        }
                    }

                    hybridFusion.processGnss(
                        point = event.sample.point,
                        accuracyMeters = event.sample.accuracyMeters,
                        timestampMs = event.sample.timestampMs,
                        isLastKnown = false,
                        measurementAgeMs = 0L
                    ).let { update ->
                        if (update.measurementAccepted && update.point != null) {
                            hybridPoints.addIfChanged(update.point)
                        }
                    }
                }
            }
        }

        if (pdrPoints.isEmpty() && effectiveAnchorPoint != null && recordedLocalPositions.isNotEmpty()) {
            pdrPoints.addAll(projectLocalPositions(effectiveAnchorPoint, recordedLocalPositions))
        }
        if (hybridPoints.isEmpty()) {
            when {
                gnssPoints.isNotEmpty() -> hybridPoints.addAll(gnssPoints)
                pdrPoints.isNotEmpty() -> hybridPoints.addAll(pdrPoints)
            }
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

    private fun projectLocalPositions(anchorPoint: GPSPoint, localPositions: List<Pair<Double, Double>>): List<GPSPoint> {
        return localPositions.map { (xMeters, yMeters) -> projectLocalPoint(anchorPoint, xMeters, yMeters) }
    }

    private fun projectLocalPoint(anchorPoint: GPSPoint, xMeters: Double, yMeters: Double): GPSPoint {
        val anchorXY = Transer.BL2XY(anchorPoint.lat, anchorPoint.lon)
        return Transer.XY2BL(anchorXY.x + xMeters, anchorXY.y + yMeters, anchorXY.n)
    }

    private fun parseMetadataLine(line: String, metadata: MutableMap<String, String>) {
        val content = line.removePrefix("#").trim()
        val separator = content.indexOf('=')
        if (separator <= 0) return
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

    private sealed class RawEvent {
        data class Accelerometer(val wallTimeMs: Long, val eventTimestampNs: Long, val values: FloatArray) : RawEvent()
        data class Gyroscope(val wallTimeMs: Long, val eventTimestampNs: Long, val values: FloatArray) : RawEvent()
        data class Magnetometer(val wallTimeMs: Long, val eventTimestampNs: Long, val values: FloatArray) : RawEvent()
        data class Gnss(val sample: GnssSample) : RawEvent()
    }

    private data class GnssSample(
        val timestampMs: Long,
        val point: GPSPoint,
        val accuracyMeters: Float
    )
}
