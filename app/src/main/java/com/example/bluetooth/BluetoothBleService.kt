package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.ImpactRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Syncing : ConnectionState()
    class Error(val message: String) : ConnectionState()
}

class BluetoothBleService(private val context: Context) {

    companion object {
        // Nordic UART Service (NUS) UUIDs
        private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val CHARACTERISTIC_UUID_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Write
        private val CHARACTERISTIC_UUID_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Notify
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _isSimulator = MutableStateFlow(false)
    val isSimulator: StateFlow<Boolean> = _isSimulator

    private val _terminalLogs = MutableStateFlow<List<String>>(emptyList())
    val terminalLogs: StateFlow<List<String>> = _terminalLogs

    // Scanned BLE devices flow (list of map for UI compatibility)
    private val _scannedDevices = MutableStateFlow<List<Map<String, String>>>(emptyList())
    val scannedDevicesFlow: StateFlow<List<Map<String, String>>> = _scannedDevices

    // Emitting packets for database storage
    private val _receivedRecordFlow = MutableSharedFlow<ImpactRecord>()
    val receivedRecordFlow: SharedFlow<ImpactRecord> = _receivedRecordFlow

    private val _receivedBatchFlow = MutableSharedFlow<List<ImpactRecord>>()
    val receivedBatchFlow: SharedFlow<List<ImpactRecord>> = _receivedBatchFlow

    // GATT and Connection state variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    
    private var connectionJob: Job? = null
    private var simulationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    // Rx stream parsing buffer
    private val rxBuffer = StringBuilder()

    // Batch synchronization states
    private var isSyncingBatch = false
    private val tempBatchList = mutableListOf<ImpactRecord>()

    // Device Location Overriding
    private var latestDeviceLocation: Pair<Double, Double>? = null

    // Bicycle Proximity Warning States
    private var lastWarningTriggerTime = 0L
    private val WARNING_COOLDOWN_MS = 15000L // 15 seconds cooldown between alerts

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestDeviceLocation = Pair(location.latitude, location.longitude)
            Log.d("BluetoothBleService", "Device Location updated to: ${location.latitude}, ${location.longitude}")
            checkBicycleProximityWarning(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun calculateDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun playWarningSound() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            coroutineScope.launch {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                delay(250)
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            }
        } catch (e: Exception) {
            Log.e("BluetoothBleService", "Error playing warning sound: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Bicycle Safety Warning"
            val descriptionText = "Warnings for upcoming road impact/shock areas"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("BICYCLE_SAFETY_CHANNEL", name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showWarningNotification(matchCount: Int) {
        createNotificationChannel()
        val builder = NotificationCompat.Builder(context, "BICYCLE_SAFETY_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ 전방 도로 충격 감지 경고")
            .setContentText("앞쪽 10m 내에 충격 기록이 ${matchCount}번 이상 등록된 위험 지점이 있습니다. 서행하세요!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(4242, builder.build())
        } catch (e: Exception) {
            Log.e("BluetoothBleService", "Failed to show notification: ${e.message}")
        }
    }

    private fun checkBicycleProximityWarning(location: Location) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastWarningTriggerTime < WARNING_COOLDOWN_MS) {
            return // Still in cooldown
        }

        coroutineScope.launch {
            try {
                val currentLat = location.latitude
                val currentLng = location.longitude
                
                val checkPoints = mutableListOf<Pair<Double, Double>>()
                checkPoints.add(Pair(currentLat, currentLng))
                
                // If moving, project a point 10 meters ahead using bearing
                if (location.hasBearing() || location.speed > 0.8f) {
                    val bearingDeg = if (location.hasBearing()) location.bearing else 0.0f
                    if (bearingDeg != 0.0f) {
                        val bearingRad = Math.toRadians(bearingDeg.toDouble())
                        val d = 10.0 // meters ahead
                        val deltaLat = (d * Math.cos(bearingRad)) / 111111.0
                        val deltaLng = (d * Math.sin(bearingRad)) / (111111.0 * Math.cos(Math.toRadians(currentLat)))
                        checkPoints.add(Pair(currentLat + deltaLat, currentLng + deltaLng))
                    }
                }

                // Query database for all historic records
                val dao = AppDatabase.getDatabase(context).impactDao()
                val records = dao.getAllImpactsList()

                // Count unique records within 10m of our current or projected path
                var matchCount = 0
                for (record in records) {
                    for (pt in checkPoints) {
                        val dist = calculateDistanceInMeters(pt.first, pt.second, record.latitude, record.longitude)
                        if (dist <= 10.0) {
                            matchCount++
                            break // Count each historic record at most once
                        }
                    }
                }

                if (matchCount >= 3) {
                    lastWarningTriggerTime = currentTime
                    logTerminal("SAFETY_WARN", "⚠️ WARNING: Detected $matchCount historic shocks within 10m! Triggering alarm.")
                    playWarningSound()
                    showWarningNotification(matchCount)
                }
            } catch (e: Exception) {
                Log.e("BluetoothBleService", "Failed to perform bicycle proximity check", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDeviceLocationUpdates() {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        if (!hasFine && !hasCoarse) {
            logTerminal("System", "Android GPS Permission not granted. Cannot override coordinates.")
            return
        }

        try {
            // Get cached coordinates immediately
            val gpsLoc = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null

            val netLoc = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            val bestLoc = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                netLoc != null -> netLoc
                else -> null
            }

            if (bestLoc != null) {
                latestDeviceLocation = Pair(bestLoc.latitude, bestLoc.longitude)
                logTerminal("System", "Android Device GPS ready (Cached): $latestDeviceLocation")
            } else {
                logTerminal("System", "No cached GPS location. Requesting fresh GPS updates...")
            }

            // Register for active updates
            handler.post {
                try {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            2000L,
                            1f,
                            locationListener,
                            Looper.getMainLooper()
                        )
                    }
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            2000L,
                            1f,
                            locationListener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e("BluetoothBleService", "Location Permission revoked", e)
                } catch (e: Exception) {
                    Log.e("BluetoothBleService", "Failed to register location updates", e)
                }
            }
        } catch (e: Exception) {
            logTerminal("System_Error", "Android GPS initialization failed: ${e.message}")
        }
    }

    private fun stopDeviceLocationUpdates() {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager.removeUpdates(locationListener)
            logTerminal("System", "Android Device GPS tracking stopped.")
        } catch (e: Exception) {
            Log.e("BluetoothBleService", "Failed to remove location updates", e)
        }
    }

    private fun getQuickDeviceLocation(): Pair<Double, Double>? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        try {
            val gpsLoc = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null

            val netLoc = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            val bestLoc = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                netLoc != null -> netLoc
                else -> null
            }
            if (bestLoc != null) {
                return Pair(bestLoc.latitude, bestLoc.longitude)
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    init {
        logTerminal("System", "Bluetooth BLE Service Initialized. Mode: Physical ESP32 (Default)")
    }

    fun setSimulatorMode(enabled: Boolean) {
        _isSimulator.value = enabled
        if (connectionState.value !is ConnectionState.Disconnected) {
            disconnect()
        }
        logTerminal("System", "Mode switched to: ${if (enabled) "ESP32 Simulator" else "Physical ESP32 BLE Device"}")
    }

    fun logTerminal(tag: String, text: String) {
        val formattedLog = "[${System.currentTimeMillis().let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) }}] ($tag) $text"
        val current = _terminalLogs.value.toMutableList()
        current.add(formattedLog)
        if (current.size > 150) {
            current.removeAt(0)
        }
        _terminalLogs.value = current
    }

    fun clearLogs() {
        _terminalLogs.value = emptyList()
    }

    // BLE Scanning Callbacks
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
            val address = device.address

            // Map and update state
            val currentList = _scannedDevices.value.toMutableList()
            if (currentList.none { it["address"] == address }) {
                currentList.add(mapOf("name" to name, "address" to address))
                _scannedDevices.value = currentList
                logTerminal("Bluetooth", "Discovered: $name ($address)")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logTerminal("Bluetooth_Error", "BLE scan failed with code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            logTerminal("Bluetooth", "Error: Bluetooth is disabled or not supported on this device.")
            return
        }

        if (isScanning) return
        _scannedDevices.value = emptyList()
        logTerminal("Bluetooth", "Starting BLE Scan for ESP32_Vibe_Tracker...")

        try {
            isScanning = true
            bluetoothLeScanner?.startScan(scanCallback)
            
            // Stop scanning automatically after 10 seconds to conserve battery
            handler.postDelayed({
                stopBleScan()
            }, 10000)
        } catch (e: Exception) {
            logTerminal("Bluetooth_Error", "Scan failed: ${e.message}")
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        if (!isScanning) return
        logTerminal("Bluetooth", "Scan stopped.")
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e("Bluetooth", "Failed to stop BLE scan", e)
        }
        isScanning = false
    }

    fun connect(macAddress: String) {
        disconnect()
        if (_isSimulator.value) {
            connectSimulated()
        } else {
            connectPhysical(macAddress)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected

        // Cancel connection job
        connectionJob?.cancel()
        connectionJob = null

        // Cancel simulation job
        simulationJob?.cancel()
        simulationJob = null

        // Stop device location updates
        stopDeviceLocationUpdates()

        // Close GATT
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e("Bluetooth", "Failed to close GATT", e)
        }
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null

        rxBuffer.clear()
        logTerminal("System", "Connection terminated.")
    }

    // PHYSICAL CONNECTION FLOW
    @SuppressLint("MissingPermission")
    private fun connectPhysical(macAddress: String) {
        if (bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth not supported.")
            logTerminal("System", "Error: Bluetooth Hardware Not Detected")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is Disabled")
            logTerminal("System", "Error: Please enable Bluetooth first")
            return
        }

        _connectionState.value = ConnectionState.Connecting
        logTerminal("System", "Connecting to BLE device $macAddress...")
        stopBleScan()
        
        // Start device location tracking for real-time overriding
        startDeviceLocationUpdates()

        try {
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            logTerminal("Bluetooth_Error", "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.Error(e.message ?: "GATT initiation failed")
            disconnect()
        }
    }

    // GATT Callback Definitions
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    logTerminal("Bluetooth", "GATT connected! Discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    logTerminal("Bluetooth", "GATT disconnected.")
                    disconnect()
                }
            } else {
                logTerminal("Bluetooth_Error", "Connection status error: $status. Disconnecting...")
                disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logTerminal("Bluetooth", "Services discovered. Requesting larger MTU (512 bytes) for full packet transmission...")
                try {
                    val mtuRequestSuccess = gatt.requestMtu(512)
                    logTerminal("Bluetooth", "MTU Request trigger: $mtuRequestSuccess")
                } catch (e: Exception) {
                    logTerminal("Bluetooth_Error", "Failed to invoke requestMtu: ${e.message}")
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID_RX)
                    txCharacteristic = service.getCharacteristic(CHARACTERISTIC_UUID_TX)

                    if (txCharacteristic != null) {
                        _connectionState.value = ConnectionState.Connected
                        logTerminal("Bluetooth", "NUS UART service found. Subscribing to Notifications...")
                        
                        // Enable notifications locally
                        val localSuccess = gatt.setCharacteristicNotification(txCharacteristic, true)
                        logTerminal("Bluetooth", "Local notifications setup: $localSuccess")

                        // Enable notifications on the physical CCCD descriptor with a 250ms delay to let the GATT engine settle
                        handler.postDelayed({
                            val descriptor = txCharacteristic!!.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                            if (descriptor != null) {
                                val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    val res = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    res == 0 // 0 is BluetoothStatusCodes.SUCCESS
                                } else {
                                    @Suppress("DEPRECATION")
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(descriptor)
                                }
                                logTerminal("Bluetooth", "CCCD descriptor write request sent: $success")
                            } else {
                                logTerminal("Bluetooth_Error", "Could not locate standard CCCD descriptor.")
                            }
                        }, 250)
                    } else {
                        logTerminal("Bluetooth_Error", "NUS TX characteristic not found.")
                        disconnect()
                    }
                } else {
                    logTerminal("Bluetooth_Error", "Nordic NUS Service NOT found on device!")
                    disconnect()
                }
            } else {
                logTerminal("Bluetooth_Error", "Service discovery failed with status: $status")
                disconnect()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logTerminal("Bluetooth", "Notifications enabled successfully. Monitoring real-time telemetry...")
                
                // Once notifications are established, automatically query the ESP32 SPIFFS history logs
                handler.postDelayed({
                    logTerminal("Bluetooth", "Requesting offline accumulated data (REQ_DATA)...")
                    writeCommand("REQ_DATA")
                }, 500)
            } else {
                logTerminal("Bluetooth_Error", "Descriptor write failed: $status")
                disconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logTerminal("Bluetooth", "MTU size successfully changed to: $mtu bytes")
            } else {
                logTerminal("Bluetooth_Error", "MTU change failed: $status. Defaulting to standard MTU.")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value
            if (value != null && value.isNotEmpty()) {
                val text = String(value, Charsets.UTF_8)
                handleReceivedData(text)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isNotEmpty()) {
                val text = String(value, Charsets.UTF_8)
                handleReceivedData(text)
            }
        }
    }

    private fun handleReceivedData(chunk: String) {
        logTerminal("RX_RAW", chunk.trim())
        rxBuffer.append(chunk)
        
        // Handle both carriage return (\r) and newline (\n) as line delimiters
        var delimiterIndex = rxBuffer.indexOf("\n")
        if (delimiterIndex == -1) {
            delimiterIndex = rxBuffer.indexOf("\r")
        }
        
        while (delimiterIndex != -1) {
            val line = rxBuffer.substring(0, delimiterIndex).trim()
            rxBuffer.delete(0, delimiterIndex + 1)
            if (line.isNotEmpty()) {
                parseAndProcessLine(line)
            }
            delimiterIndex = rxBuffer.indexOf("\n")
            if (delimiterIndex == -1) {
                delimiterIndex = rxBuffer.indexOf("\r")
            }
        }

        // ESP32 fallback: if the buffer has no trailing delimiter but starts with "RT,",
        // protect against packet loss where multiple headers are joined, or partial transmission.
        val remaining = rxBuffer.toString().trim()
        if (remaining.startsWith("RT,")) {
            val rtCount = remaining.split("RT,").size - 1
            if (rtCount > 1) {
                // If there are multiple "RT," headers, discard the older corrupted stream and keep the latest
                val lastRtIndex = rxBuffer.lastIndexOf("RT,")
                if (lastRtIndex != -1) {
                    rxBuffer.delete(0, lastRtIndex)
                }
            } else {
                val tokens = remaining.split(",")
                // Standard RT packet has 3 (RT, timestamp, G) or 5 (RT, timestamp, lat, lng, G) elements.
                // Crucially, only parse if the final field (the G value) is fully received (i.e. is a valid float).
                if (tokens.size == 3 || tokens.size == 5) {
                    val lastToken = tokens.last().trim()
                    val isGForceValid = lastToken.toFloatOrNull() != null
                    if (isGForceValid) {
                        parseAndProcessLine(remaining)
                        rxBuffer.clear()
                    }
                }
            }
        }
    }

    private fun parseAndProcessLine(trimmedLine: String) {
        logTerminal("RX", trimmedLine)

        when {
            trimmedLine == "===BATCH_START===" -> {
                isSyncingBatch = true
                tempBatchList.clear()
                _connectionState.value = ConnectionState.Syncing
                logTerminal("SYNC", "Batch historical sync started. Processing offline CSV rows...")
            }
            trimmedLine == "===BATCH_END===" -> {
                isSyncingBatch = false
                _connectionState.value = ConnectionState.Connected
                logTerminal("SYNC", "Batch historical sync finished. Extracted ${tempBatchList.size} records.")
                if (tempBatchList.isNotEmpty()) {
                    coroutineScope.launch {
                        _receivedBatchFlow.emit(tempBatchList.toList())
                    }
                } else {
                    writeCommand("ACK_DATA")
                }
            }
            trimmedLine == "CLEAR_SUCCESS" -> {
                logTerminal("SYNC", "ESP32 flash logs cleared successfully.")
            }
            trimmedLine == "CLEAR_FAILED" -> {
                logTerminal("SYNC_ERROR", "ESP32 failed to clear flash logs.")
            }
            isSyncingBatch -> {
                // Skip CSV headers
                if (trimmedLine.startsWith("Timestamp", ignoreCase = true)) return
                
                try {
                    val rawTokens = trimmedLine.split(",")
                    val tokens = rawTokens.map { it.trim() }.filter { it.isNotEmpty() }
                    if (tokens.size >= 4) {
                        val timestamp = tokens[0]
                        val lat = tokens[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                        val lng = tokens[2].replace(',', '.').toDoubleOrNull() ?: 0.0
                        val impactG = tokens[3].replace(',', '.').toFloatOrNull() ?: 1.0f

                        // Default to School fallback if coordinates are missing/zero
                        val finalLat = if (lat != 0.0) lat else 37.1806928
                        val finalLng = if (lng != 0.0) lng else 127.040918

                        tempBatchList.add(
                            ImpactRecord(
                                timestamp = timestamp,
                                latitude = finalLat,
                                longitude = finalLng,
                                impactG = impactG,
                                isRealtime = false
                            )
                        )
                    }
                } catch (e: Exception) {
                    logTerminal("SYNC_ERROR", "Failed to parse historical record: $trimmedLine. Error: ${e.message}")
                }
            }
            trimmedLine.startsWith("RT,") -> {
                try {
                    val rawTokens = trimmedLine.split(",")
                    val tokens = rawTokens.map { it.trim() }.filter { it.isNotEmpty() }
                    logTerminal("PARSED_TOKENS", "RT packet splits (size=${tokens.size}): ${tokens.joinToString(" | ")}")
                    
                    if (tokens.size >= 3) {
                        val timestamp = tokens.getOrNull(1) ?: "00:00:00"
                        
                        // Parse G-Force: scan backwards from the end of the list to find the first valid G-force float.
                        // This prevents alignment errors when fields are shifted or incomplete.
                        var impactG = 1.0f
                        var parseSuccess = false
                        for (i in tokens.indices.reversed()) {
                            val rawToken = tokens[i]
                            val cleanToken = rawToken.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
                            val tempG = cleanToken.toFloatOrNull()
                            if (tempG != null && tempG > 0.05f && tempG < 30.0f) {
                                impactG = tempG
                                parseSuccess = true
                                break
                            }
                        }
                        
                        if (!parseSuccess) {
                            logTerminal("PARSER_WARN", "Unable to extract a valid G value. Defaulting to 1.0f.")
                        } else {
                            logTerminal("PARSER_SUCCESS", "Successfully extracted G value: ${impactG}G")
                        }
                        
                        // Try parsing latitude/longitude if present and valid
                        val rawLat: Double
                        val rawLng: Double
                        if (tokens.size >= 5) {
                            val rawLatStr = tokens[2].replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
                            val rawLngStr = tokens[3].replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }
                            rawLat = rawLatStr.toDoubleOrNull() ?: 0.0
                            rawLng = rawLngStr.toDoubleOrNull() ?: 0.0
                        } else {
                            rawLat = 0.0
                            rawLng = 0.0
                        }

                        // Override with Android Device Location if available, or fallback gracefully
                        val activeLoc = latestDeviceLocation
                        val finalLat: Double
                        val finalLng: Double
                        val sourceStr: String

                        if (activeLoc != null && activeLoc.first != 0.0 && activeLoc.second != 0.0) {
                            finalLat = activeLoc.first
                            finalLng = activeLoc.second
                            sourceStr = "Android Device GPS (High Precision)"
                        } else {
                            val quickLoc = getQuickDeviceLocation()
                            if (quickLoc != null && quickLoc.first != 0.0 && quickLoc.second != 0.0) {
                                finalLat = quickLoc.first
                                finalLng = quickLoc.second
                                sourceStr = "Android Device GPS (Last Known)"
                            } else if (rawLat != 0.0 && rawLng != 0.0) {
                                finalLat = rawLat
                                finalLng = rawLng
                                sourceStr = "ESP32 GPS"
                            } else {
                                // Default fallback (School) so that data is visible on the map
                                finalLat = 37.1806928
                                finalLng = 127.040918
                                sourceStr = "No GPS (Default School)"
                            }
                        }

                        val record = ImpactRecord(
                            timestamp = timestamp,
                            latitude = finalLat,
                            longitude = finalLng,
                            impactG = impactG,
                            isRealtime = true
                        )
                        coroutineScope.launch {
                            _receivedRecordFlow.emit(record)
                        }
                        logTerminal("LIVE_DETECTION", "Realtime shock: ${record.impactG}G. Location from: $sourceStr ($finalLat, $finalLng)")
                    } else {
                        logTerminal("PARSE_ERROR", "RT Packet has too few fields: $trimmedLine")
                    }
                } catch (e: Exception) {
                    logTerminal("PARSE_ERROR", "Failed to parse RT packet: $trimmedLine. Error: ${e.message}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun writeCommand(command: String) {
        if (_isSimulator.value) {
            if (command.trim() == "ACK_DATA") {
                logTerminal("SIMULATOR_TX", "ACK received, clearing simulator flash...")
            }
            return
        }

        val gatt = bluetoothGatt
        val rxChar = rxCharacteristic
        if (gatt != null && rxChar != null) {
            coroutineScope.launch {
                try {
                    val formatted = "$command\n"
                    rxChar.value = formatted.toByteArray(Charsets.UTF_8)
                    gatt.writeCharacteristic(rxChar)
                    logTerminal("TX", command.trim())
                } catch (e: Exception) {
                    logTerminal("TX_ERROR", "Failed to write characteristic: ${e.message}")
                }
            }
        } else {
            logTerminal("TX_ERROR", "Cannot transmit: GATT or RX channel not ready.")
        }
    }

    // ==========================================
    // EMULATED ESP32 SIMULATOR (MOCK)
    // ==========================================
    private fun connectSimulated() {
        _connectionState.value = ConnectionState.Connecting
        logTerminal("SIMULATOR", "Initializing Virtual BLE GATT Service (NUS)...")
        
        // Start device location tracking for real-time overriding or fallback use
        startDeviceLocationUpdates()

        simulationJob = coroutineScope.launch {
            delay(1000)
            _connectionState.value = ConnectionState.Connected
            logTerminal("SIMULATOR_COMM", "Connected virtual BLE GATT Link.")
            logTerminal("SIMULATOR", "Switched to Standby RT mode. Monitoring real-time BLE bike shocks...")
        }
    }

    suspend fun simulateReceiveBatchRecords() {
        logTerminal("SIMULATOR_RX", "RCVD: ===BATCH_START===")
        delay(200)
        logTerminal("SIMULATOR_RX", "RCVD: Timestamp,Lat,Lng,Impact_G")
        delay(200)

        val baseTime = System.currentTimeMillis() - 3600000 * 2 // 2 hours ago
        val mockPoints = listOf(
            Triple(37.5113, 127.0016, 1.4f),  // Banpo
            Triple(37.5194, 127.0863, 0.8f),  // Jamsil Bridge
            Triple(37.5284, 127.0682, 3.2f),  // Ttukseom (moderate block)
            Triple(37.5372, 127.0374, 5.1f),  // Seoul Forest (severe bump!)
            Triple(37.5271, 126.9328, 2.8f),  // Yeouido
            Triple(37.5338, 126.9349, 1.2f)   // Mapo Bridge
        )

        val batchList = mutableListOf<ImpactRecord>()
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

        mockPoints.forEachIndexed { index, triple ->
            val timestamp = formatter.format(java.util.Date(baseTime + index * 15 * 60000))
            val csvLine = "$timestamp,${triple.first},${triple.second},${triple.third}"
            logTerminal("SIMULATOR_RX", "RCVD: $csvLine")

            batchList.add(
                ImpactRecord(
                    timestamp = timestamp,
                    latitude = triple.first,
                    longitude = triple.second,
                    impactG = triple.third,
                    isRealtime = false
                )
            )
            delay(150)
        }

        logTerminal("SIMULATOR_RX", "RCVD: ===BATCH_END===")
        delay(300)

        // Store to database
        _receivedBatchFlow.emit(batchList)
        logTerminal("SIMULATOR_TX", "SENT: ACK_DATA\\n")

        delay(400)
        logTerminal("SIMULATOR_RX", "RCVD: CLEAR_SUCCESS")
        _connectionState.value = ConnectionState.Connected
        logTerminal("SIMULATOR", "Switched to Standby RT mode. Monitoring real-time BLE bike shocks...")
    }

    fun triggerSimulatedRealtimeImpact(latitude: Double, longitude: Double, GValue: Float) {
        if (_connectionState.value !is ConnectionState.Connected && _connectionState.value !is ConnectionState.Syncing) {
            logTerminal("SIM_ALERT", "Cannot trigger RT impact. Simulator not connected.")
            return
        }

        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val timestamp = formatter.format(java.util.Date())

        val pStreamLine = "RT,$timestamp,$latitude,$longitude,$GValue"
        logTerminal("SIMULATOR_RX", "RCVD: $pStreamLine")

        val record = ImpactRecord(
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            impactG = GValue,
            isRealtime = true
        )

        coroutineScope.launch {
            _receivedRecordFlow.emit(record)
        }
        logTerminal("LIVE_DETECTION", "RT Impact Synced! Strength: ${GValue}G. Recorded: Lat=$latitude, Lng=$longitude.")
    }
}
