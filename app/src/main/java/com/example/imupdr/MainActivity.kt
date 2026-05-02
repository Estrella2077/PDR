package com.example.imupdr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BaiduMapOptions
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.DotOptions
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.Overlay
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.example.imupdr.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var csvWriter: CsvSessionWriter
    private lateinit var mapView: MapView
    private lateinit var baiduMap: BaiduMap
    private lateinit var currentMarkerIcon: BitmapDescriptor
    private val displayAhrsEstimator = AhrsEstimator()

    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val pdrProcessor = PdrProcessor()
    private val gnssModeFusion = GnssFusionEkf()
    private val hybridModeFusion = GnssFusionEkf()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val openTrackFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { importTrackFile(it) }
    }

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    private var isRunning = false
    private var isSavingSession = false
    private var currentMode = NavigationMode.HYBRID
    private var currentHeightCm = DEFAULT_HEIGHT_CM
    private var currentStepLengthScale = DEFAULT_STEP_LENGTH_SCALE
    private var drawTrajectoryEnabled = true
    private var showImportedTrack = true
    private var currentModelConfig = createHeightModelConfig(DEFAULT_HEIGHT_CM, DEFAULT_STEP_LENGTH_SCALE)
    private var sessionFiles: SessionFiles? = null
    private var lastSessionDirectoryName: String? = null
    private var hasCenteredMap = false
    private var locationServicesActive = false
    private var displayHeadingRad = 0.0f
    private var displayHeadingReady = false
    private var displayPitchRad = 0.0f
    private var displayRollRad = 0.0f
    private var displayMagneticDeclinationRad = 0.0f
    private var lastRenderedHeadingRad = Float.NaN

    private var anchorPoint: GPSPoint? = null
    private var latestGnssPoint: GPSPoint? = null
    private var lastGnssFilteredPoint: GPSPoint? = null
    private var latestGnssAccuracyMeters = Float.NaN
    private var latestGnssTimeMs = 0L
    private var lastPdrPoint: GPSPoint? = null
    private var lastHybridPoint: GPSPoint? = null
    private var lastPdrLocalXMeters = 0.0
    private var lastPdrLocalYMeters = 0.0

    private var visibleSatellites = 0
    private var usedSatellites = 0
    private val satellites = mutableListOf<SatelliteInfo>()

    private var accAccuracy = 0
    private var gyrAccuracy = 0
    private var magAccuracy = 0

    private val lastAcc = FloatArray(3)
    private val lastGyr = FloatArray(3)
    private val lastMag = FloatArray(3)

    private val accHistoryX = ArrayDeque<Float>()
    private val accHistoryY = ArrayDeque<Float>()
    private val accHistoryZ = ArrayDeque<Float>()
    private val gyrHistoryX = ArrayDeque<Float>()
    private val gyrHistoryY = ArrayDeque<Float>()
    private val gyrHistoryZ = ArrayDeque<Float>()
    private val magHistoryX = ArrayDeque<Float>()
    private val magHistoryY = ArrayDeque<Float>()
    private val magHistoryZ = ArrayDeque<Float>()
    private val pdrHistoryMotion = ArrayDeque<Float>()
    private val pdrHistorySteps = ArrayDeque<Float>()
    private val pdrHistoryHeading = ArrayDeque<Float>()

    private val pdrTrack = mutableListOf<LatLng>()
    private val gnssTrack = mutableListOf<LatLng>()
    private val hybridTrack = mutableListOf<LatLng>()
    private val importedTrackByMode = mutableMapOf<NavigationMode, List<LatLng>>()
    private var importedTrackResult: ImportedTrackResult? = null

    private val dynamicOverlays = mutableListOf<Overlay>()
    private var anchorOverlay: Overlay? = null

    private val gpsLocationListener = LocationListener { location ->
        handleGnssLocation(location, false)
    }

    private val gnssStatusCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                visibleSatellites = status.satelliteCount
                usedSatellites = 0
                satellites.clear()
                for (index in 0 until status.satelliteCount) {
                    val used = status.usedInFix(index)
                    if (used) usedSatellites += 1
                    satellites.add(
                        SatelliteInfo(
                            constellationType = status.getConstellationType(index),
                            elevationDegrees = status.getElevationDegrees(index),
                            azimuthDegrees = status.getAzimuthDegrees(index),
                            usedInFix = used
                        )
                    )
                }
                binding.satelliteSkyView.submitSatellites(satellites)
                updateReferencePage()
                updateMapPage()
            }
        }
    } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        SDKInitializer.initialize(applicationContext)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        csvWriter = CsvSessionWriter(this)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        currentMarkerIcon = createBitmapDescriptor(R.drawable.map_arrow_marker)

        loadPreferences()
        pdrProcessor.setModelConfig(currentModelConfig)
        applyWindowInsetSpacer()
        setupMap()
        setupControls()
        switchPage(0)
        updateActionButtons()
        refreshAllPanels()

        if (!hasLocationPermission()) requestLocationPermission() else fetchLastKnownLocation()?.let { handleGnssLocation(it, true) }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        registerSensors()
        startLocationServices()
    }

    override fun onPause() {
        unregisterSensors()
        stopLocationServices()
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterSensors()
        stopLocationServices()
        clearDynamicOverlays()
        anchorOverlay?.remove()
        anchorOverlay = null
        currentMarkerIcon.recycle()
        mapView.onDestroy()
        csvWriter.shutdown()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                copyValues(event.values, lastAcc)
                updateDisplayAttitude(Sensor.TYPE_ACCELEROMETER, lastAcc, event.timestamp)
                if (isRunning) {
                    syncPdrExternalAttitude()
                    appendHistory(accHistoryX, lastAcc[0])
                    appendHistory(accHistoryY, lastAcc[1])
                    appendHistory(accHistoryZ, lastAcc[2])
                    val step = pdrProcessor.updateAccelerometer(lastAcc, System.currentTimeMillis(), event.timestamp)
                    val snapshot = pdrProcessor.snapshot()
                    appendHistory(pdrHistoryMotion, snapshot.filteredMotion)
                    appendHistory(pdrHistorySteps, snapshot.steps.toFloat())
                    appendHistory(pdrHistoryHeading, if (snapshot.headingReady) Math.toDegrees(snapshot.headingRad.toDouble()).toFloat() else 0f)
                    appendRawRecord("ACC", event.timestamp, lastAcc, snapshot)
                    if (step != null) handlePdrStep(step)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                copyValues(event.values, lastGyr)
                updateDisplayAttitude(Sensor.TYPE_GYROSCOPE, lastGyr, event.timestamp)
                if (isRunning) {
                    syncPdrExternalAttitude()
                    appendHistory(gyrHistoryX, lastGyr[0])
                    appendHistory(gyrHistoryY, lastGyr[1])
                    appendHistory(gyrHistoryZ, lastGyr[2])
                    pdrProcessor.updateGyroscope(lastGyr, event.timestamp)
                    appendRawRecord("GYR", event.timestamp, lastGyr, pdrProcessor.snapshot())
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                copyValues(event.values, lastMag)
                updateDisplayAttitude(Sensor.TYPE_MAGNETIC_FIELD, lastMag, event.timestamp)
                if (isRunning) {
                    syncPdrExternalAttitude()
                    appendHistory(magHistoryX, lastMag[0])
                    appendHistory(magHistoryY, lastMag[1])
                    appendHistory(magHistoryZ, lastMag[2])
                    pdrProcessor.updateMagnetometer(lastMag)
                    appendRawRecord("MAG", event.timestamp, lastMag, pdrProcessor.snapshot())
                }
            }
        }
        if (shouldRedrawMapForHeadingChange()) {
            redrawMap(resolveDisplayPoint(), animateCenter = false)
        }
        if (isRunning) {
            refreshAllPanels()
        } else {
            updateMapPage()
            updateReferencePage()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        when (sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accAccuracy = accuracy
            Sensor.TYPE_GYROSCOPE -> gyrAccuracy = accuracy
            Sensor.TYPE_MAGNETIC_FIELD -> magAccuracy = accuracy
        }
        refreshAllPanels()
    }

    private fun applyWindowInsetSpacer() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.topInsetSpacer.layoutParams = binding.topInsetSpacer.layoutParams.apply { height = topInset + dpToPx(8) }
            insets
        }
    }

    private fun loadPreferences() {
        currentHeightCm = preferences.getFloat(KEY_HEIGHT_CM, DEFAULT_HEIGHT_CM)
        currentStepLengthScale = preferences.getFloat(KEY_STEP_LENGTH_SCALE, DEFAULT_STEP_LENGTH_SCALE)
        drawTrajectoryEnabled = preferences.getBoolean(KEY_DRAW_TRAJECTORY, true)
        showImportedTrack = preferences.getBoolean(KEY_SHOW_IMPORTED_TRACK, true)
        currentMode = NavigationMode.entries.firstOrNull { it.name == preferences.getString(KEY_NAVIGATION_MODE, NavigationMode.HYBRID.name) } ?: NavigationMode.HYBRID
        currentModelConfig = createHeightModelConfig(currentHeightCm, currentStepLengthScale)
    }

    private fun setupControls() {
        binding.heightInput.setText(formatHeightInput(currentHeightCm))
        binding.stepLengthScaleInput.setText(formatStepLengthScaleInput(currentStepLengthScale))
        binding.drawTrajectorySwitch.isChecked = drawTrajectoryEnabled
        binding.showImportedTrackSwitch.isChecked = showImportedTrack
        syncModeSelection()

        binding.applyHeightButton.setOnClickListener { applyHeightSetting() }
        binding.startButton.setOnClickListener { startCollection() }
        binding.stopButton.setOnClickListener { stopCollection() }
        binding.resetButton.setOnClickListener { resetAll() }
        binding.saveSensorButton.setOnClickListener { toggleSensorSaving() }
        binding.importTrackButton.setOnClickListener {
            if (isRunning) Toast.makeText(this, "请先停止采集，再导入数据做后处理。", Toast.LENGTH_SHORT).show()
            else openTrackFileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
        }

        binding.drawTrajectorySwitch.setOnCheckedChangeListener { _, isChecked ->
            drawTrajectoryEnabled = isChecked
            preferences.edit { putBoolean(KEY_DRAW_TRAJECTORY, isChecked) }
            redrawMap(resolveDisplayPoint())
            refreshAllPanels()
        }
        binding.showImportedTrackSwitch.setOnCheckedChangeListener { _, isChecked ->
            showImportedTrack = isChecked
            preferences.edit { putBoolean(KEY_SHOW_IMPORTED_TRACK, isChecked) }
            redrawMap(resolveDisplayPoint())
            refreshAllPanels()
        }
        binding.settingsModeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentMode = when (checkedId) {
                binding.settingsPdrModeButton.id -> NavigationMode.PDR
                binding.settingsGnssModeButton.id -> NavigationMode.GNSS
                else -> NavigationMode.HYBRID
            }
            preferences.edit { putString(KEY_NAVIGATION_MODE, currentMode.name) }
            redrawMap(resolveDisplayPoint())
            refreshAllPanels()
        }

        binding.bottomTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { switchPage(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.bottomTabs.getTabAt(0)?.select()
    }

    private fun syncModeSelection() {
        binding.settingsModeGroup.check(
            when (currentMode) {
                NavigationMode.PDR -> binding.settingsPdrModeButton.id
                NavigationMode.GNSS -> binding.settingsGnssModeButton.id
                NavigationMode.HYBRID -> binding.settingsHybridModeButton.id
            }
        )
    }

    private fun switchPage(page: Int) {
        binding.mapPage.visibility = if (page == 0) View.VISIBLE else View.GONE
        binding.sensorPage.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.referencePage.visibility = if (page == 2) View.VISIBLE else View.GONE
        binding.settingsPage.visibility = if (page == 3) View.VISIBLE else View.GONE
    }

    private fun setupMap() {
        val options = BaiduMapOptions().mapType(BaiduMap.MAP_TYPE_NORMAL).zoomControlsPosition(Point(24, 24))
        mapView = MapView(this, options)
        mapView.showZoomControls(true)
        binding.mapContainer.addView(mapView)
        baiduMap = mapView.map
        baiduMap.setMapStatus(MapStatusUpdateFactory.zoomTo(19.0f))
        baiduMap.uiSettings.isCompassEnabled = true
        baiduMap.setOnMapLongClickListener { latLng ->
            anchorPoint = Transer.baiduToWgs84(latLng.latitude, latLng.longitude)
            anchorPoint?.let { point ->
                pdrProcessor.setReferenceLocation(point.lat, point.lon)
                updateDisplayReferenceLocation(point.lat, point.lon)
                hybridModeFusion.setAnchor(point)
            }
            if (isRunning) {
                pdrProcessor.reset()
                pdrProcessor.setModelConfig(currentModelConfig)
                anchorPoint?.let { point ->
                    pdrProcessor.setReferenceLocation(point.lat, point.lon)
                    updateDisplayReferenceLocation(point.lat, point.lon)
                    hybridModeFusion.setAnchor(point)
                }
                pdrTrack.clear()
                hybridTrack.clear()
                lastPdrPoint = null
                lastHybridPoint = null
                lastPdrLocalXMeters = 0.0
                lastPdrLocalYMeters = 0.0
                clearPdrHistoryOnly()
                Toast.makeText(this, "已重设 PDR 起点，并清空实时 PDR 轨迹。", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "已设置 PDR 起点。", Toast.LENGTH_SHORT).show()
            }
            redrawMap(resolveDisplayPoint())
            refreshAllPanels()
        }
    }

    private fun startCollection() {
        if (isRunning) return
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        if (accelerometer == null || gyroscope == null || magnetometer == null) {
            Toast.makeText(this, "当前手机缺少必需的加速度计、陀螺仪或磁力计。", Toast.LENGTH_LONG).show()
            return
        }
        val startupAnchorCandidate = anchorPoint
            ?: lastGnssFilteredPoint
            ?: latestGnssPoint?.takeIf { !latestGnssAccuracyMeters.isFinite() || latestGnssAccuracyMeters <= ANCHOR_MAX_ACCURACY_METERS }
            ?: fetchLastKnownLocation()?.let { GPSPoint(it.latitude, it.longitude) }
        resetTrackingState(true)
        if (anchorPoint == null) anchorPoint = startupAnchorCandidate
        anchorPoint?.let { point ->
            pdrProcessor.setReferenceLocation(point.lat, point.lon)
            updateDisplayReferenceLocation(point.lat, point.lon)
            hybridModeFusion.setAnchor(point)
        }
        gnssModeFusion.setAnchor(anchorPoint ?: lastGnssFilteredPoint ?: latestGnssPoint)
        isRunning = true
        syncPdrExternalAttitude()
        redrawMap(resolveDisplayPoint())
        updateActionButtons()
        refreshAllPanels()
    }

    private fun stopCollection() {
        if (!isRunning) return
        if (isSavingSession) stopDataSaving(false)
        isRunning = false
        updateActionButtons()
        refreshAllPanels()
    }

    private fun toggleSensorSaving() {
        if (!isRunning) {
            Toast.makeText(this, "请先开始采集，再保存传感器数据。", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSavingSession) stopDataSaving(true) else startDataSaving()
    }

    private fun startDataSaving() {
        val effectiveAnchor = anchorPoint ?: latestGnssPoint ?: fetchLastKnownLocation()?.let { GPSPoint(it.latitude, it.longitude) }
        val files = csvWriter.startSession(effectiveAnchor, currentHeightCm, currentStepLengthScale, modeName(currentMode))
        sessionFiles = files
        lastSessionDirectoryName = files.sessionDirectory.name
        isSavingSession = true
        updateActionButtons()
        refreshAllPanels()
        Toast.makeText(this, "开始保存到 ${files.sessionDirectory.name}", Toast.LENGTH_SHORT).show()
    }

    private fun stopDataSaving(showToast: Boolean) {
        if (!isSavingSession) return
        val directoryName = sessionFiles?.sessionDirectory?.name ?: lastSessionDirectoryName
        csvWriter.stopSession()
        sessionFiles = null
        isSavingSession = false
        updateActionButtons()
        refreshAllPanels()
        if (showToast) Toast.makeText(this, "已停止保存${directoryName?.let { "：$it" } ?: ""}", Toast.LENGTH_SHORT).show()
    }

    private fun resetAll() {
        if (isRunning) stopCollection()
        sessionFiles = null
        isSavingSession = false
        lastSessionDirectoryName = null
        anchorPoint = null
        latestGnssPoint = null
        latestGnssAccuracyMeters = Float.NaN
        latestGnssTimeMs = 0L
        visibleSatellites = 0
        usedSatellites = 0
        satellites.clear()
        binding.satelliteSkyView.submitSatellites(emptyList())
        hasCenteredMap = false
        clearImportedTrack()
        resetTrackingState(false)
        redrawMap(null)
        updateActionButtons()
        refreshAllPanels()
    }

    private fun clearImportedTrack() {
        importedTrackByMode.clear()
        importedTrackResult = null
    }

    private fun resetTrackingState(keepAnchor: Boolean) {
        pdrProcessor.reset()
        pdrProcessor.setModelConfig(currentModelConfig)
        gnssModeFusion.setAnchor(if (keepAnchor) anchorPoint else null)
        hybridModeFusion.setAnchor(if (keepAnchor) anchorPoint else null)
        gnssModeFusion.reset()
        hybridModeFusion.reset()
        lastGnssFilteredPoint = null
        lastPdrPoint = null
        lastHybridPoint = null
        lastPdrLocalXMeters = 0.0
        lastPdrLocalYMeters = 0.0
        pdrTrack.clear()
        gnssTrack.clear()
        hybridTrack.clear()
        accAccuracy = 0
        gyrAccuracy = 0
        magAccuracy = 0
        lastAcc.fill(0f)
        lastGyr.fill(0f)
        lastMag.fill(0f)
        clearAllHistory()
        if (!keepAnchor) anchorPoint = null
    }

    private fun registerSensors() {
        accelerometer?.also { sensorManager.registerListener(this, it, SENSOR_PERIOD_US) }
        gyroscope?.also { sensorManager.registerListener(this, it, SENSOR_PERIOD_US) }
        magnetometer?.also { sensorManager.registerListener(this, it, SENSOR_PERIOD_US) }
    }

    private fun unregisterSensors() { sensorManager.unregisterListener(this) }

    private fun startLocationServices() {
        if (locationServicesActive || !hasLocationPermission()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler)
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsLocationListener)
            }
            locationServicesActive = true
            fetchLastKnownLocation()?.let { handleGnssLocation(it, true) }
        } catch (_: SecurityException) {
            locationServicesActive = false
        }
    }

    private fun stopLocationServices() {
        if (!locationServicesActive) return
        try {
            locationManager.removeUpdates(gpsLocationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            }
        } catch (_: SecurityException) {
        } finally {
            locationServicesActive = false
        }
    }

    private fun fetchLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun handleGnssLocation(location: Location, lastKnown: Boolean) {
        val nowMs = System.currentTimeMillis()
        val measurementTimestampMs = location.time.takeIf { it > 0L } ?: nowMs
        val measurementAgeMs = (nowMs - measurementTimestampMs).coerceAtLeast(0L)
        latestGnssPoint = GPSPoint(location.latitude, location.longitude)
        latestGnssAccuracyMeters = if (location.hasAccuracy()) location.accuracy else Float.NaN
        latestGnssTimeMs = nowMs
        pdrProcessor.setReferenceLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
            timeMillis = nowMs
        )
        updateDisplayReferenceLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
            timeMillis = nowMs
        )

        val gnssPoint = latestGnssPoint ?: return
        val gnssAcceptedForAnchor = (!lastKnown || measurementAgeMs <= LAST_KNOWN_MAX_AGE_MS) &&
            (!latestGnssAccuracyMeters.isFinite() || latestGnssAccuracyMeters <= ANCHOR_MAX_ACCURACY_METERS)
        if (anchorPoint == null && gnssAcceptedForAnchor && (isRunning || currentMode != NavigationMode.PDR)) {
            anchorPoint = GPSPoint(gnssPoint.lat, gnssPoint.lon)
            hybridModeFusion.setAnchor(anchorPoint)
        }

        val gnssUpdate = gnssModeFusion.processGnss(
            point = gnssPoint,
            accuracyMeters = latestGnssAccuracyMeters,
            timestampMs = measurementTimestampMs,
            isLastKnown = lastKnown,
            measurementAgeMs = measurementAgeMs
        )
        if (gnssUpdate.point != null) {
            lastGnssFilteredPoint = gnssUpdate.point
        }
        if (isRunning) {
            val snapshot = pdrProcessor.snapshot()
            csvWriter.appendRaw(
                RawSensorRecord(
                    sensorTag = "GPS",
                    wallTimeMs = nowMs,
                    eventTimestampNs = location.elapsedRealtimeNanos,
                    x = location.latitude.toFloat(),
                    y = location.longitude.toFloat(),
                    z = if (location.hasAccuracy()) location.accuracy else Float.NaN,
                    accuracy = usedSatellites,
                    headingDeg = Float.NaN,
                    steps = snapshot.steps,
                    posXMeters = snapshot.positionXMeters,
                    posYMeters = snapshot.positionYMeters
                )
            )

            if (gnssUpdate.measurementAccepted && gnssUpdate.point != null) {
                appendTrackPoint(gnssTrack, gnssUpdate.point)
                if (currentMode == NavigationMode.GNSS) redrawMap(gnssUpdate.point)
            }

            val hybridUpdate = hybridModeFusion.processGnss(
                point = gnssPoint,
                accuracyMeters = latestGnssAccuracyMeters,
                timestampMs = measurementTimestampMs,
                isLastKnown = lastKnown,
                measurementAgeMs = measurementAgeMs
            )
            if (hybridUpdate.point != null) {
                lastHybridPoint = hybridUpdate.point
            }
            if (hybridUpdate.measurementAccepted && hybridUpdate.point != null) {
                appendTrackPoint(hybridTrack, hybridUpdate.point)
                if (currentMode == NavigationMode.HYBRID) redrawMap(hybridUpdate.point)
            }
        } else {
            redrawMap(resolveDisplayPoint())
        }
        refreshAllPanels()
    }

    private fun handlePdrStep(step: StepUpdate) {
        val deltaXMeters = step.xMeters.toDouble() - lastPdrLocalXMeters
        val deltaYMeters = step.yMeters.toDouble() - lastPdrLocalYMeters
        lastPdrLocalXMeters = step.xMeters.toDouble()
        lastPdrLocalYMeters = step.yMeters.toDouble()
        val pdrPoint = buildPdrPoint(step.xMeters, step.yMeters)
        if (pdrPoint != null) {
            lastPdrPoint = pdrPoint
            appendTrackPoint(pdrTrack, pdrPoint)
            if (currentMode == NavigationMode.PDR) redrawMap(pdrPoint)
        }
        val hybridPoint = hybridModeFusion.processPdrStep(deltaXMeters, deltaYMeters, step.timestampMs).point
        if (hybridPoint != null) {
            lastHybridPoint = hybridPoint
            appendTrackPoint(hybridTrack, hybridPoint)
            if (currentMode == NavigationMode.HYBRID) redrawMap(hybridPoint)
        }
        csvWriter.appendStep(step)
    }

    private fun buildPdrPoint(xMeters: Float, yMeters: Float): GPSPoint? {
        val anchor = anchorPoint ?: return null
        val origin = Transer.BL2XY(anchor.lat, anchor.lon)
        return Transer.XY2BL(origin.x + xMeters, origin.y + yMeters, origin.n)
    }

    private fun appendTrackPoint(track: MutableList<LatLng>, point: GPSPoint?) {
        if (point == null) return
        val bdPoint = Transer.WGS2BD09(point.lat, point.lon)
        track.add(LatLng(bdPoint.lat, bdPoint.lon))
    }

    private fun cacheImportedTracks(result: ImportedTrackResult) {
        importedTrackByMode.clear()
        NavigationMode.entries.forEach { mode ->
            importedTrackByMode[mode] = result.pointsForMode(mode).map { point ->
                val bdPoint = Transer.WGS2BD09(point.lat, point.lon)
                LatLng(bdPoint.lat, bdPoint.lon)
            }
        }
    }

    private fun currentImportedTrack(): List<LatLng> = importedTrackByMode[currentMode].orEmpty()
    private fun currentImportedTrackLastPoint(): GPSPoint? = importedTrackResult?.lastPointForMode(currentMode)

    private fun redrawMap(centerPoint: GPSPoint?, animateCenter: Boolean = true) {
        clearDynamicOverlays()
        redrawAnchor()
        val state = pdrProcessor.snapshot()

        if (drawTrajectoryEnabled) {
            val activeTrack = when (currentMode) {
                NavigationMode.PDR -> pdrTrack
                NavigationMode.GNSS -> gnssTrack
                NavigationMode.HYBRID -> hybridTrack
            }
            val lineColor = when (currentMode) {
                NavigationMode.PDR -> 0xAA1565C0.toInt()
                NavigationMode.GNSS -> 0xAA2E7D32.toInt()
                NavigationMode.HYBRID -> 0xAAC62828.toInt()
            }
            if (activeTrack.size >= 2) {
                dynamicOverlays.add(baiduMap.addOverlay(PolylineOptions().width(6).color(lineColor).points(activeTrack)))
            }
        }

        val importedTrack = currentImportedTrack()
        if (showImportedTrack && importedTrack.size >= 2) {
            dynamicOverlays.add(baiduMap.addOverlay(PolylineOptions().width(5).color(0xAAF57C00.toInt()).points(importedTrack)))
        }

        val effectiveCenter = centerPoint ?: if (showImportedTrack) currentImportedTrackLastPoint() else null
        val effectiveColor = when (currentMode) {
            NavigationMode.PDR -> 0xAA1565C0.toInt()
            NavigationMode.GNSS -> 0xAA2E7D32.toInt()
            NavigationMode.HYBRID -> 0xAAC62828.toInt()
        }
        val displayPoint = effectiveCenter?.let { Transer.WGS2BD09(it.lat, it.lon) }
        if (displayPoint != null) {
            val latLng = LatLng(displayPoint.lat, displayPoint.lon)
            val headingRad = activeHeadingRad(state)
            val headingDegrees = headingRad?.let { (-Math.toDegrees(it.toDouble())).toFloat() } ?: 0.0f
            dynamicOverlays.add(
                baiduMap.addOverlay(
                    MarkerOptions()
                        .position(latLng)
                        .icon(currentMarkerIcon)
                        .flat(true)
                        .anchor(0.5f, 0.5f)
                        .rotate(headingDegrees)
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                )
            )
            if (!hasCenteredMap) {
                baiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(latLng, 20.0f))
                hasCenteredMap = true
            } else if (animateCenter) {
                baiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLng(latLng))
            }
            lastRenderedHeadingRad = headingRad ?: Float.NaN
        } else {
            lastRenderedHeadingRad = Float.NaN
        }
    }

    private fun redrawAnchor() {
        anchorOverlay?.remove()
        anchorOverlay = null
        val currentAnchor = anchorPoint ?: return
        val bdPoint = Transer.WGS2BD09(currentAnchor.lat, currentAnchor.lon)
        anchorOverlay = baiduMap.addOverlay(DotOptions().center(LatLng(bdPoint.lat, bdPoint.lon)).radius(10).color(0xAAFF9800.toInt()))
    }

    private fun clearDynamicOverlays() {
        dynamicOverlays.forEach { it.remove() }
        dynamicOverlays.clear()
    }

    private fun resolveDisplayPoint(): GPSPoint? = when (currentMode) {
        NavigationMode.PDR -> lastPdrPoint ?: anchorPoint ?: latestGnssPoint ?: currentImportedTrackLastPoint()
        NavigationMode.GNSS -> lastGnssFilteredPoint ?: latestGnssPoint ?: currentImportedTrackLastPoint()
        NavigationMode.HYBRID -> lastHybridPoint ?: lastGnssFilteredPoint ?: latestGnssPoint ?: lastPdrPoint ?: anchorPoint ?: currentImportedTrackLastPoint()
    }

    private fun updateActionButtons() {
        binding.startButton.isEnabled = !isRunning
        binding.stopButton.isEnabled = isRunning
        binding.saveSensorButton.isEnabled = isRunning
        binding.importTrackButton.isEnabled = !isRunning
        binding.saveSensorButton.text = if (isSavingSession) "停止保存数据" else getString(R.string.save_sensor_button)
    }

    private fun refreshAllPanels() {
        updateMapPage()
        updateSensorPage()
        updateReferencePage()
        updateSettingsPage()
    }

    private fun updateMapPage() {
        val state = pdrProcessor.snapshot()
        val display = resolveDisplayPoint()
        binding.statusPrimaryText.text = String.format(Locale.US, "%s  |  身高 %.0f cm  |  步数 %d  |  距离 %.2f m  |  卫星 %d/%d", modeName(currentMode), currentHeightCm, state.steps, state.totalDistanceMeters, usedSatellites, visibleSatellites)
        binding.statusSecondaryText.text = buildString {
            append("起点: ${formatPoint(anchorPoint)}\n")
            append("当前位置: ${formatPoint(display)}\n")
            append("航向: ${formatHeading(state)}    最新 GNSS 精度: ${formatAccuracy(latestGnssAccuracyMeters)}\n")
            append("保存目录: ${lastSessionDirectoryName ?: "未保存"}\n")
            append("导入轨迹: ${formatImportedTrackSummary()}")
        }
    }

    private fun updateSensorPage() {
        val state = pdrProcessor.snapshot()
        binding.sensorActionSummaryText.text = buildString {
            appendLine("保存状态: ${if (isSavingSession) "正在写入 ${sessionFiles?.sessionDirectory?.name}" else if (isRunning) "采集中，未保存" else "空闲"}")
            appendLine("最近会话: ${lastSessionDirectoryName ?: "无"}")
            append("后处理导入: ${formatImportedTrackSummary()}")
        }
        binding.accChartLabel.text = String.format(Locale.US, "加速度计  m/s²\nX  %.3f    Y  %.3f    Z  %.3f    精度  %d", lastAcc[0], lastAcc[1], lastAcc[2], accAccuracy)
        binding.gyrChartLabel.text = String.format(Locale.US, "陀螺仪  rad/s\nX  %.3f    Y  %.3f    Z  %.3f    精度  %d", lastGyr[0], lastGyr[1], lastGyr[2], gyrAccuracy)
        binding.magChartLabel.text = String.format(Locale.US, "磁力计  μT\nX  %.3f    Y  %.3f    Z  %.3f    精度  %d", lastMag[0], lastMag[1], lastMag[2], magAccuracy)
        binding.pdrCurveLabel.text = String.format(Locale.US, "PDR趋势\n状态  %s    模式  %s    参数  身高 %.0f cm\n步数  %d    距离  %.2f m    局部坐标  (%.2f, %.2f) m\n航向  %s    步长  %.2f m", if (isRunning) "运行中" else "空闲", modeName(currentMode), currentHeightCm, state.steps, state.totalDistanceMeters, state.positionXMeters, state.positionYMeters, formatHeading(state), state.latestStepLengthMeters)
        binding.accChartView.submitData(snapshotHistory(accHistoryX), snapshotHistory(accHistoryY), snapshotHistory(accHistoryZ))
        binding.gyrChartView.submitData(snapshotHistory(gyrHistoryX), snapshotHistory(gyrHistoryY), snapshotHistory(gyrHistoryZ))
        binding.magChartView.submitData(snapshotHistory(magHistoryX), snapshotHistory(magHistoryY), snapshotHistory(magHistoryZ))
        binding.pdrChartView.submitData(snapshotHistory(pdrHistoryMotion), snapshotHistory(pdrHistorySteps), snapshotHistory(pdrHistoryHeading))
    }

    private fun updateReferencePage() {
        val latestConstellations = satellites.groupBy { constellationName(it.constellationType) }.mapValues { it.value.size }
        val distribution = if (latestConstellations.isEmpty()) "暂无卫星数据" else latestConstellations.entries.joinToString("  ") { "${it.key}:${it.value}" }
        binding.referenceSummaryText.text = buildString {
            appendLine("可见卫星: $visibleSatellites")
            appendLine("参与定位: $usedSatellites")
            appendLine("星座分布: $distribution")
            append("最新 GNSS 位置: ${formatPoint(latestGnssPoint)}")
        }
        binding.postureStatusText.text = computePostureSummary()
    }

    private fun updateSettingsPage() {
        Unit
    }

    private fun appendRawRecord(tag: String, eventTimestampNs: Long, values: FloatArray, snapshot: PdrState) {
        val headingDeg = if (snapshot.headingReady) Math.toDegrees(snapshot.headingRad.toDouble()).toFloat() else Float.NaN
        val accuracy = when (tag) {
            "ACC" -> accAccuracy
            "GYR" -> gyrAccuracy
            else -> magAccuracy
        }
        csvWriter.appendRaw(RawSensorRecord(tag, System.currentTimeMillis(), eventTimestampNs, values[0], values[1], values[2], accuracy, headingDeg, snapshot.steps, snapshot.positionXMeters, snapshot.positionYMeters))
    }

    private fun applyHeightSetting() {
        val heightValue = binding.heightInput.text.toString().trim().toFloatOrNull()
        if (heightValue == null || heightValue !in 120f..220f) {
            Toast.makeText(this, "请输入 120 到 220 之间的身高厘米值。", Toast.LENGTH_LONG).show()
            return
        }
        val scaleValue = binding.stepLengthScaleInput.text.toString().trim().toFloatOrNull()
        if (scaleValue == null || scaleValue !in 0.30f..1.50f) {
            Toast.makeText(this, "请输入 0.30 到 1.50 之间的步长缩放倍率。", Toast.LENGTH_LONG).show()
            return
        }
        currentHeightCm = heightValue
        currentStepLengthScale = scaleValue
        currentModelConfig = createHeightModelConfig(heightValue, scaleValue)
        pdrProcessor.setModelConfig(currentModelConfig)
        preferences.edit {
            putFloat(KEY_HEIGHT_CM, heightValue)
            putFloat(KEY_STEP_LENGTH_SCALE, scaleValue)
        }
        refreshAllPanels()
        Toast.makeText(this, "已应用模型参数：身高 ${formatHeightInput(heightValue)} cm，步长缩放 ${formatStepLengthScaleInput(scaleValue)}", Toast.LENGTH_SHORT).show()
    }

    private fun computePostureSummary(): String {
        val magnitude = sqrt(lastAcc[0] * lastAcc[0] + lastAcc[1] * lastAcc[1] + lastAcc[2] * lastAcc[2])
        if (magnitude < 0.1f) return "等待加速度计数据。"
        val nx = lastAcc[0] / magnitude
        val ny = lastAcc[1] / magnitude
        val nz = lastAcc[2] / magnitude
        val state = pdrProcessor.snapshot()
        val pitchDeg = Math.toDegrees(activePitchRad(state).toDouble()).toFloat()
        val rollDeg = Math.toDegrees(activeRollRad(state).toDouble()).toFloat()
        val posture = when {
            abs(nz) >= 0.82f -> if (nz > 0f) "平放朝上" else "平放朝下"
            abs(nx) >= 0.82f || abs(ny) >= 0.82f -> "近似竖持"
            else -> "倾斜持机"
        }
        return buildString {
            appendLine("姿态状态: $posture")
            appendLine(String.format(Locale.US, "Pitch %.1f°    Roll %.1f°", pitchDeg, rollDeg))
            appendLine("航向估计: ${formatHeading(state)}")
            append("说明: 当前姿态角来自九轴 AHRS，持机分类仍使用重力方向做轻量判断。")
        }
    }

    private fun importTrackFile(uri: Uri) {
        try {
            val result = ImportedTrackParser.parse(this, uri, currentHeightCm, currentStepLengthScale)
            clearImportedTrack()
            importedTrackResult = result
            cacheImportedTracks(result)
            if (anchorPoint == null && result.anchorPoint != null) anchorPoint = result.anchorPoint
            hasCenteredMap = false
            binding.bottomTabs.getTabAt(0)?.select()
            redrawMap(resolveDisplayPoint())
            refreshAllPanels()
            Toast.makeText(this, "已导入 ${result.fileName}，当前模式轨迹点 ${result.pointCountForMode(currentMode)}", Toast.LENGTH_LONG).show()
        } catch (error: IllegalStateException) {
            Toast.makeText(this, error.message ?: "导入失败", Toast.LENGTH_LONG).show()
        } catch (error: Throwable) {
            Toast.makeText(this, "导入失败：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatImportedTrackSummary(): String {
        val result = importedTrackResult ?: return "未导入"
        return buildString {
            append(result.fileName)
            append("  |  ")
            append(result.sourceType)
            append("  |  当前模式轨迹点 ")
            append(result.pointCountForMode(currentMode))
            if (result.postProcessed) append("  |  离线后处理")
        }
    }

    private fun updateDisplayAttitude(sensorType: Int, values: FloatArray, timestampNs: Long) {
        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> displayAhrsEstimator.updateAccelerometer(values)
            Sensor.TYPE_GYROSCOPE -> displayAhrsEstimator.updateGyroscope(values, timestampNs)
            Sensor.TYPE_MAGNETIC_FIELD -> displayAhrsEstimator.updateMagnetometer(values)
        }
        val sample = displayAhrsEstimator.snapshot()
        if (!sample.ready) {
            displayHeadingReady = false
            return
        }
        val measuredHeading = AngleUtils.normalizeRadians(
            sample.yawRad + displayMagneticDeclinationRad + Math.PI.toFloat()
        )
        displayHeadingRad = if (displayHeadingReady) {
            AngleUtils.smoothAngle(displayHeadingRad, measuredHeading, DISPLAY_HEADING_WEIGHT)
        } else {
            measuredHeading
        }
        displayPitchRad = sample.pitchRad
        displayRollRad = sample.rollRad
        displayHeadingReady = true
    }

    private fun updateDisplayReferenceLocation(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double = 0.0,
        timeMillis: Long = System.currentTimeMillis()
    ) {
        displayMagneticDeclinationRad = GeomagneticHelper.declinationRadians(latitude, longitude, altitudeMeters, timeMillis)
    }

    private fun activeHeadingRad(state: PdrState = pdrProcessor.snapshot()): Float? = when {
        isRunning && state.headingReady -> state.headingRad
        displayHeadingReady -> displayHeadingRad
        state.headingReady -> state.headingRad
        else -> null
    }

    private fun activePitchRad(state: PdrState): Float = when {
        isRunning || state.headingReady -> state.pitchRad
        displayHeadingReady -> displayPitchRad
        else -> 0.0f
    }

    private fun activeRollRad(state: PdrState): Float = when {
        isRunning || state.headingReady -> state.rollRad
        displayHeadingReady -> displayRollRad
        else -> 0.0f
    }

    private fun shouldRedrawMapForHeadingChange(): Boolean {
        if (!hasCenteredMap) return false
        if (resolveDisplayPoint() == null) return false
        val headingRad = activeHeadingRad() ?: return false
        if (!lastRenderedHeadingRad.isFinite()) return true
        val deltaDegrees = Math.toDegrees(abs(AngleUtils.shortestDelta(lastRenderedHeadingRad, headingRad)).toDouble())
        return deltaDegrees >= MARKER_HEADING_REDRAW_THRESHOLD_DEG
    }

    private fun syncPdrExternalAttitude() {
        pdrProcessor.setExternalAttitude(
            headingRad = displayHeadingRad,
            headingReady = displayHeadingReady,
            pitchRad = displayPitchRad,
            rollRad = displayRollRad
        )
    }

    private fun formatPoint(point: GPSPoint?): String = if (point == null) "未设置" else String.format(Locale.US, "%.6f, %.6f", point.lat, point.lon)
    private fun formatAccuracy(value: Float): String = if (value.isFinite()) String.format(Locale.US, "%.1f m", value) else "未知"
    private fun formatHeading(state: PdrState): String {
        val headingRad = activeHeadingRad(state) ?: return "未就绪"
        return String.format(Locale.US, "%.1f°", Math.toDegrees(headingRad.toDouble()))
    }
    private fun formatHeightInput(heightCm: Float): String = if (heightCm % 1f == 0f) heightCm.toInt().toString() else String.format(Locale.US, "%.1f", heightCm)
    private fun formatStepLengthScaleInput(scale: Float): String = String.format(Locale.US, "%.2f", scale)
    private fun modeName(mode: NavigationMode): String = when (mode) {
        NavigationMode.PDR -> "PDR"
        NavigationMode.GNSS -> "GNSS"
        NavigationMode.HYBRID -> "PDR+GNSS"
    }
    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "GALILEO"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "UNKNOWN"
    }

    private fun copyValues(source: FloatArray, target: FloatArray) {
        target[0] = source[0]
        target[1] = source[1]
        target[2] = source[2]
    }

    private fun createBitmapDescriptor(drawableResId: Int): BitmapDescriptor {
        val drawable = requireNotNull(AppCompatResources.getDrawable(this, drawableResId)) {
            "Drawable resource $drawableResId not found."
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED && coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationServices()
                fetchLastKnownLocation()?.let { handleGnssLocation(it, true) }
                Toast.makeText(this, "定位权限已授予。", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "未授予定位权限，GNSS 与融合模式将不可用。", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun appendHistory(history: ArrayDeque<Float>, value: Float) {
        history.addLast(value)
        while (history.size > HISTORY_SIZE) history.removeFirst()
    }

    private fun snapshotHistory(history: ArrayDeque<Float>): List<Float> = history.toList()

    private fun clearAllHistory() {
        accHistoryX.clear(); accHistoryY.clear(); accHistoryZ.clear()
        gyrHistoryX.clear(); gyrHistoryY.clear(); gyrHistoryZ.clear()
        magHistoryX.clear(); magHistoryY.clear(); magHistoryZ.clear()
        clearPdrHistoryOnly()
    }

    private fun clearPdrHistoryOnly() {
        pdrHistoryMotion.clear(); pdrHistorySteps.clear(); pdrHistoryHeading.clear()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val SENSOR_PERIOD_US = 20_000
        private const val LOCATION_PERMISSION_REQUEST = 2001
        private const val HISTORY_SIZE = 90
        private const val PREFS_NAME = "imu_pdr_preferences"
        private const val KEY_HEIGHT_CM = "height_cm"
        private const val KEY_STEP_LENGTH_SCALE = "step_length_scale"
        private const val KEY_DRAW_TRAJECTORY = "draw_trajectory"
        private const val KEY_SHOW_IMPORTED_TRACK = "show_imported_track"
        private const val KEY_NAVIGATION_MODE = "navigation_mode"
        private const val DEFAULT_HEIGHT_CM = 180f
        private const val DEFAULT_STEP_LENGTH_SCALE = 0.67f
        private const val ANCHOR_MAX_ACCURACY_METERS = 40f
        private const val LAST_KNOWN_MAX_AGE_MS = 15_000L
        private const val DISPLAY_HEADING_WEIGHT = 0.32f
        private const val MARKER_HEADING_REDRAW_THRESHOLD_DEG = 3.0
    }
}
