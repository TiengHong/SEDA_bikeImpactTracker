package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.BluetoothBleService
import com.example.bluetooth.ConnectionState
import com.example.data.AppDatabase
import com.example.data.ImpactRecord
import com.example.data.ImpactRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = ImpactRepository(database.impactDao())

    // Bluetooth Service
    val bluetoothService = BluetoothBleService(application)

    // Expose local DB records
    val allImpacts: StateFlow<List<ImpactRecord>> = repository.allImpacts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Supabase Sync Client
    val supabaseSyncManager = com.example.data.SupabaseSyncManager(application)

    // Supabase UI States
    val supabaseUrl = MutableStateFlow(supabaseSyncManager.getSupabaseUrl())
    val supabaseAnonKey = MutableStateFlow(supabaseSyncManager.getSupabaseAnonKey())
    val isSyncingToCloud = MutableStateFlow(false)
    val syncResultMessage = MutableStateFlow<String?>(null)

    // UI Interactive States
    val connectionState: StateFlow<ConnectionState> = bluetoothService.connectionState
    val isSimulator: StateFlow<Boolean> = bluetoothService.isSimulator
    val terminalLogs: StateFlow<List<String>> = bluetoothService.terminalLogs

    // Scanned BLE Devices
    val pairedDevices: StateFlow<List<Map<String, String>>> = bluetoothService.scannedDevicesFlow

    // Dialog state for clicked point popup details
    private val _selectedRecordForDetail = MutableStateFlow<ImpactRecord?>(null)
    val selectedRecordForDetail: StateFlow<ImpactRecord?> = _selectedRecordForDetail

    // Coordinates of current map focus
    private val _mapTargetLocation = MutableStateFlow<Pair<Double, Double>>(Pair(37.1806928, 127.040918)) // Default to School
    val mapTargetLocation: StateFlow<Pair<Double, Double>> = _mapTargetLocation

    init {
        // Collect real-time records from Bluetooth and persist to Room database
        viewModelScope.launch {
            bluetoothService.receivedRecordFlow.collect { record ->
                val id = repository.insert(record)
                // Center Map on real-time impact place
                _mapTargetLocation.value = Pair(record.latitude, record.longitude)
                bluetoothService.logTerminal("Database", "Saved Realtime Log with ID #$id to Room DB")

                // Auto-upload real-time event to Supabase Cloud if configured
                viewModelScope.launch {
                    val result = supabaseSyncManager.uploadRecord(record)
                    result.onSuccess {
                        bluetoothService.logTerminal("CloudSync", "Auto-uploaded realtime log #$id to Supabase cloud.")
                    }.onFailure { e ->
                        bluetoothService.logTerminal("CloudSync_Warning", "Auto-upload to cloud bypassed/failed: ${e.message}")
                    }
                }
            }
        }

        // Collect batch offline records and persist, then transmit ACK_DATA to ESP32
        viewModelScope.launch {
            bluetoothService.receivedBatchFlow.collect { batchList ->
                try {
                    repository.insertBatch(batchList)
                    bluetoothService.logTerminal("Database", "Saved Batch Sync (${batchList.size} events) to Room DB.")
                    bluetoothService.writeCommand("ACK_DATA")

                    // Auto-upload synchronized batch to Supabase Cloud if configured
                    viewModelScope.launch {
                        val result = supabaseSyncManager.uploadBatch(batchList)
                        result.onSuccess { count ->
                            bluetoothService.logTerminal("CloudSync", "Auto-uploaded batch sync ($count records) to Supabase cloud.")
                        }.onFailure { e ->
                            bluetoothService.logTerminal("CloudSync_Warning", "Batch cloud sync failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    bluetoothService.logTerminal("Database_Error", "Failed to save batch logs: ${e.message}")
                }
            }
        }

        refreshPairedDevices()
        fetchFromSupabaseAndSync()
    }

    fun fetchFromSupabaseAndSync() {
        viewModelScope.launch {
            bluetoothService.logTerminal("CloudSync", "Connecting to Supabase DB to fetch initial records...")
            val result = supabaseSyncManager.fetchAllRecords()
            result.onSuccess { remoteRecords ->
                if (remoteRecords.isNotEmpty()) {
                    repository.insertBatch(remoteRecords)
                    bluetoothService.logTerminal("CloudSync", "Loaded ${remoteRecords.size} records from Supabase DB into app.")
                    _mapTargetLocation.value = Pair(remoteRecords.first().latitude, remoteRecords.first().longitude)
                } else {
                    bluetoothService.logTerminal("CloudSync", "Supabase DB connection successful (0 remote records found).")
                }
            }.onFailure { e ->
                bluetoothService.logTerminal("CloudSync_Warning", "Failed to load records from Supabase: ${e.message}")
            }
        }
    }

    fun saveSupabaseCredentials(url: String, anonKey: String) {
        supabaseSyncManager.saveCredentials(url, anonKey)
        supabaseUrl.value = supabaseSyncManager.getSupabaseUrl()
        supabaseAnonKey.value = supabaseSyncManager.getSupabaseAnonKey()
        syncResultMessage.value = "Supabase credentials updated! Fetching records..."
        bluetoothService.logTerminal("CloudSync", "Supabase endpoint and credentials updated.")
        fetchFromSupabaseAndSync()
    }

    fun clearSyncMessage() {
        syncResultMessage.value = null
    }

    fun syncAllToSupabase() {
        viewModelScope.launch {
            isSyncingToCloud.value = true
            syncResultMessage.value = "Starting manual Cloud synchronization..."
            val records = allImpacts.value
            if (records.isEmpty()) {
                syncResultMessage.value = "No records found in local database to sync."
                isSyncingToCloud.value = false
                return@launch
            }

            val result = supabaseSyncManager.uploadBatch(records)
            result.onSuccess { count ->
                syncResultMessage.value = "Successfully synced $count records to Supabase Cloud!"
                bluetoothService.logTerminal("CloudSync", "Manual full sync succeeded: $count records uploaded.")
            }.onFailure { error ->
                syncResultMessage.value = "Cloud Sync Failed: ${error.localizedMessage}"
                bluetoothService.logTerminal("CloudSync_Error", "Manual full sync failed: ${error.message}")
            }
            isSyncingToCloud.value = false
        }
    }

    fun refreshPairedDevices() {
        bluetoothService.startBleScan()
    }

    fun toggleSimulator(enabled: Boolean) {
        bluetoothService.setSimulatorMode(enabled)
    }

    fun connectDevice(macAddress: String) {
        bluetoothService.connect(macAddress)
    }

    fun disconnectDevice() {
        bluetoothService.disconnect()
    }

    fun clearAllData(clearSupabase: Boolean = true) {
        viewModelScope.launch {
            repository.clearAll()
            bluetoothService.logTerminal("Database", "All logs purged from local Room DB.")

            if (clearSupabase) {
                bluetoothService.logTerminal("CloudSync", "Clearing all records from Supabase DB...")
                val result = supabaseSyncManager.clearAllRecords()
                result.onSuccess {
                    bluetoothService.logTerminal("CloudSync", "Supabase DB successfully cleared.")
                    syncResultMessage.value = "All records deleted from local DB & Supabase DB!"
                }.onFailure { e ->
                    bluetoothService.logTerminal("CloudSync_Error", "Failed to clear Supabase DB: ${e.message}")
                    syncResultMessage.value = "Local DB cleared, but Supabase error: ${e.message}"
                }
            }
        }
    }

    fun showRecordDetail(record: ImpactRecord?) {
        _selectedRecordForDetail.value = record
        if (record != null) {
            _mapTargetLocation.value = Pair(record.latitude, record.longitude)
        }
    }

    fun updateMapTarget(lat: Double, lng: Double) {
        _mapTargetLocation.value = Pair(lat, lng)
    }

    // Interactive SIMULATION Trigger
    fun simulateBatchLogs() {
        viewModelScope.launch {
            bluetoothService.simulateReceiveBatchRecords()
        }
    }

    fun simulateRealtimeShock(lat: Double, lng: Double, gForce: Float) {
        bluetoothService.triggerSimulatedRealtimeImpact(lat, lng, gForce)
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothService.disconnect()
    }
}
