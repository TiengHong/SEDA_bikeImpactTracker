package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.bluetooth.ConnectionState
import com.example.data.ImpactRecord
import com.example.viewmodel.MainViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val config = LocalConfiguration.current

    val allImpacts by viewModel.allImpacts.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isSimulator by viewModel.isSimulator.collectAsState()
    val terminalLogs by viewModel.terminalLogs.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val mapTarget by viewModel.mapTargetLocation.collectAsState()
    val selectedRecord by viewModel.selectedRecordForDetail.collectAsState()

    val supabaseUrl by viewModel.supabaseUrl.collectAsState()
    val supabaseAnonKey by viewModel.supabaseAnonKey.collectAsState()
    val isSyncingToCloud by viewModel.isSyncingToCloud.collectAsState()
    val syncResultMessage by viewModel.syncResultMessage.collectAsState()

    var deviceLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var showDevicePickerDialog by remember { mutableStateOf(false) }
    var showClearAllConfirmation by remember { mutableStateOf(false) }

    // Check Permissions
    val requiredPermissions = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            list.add(Manifest.permission.BLUETOOTH)
            list.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+
            list.add("android.permission.POST_NOTIFICATIONS")
        }
        list
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            val allGranted = result.values.all { it }
            hasPermissions = allGranted
            if (allGranted) {
                viewModel.refreshPairedDevices()
                Toast.makeText(context, "Permissions Granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Location & Bluetooth permissions required for hardware pairing", Toast.LENGTH_LONG).show()
            }
        }
    )

    LaunchedEffect(hasPermissions) {
        if (!hasPermissions) {
            launcher.launch(requiredPermissions.toTypedArray())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBarHeader(
                connectionState = connectionState,
                isSimulator = isSimulator,
                onChooseDevice = {
                    viewModel.refreshPairedDevices()
                    showDevicePickerDialog = true
                },
                onDisconnect = { viewModel.disconnectDevice() },
                onSync = { viewModel.bluetoothService.writeCommand("REQ_DATA") },
                onClearESP32 = { viewModel.bluetoothService.writeCommand("ACK_DATA") }
            )
        }
    ) { padding ->
        val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            // Adaptive Two-Column Layout for Phone/Tablet Landscape Mode
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left Column: Interactive Map
                Card(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                        .padding(start = 12.dp, top = 6.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OSMMapView(
                            modifier = Modifier.fillMaxSize(),
                            impacts = allImpacts,
                            targetLocation = mapTarget,
                            onRecordClicked = { record -> viewModel.showRecordDetail(record) },
                            userLocation = deviceLocation
                        )
                        MapTelemetrySummary(
                            allImpacts = allImpacts,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        )
                        Card(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(42.dp)
                                .testTag("my_location_button_landscape"),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        requestDeviceLocation(context) { location ->
                                            deviceLocation = location
                                            viewModel.updateMapTarget(location.first, location.second)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "My Location",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Right Column: Tabbed Controls (Recorded Logs, Serial Terminal, Cloud Sync)
                DashboardControlTabs(
                    allImpacts = allImpacts,
                    connectionState = connectionState,
                    supabaseUrl = supabaseUrl,
                    supabaseAnonKey = supabaseAnonKey,
                    isSyncingToCloud = isSyncingToCloud,
                    syncResultMessage = syncResultMessage,
                    onSaveCredentials = { url, key -> viewModel.saveSupabaseCredentials(url, key) },
                    onSyncAllToSupabase = { viewModel.syncAllToSupabase() },
                    onClearSyncMessage = { viewModel.clearSyncMessage() },
                    onClearAll = { showClearAllConfirmation = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 12.dp, top = 6.dp, bottom = 12.dp),
                    onLogItemClick = { record -> viewModel.showRecordDetail(record) },
                    showTerminalTab = true,
                    terminalLogs = terminalLogs,
                    onClearTerminalLogs = { viewModel.bluetoothService.clearLogs() }
                )
            }
        } else {
            // Portrait Mobile Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Map Section (Top) - Balanced 1:1 ratio area for phone display
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                ) {
                    OSMMapView(
                        modifier = Modifier.fillMaxSize(),
                        impacts = allImpacts,
                        targetLocation = mapTarget,
                        onRecordClicked = { record -> viewModel.showRecordDetail(record) },
                        userLocation = deviceLocation
                    )
                    MapTelemetrySummary(
                        allImpacts = allImpacts,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    )
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(42.dp)
                            .testTag("my_location_button_portrait"),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    requestDeviceLocation(context) { location ->
                                        deviceLocation = location
                                        viewModel.updateMapTarget(location.first, location.second)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "My Location",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Controls and Logs Console (Bottom Split Screen)
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Terminal Log Console
                    Card(
                        modifier = Modifier.height(100.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131924)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        TerminalLogsView(logs = terminalLogs, onClear = { viewModel.bluetoothService.clearLogs() })
                    }

                    // Controllers
                    DashboardControlTabs(
                        allImpacts = allImpacts,
                        connectionState = connectionState,
                        supabaseUrl = supabaseUrl,
                        supabaseAnonKey = supabaseAnonKey,
                        isSyncingToCloud = isSyncingToCloud,
                        syncResultMessage = syncResultMessage,
                        onSaveCredentials = { url, key -> viewModel.saveSupabaseCredentials(url, key) },
                        onSyncAllToSupabase = { viewModel.syncAllToSupabase() },
                        onClearSyncMessage = { viewModel.clearSyncMessage() },
                        onClearAll = { showClearAllConfirmation = true },
                        modifier = Modifier.weight(1f),
                        onLogItemClick = { record -> viewModel.showRecordDetail(record) }
                    )
                }
            }
        }
    }

    // Modal dialogue popup for paired device list picker
    if (showDevicePickerDialog) {
        DeviceSelectionDialog(
            devices = pairedDevices,
            onDismiss = { showDevicePickerDialog = false },
            onSelectPhysical = { mac ->
                viewModel.toggleSimulator(false)
                viewModel.connectDevice(mac)
                showDevicePickerDialog = false
            }
        )
    }

    // Modal popup dialog containing precise details of selected G-vibration point
    selectedRecord?.let { record ->
        ImpactDetailsDialog(
            record = record,
            onDismiss = { viewModel.showRecordDetail(null) }
        )
    }

    // Confirmation dialog before clearing all logs
    if (showClearAllConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            title = {
                Text(
                    text = "데이터 전체 삭제 경고",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "앱 내 저장소 및 Supabase DB에 저장된 모든 충격 로그 데이터가 완전히 삭제됩니다.\n\n정말로 삭제하시겠습니까?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllConfirmation = false
                        viewModel.clearAllData(clearSupabase = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("삭제 동의", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearAllConfirmation = false }
                ) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun TopAppBarHeader(
    connectionState: ConnectionState,
    isSimulator: Boolean,
    onChooseDevice: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
    onClearESP32: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = when (connectionState) {
                    is ConnectionState.Disconnected -> "Disconnected • Choose a device provider"
                    is ConnectionState.Connecting -> "Establishing secure SPP link..."
                    is ConnectionState.Connected -> "Standby • Realtime stream active"
                    is ConnectionState.Syncing -> "Syncing • Exporting flash backup logs..."
                    is ConnectionState.Error -> "Connection error • Click connect to retry"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(16.dp))

            if (connectionState is ConnectionState.Disconnected || connectionState is ConnectionState.Error) {
                Button(
                    onClick = onChooseDevice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp).testTag("connect_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Link",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Connect ESP32",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (connectionState is ConnectionState.Connected) {
                        Button(
                            onClick = onSync,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp).testTag("sync_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSecondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Sync Flash",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondary
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        OutlinedButton(
                            onClick = onClearESP32,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(36.dp).testTag("clear_esp32_button"),
                            border = borderPaint()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Wipe ESP32",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Wipe ESP32",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.height(36.dp).testTag("disconnect_button"),
                        border = borderPaint()
                    ) {
                        Text(
                            text = "Disconnect",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun borderPaint() = ButtonDefaults.outlinedButtonBorder.copy()

@Composable
fun ConnectionStatusBadge(state: ConnectionState) {
    val (color, text) = when (state) {
        is ConnectionState.Disconnected -> Pair(Color(0xFF64748B), "OFFLINE")
        is ConnectionState.Connecting -> Pair(Color(0xFFEAB308), "LINKING")
        is ConnectionState.Connected -> Pair(Color(0xFF10B981), "STANDBY")
        is ConnectionState.Syncing -> Pair(Color(0xFF3B82F6), "SYNCING")
        is ConnectionState.Error -> Pair(Color(0xFFEF4444), "ERR")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun OSMMapView(
    modifier: Modifier = Modifier,
    impacts: List<ImpactRecord>,
    targetLocation: Pair<Double, Double>,
    onRecordClicked: (ImpactRecord) -> Unit,
    zoomLevel: Double = 16.5,
    userLocation: Pair<Double, Double>? = null
) {
    val context = LocalContext.current

    // Setup OSMDroid caching folder configuration
    val osmdroidBasePath = File(context.cacheDir, "osmdroid")
    Configuration.getInstance().osmdroidBasePath = osmdroidBasePath
    Configuration.getInstance().osmdroidTileCache = File(osmdroidBasePath, "tiles")
    Configuration.getInstance().userAgentValue = context.packageName

    AndroidView(
        modifier = modifier.testTag("osm_map_view"),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                controller.setZoom(zoomLevel)
                controller.setCenter(GeoPoint(targetLocation.first, targetLocation.second))
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            }
        },
        update = { mapView ->
            mapView.controller.animateTo(GeoPoint(targetLocation.first, targetLocation.second))
            
            // Clear prior overlays
            mapView.overlays.clear()

            // Repopulate overlays from reactive database updates
            impacts.forEach { record ->
                val (fillCol, strokeCol) = when {
                    record.impactG >= 4.0f -> {
                        Pair(
                            android.graphics.Color.argb(76, 239, 68, 68), // 30% alpha Red
                            android.graphics.Color.argb(150, 239, 68, 68) // 60% alpha Red
                        )
                    }
                    record.impactG >= 2.5f -> {
                        Pair(
                            android.graphics.Color.argb(76, 245, 158, 11), // 30% alpha Orange
                            android.graphics.Color.argb(150, 245, 158, 11) // 60% alpha Orange
                        )
                    }
                    else -> {
                        Pair(
                            android.graphics.Color.argb(76, 16, 185, 129), // 30% alpha Teal
                            android.graphics.Color.argb(150, 16, 185, 129) // 60% alpha Teal
                        )
                    }
                }

                // Draw 12m impact area circle overlay first so it stays underneath the clickable marker
                val errorCircle = org.osmdroid.views.overlay.Polygon(mapView).apply {
                    points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(GeoPoint(record.latitude, record.longitude), 12.0)
                    fillPaint.color = fillCol
                    outlinePaint.color = strokeCol
                    outlinePaint.strokeWidth = 2.0f
                }
                mapView.overlays.add(errorCircle)

                val marker = Marker(mapView).apply {
                    position = GeoPoint(record.latitude, record.longitude)
                    icon = MapHelper.createCircleMarkerIcon(context, record)
                    title = "충격 감지: ${String.format("%.1f", record.impactG)}G"
                    snippet = "기록 시간: ${record.timestamp}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { m, _ ->
                        m.showInfoWindow()
                        onRecordClicked(record)
                        true
                    }
                }
                mapView.overlays.add(marker)
            }

            // Draw current Android Device's GPS Location Blue Dot
            userLocation?.let { loc ->
                val userMarker = Marker(mapView).apply {
                    position = GeoPoint(loc.first, loc.second)
                    icon = MapHelper.createUserLocationIcon(context)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "내 위치"
                    setOnMarkerClickListener { m, _ ->
                        m.showInfoWindow()
                        true
                    }
                }
                mapView.overlays.add(userMarker)
            }

            mapView.invalidate()
        }
    )
}

@Composable
fun MapTelemetrySummary(allImpacts: List<ImpactRecord>, modifier: Modifier = Modifier) {
    val count = allImpacts.size
    val maxShock = allImpacts.maxOfOrNull { it.impactG } ?: 0.0f
    val severeCount = allImpacts.count { it.impactG >= 4.0f }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xEC0F172A)), // Semi-translucent dark slate
        shape = RoundedCornerShape(10.dp),
        border = borderPaint()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LOGS",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color.White.copy(alpha = 0.2f))
            )
            Column {
                Text(
                    text = "MAX SHOCK",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "${String.format("%.1f", maxShock)}G",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = if (maxShock >= 4.0f) Color(0xFFEF4444) else Color(0xFF10B981),
                    fontWeight = FontWeight.ExtraBold
                )
            }
            if (severeCount > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Color.White.copy(alpha = 0.2f))
                )
                Column {
                    Text(
                        text = "SEVERE",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444)
                    )
                    Text(
                        text = "$severeCount",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
fun TerminalLogsView(
    logs: List<String>,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()

    // Automatically auto-scroll terminal logs to the end when populated
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "UART SERIAL LOGS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFA1A1AA) // Gray-400
                )
            }
            Text(
                text = "CLEAR LOGS",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF3B82F6),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onClear() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0F172A), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "Listening for device messages...\nClick Link ESP32 to initiate data serial flow.",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { log ->
                        val color = when {
                            log.contains("(Error)") || log.contains("BT_ERROR") -> Color(0xFFEF4444)
                            log.contains("(TX)") || log.contains("SIMULATOR_TX") -> Color(0xFF3B82F6)
                            log.contains("(LIVE_DETECTION)") -> Color(0xFFF43F5E)
                            log.contains("(Database)") -> Color(0xFF10B981)
                            else -> Color(0xFFE2E8F0)
                        }
                        Text(
                            text = log,
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardControlTabs(
    allImpacts: List<ImpactRecord>,
    connectionState: ConnectionState,
    supabaseUrl: String,
    supabaseAnonKey: String,
    isSyncingToCloud: Boolean,
    syncResultMessage: String?,
    onSaveCredentials: (String, String) -> Unit,
    onSyncAllToSupabase: () -> Unit,
    onClearSyncMessage: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    onLogItemClick: (ImpactRecord) -> Unit,
    showTerminalTab: Boolean = false,
    terminalLogs: List<String> = emptyList(),
    onClearTerminalLogs: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        ) {
            TabHeader(
                text = "Recorded (${allImpacts.size})",
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f)
            )
            if (showTerminalTab) {
                TabHeader(
                    text = "Terminal",
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
                TabHeader(
                    text = "Cloud Sync",
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.weight(1f)
                )
            } else {
                TabHeader(
                    text = "Cloud Sync",
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            border = borderPaint()
        ) {
            if (showTerminalTab) {
                when (selectedTab) {
                    0 -> SavedRecordsTab(records = allImpacts, onClearAll = onClearAll, onItemClick = onLogItemClick)
                    1 -> TerminalLogsView(logs = terminalLogs, onClear = onClearTerminalLogs)
                    else -> CloudSyncTab(
                        supabaseUrl = supabaseUrl,
                        supabaseAnonKey = supabaseAnonKey,
                        isSyncingToCloud = isSyncingToCloud,
                        syncResultMessage = syncResultMessage,
                        onSaveCredentials = onSaveCredentials,
                        onSyncAllToSupabase = onSyncAllToSupabase,
                        onClearSyncMessage = onClearSyncMessage
                    )
                }
            } else {
                when (selectedTab) {
                    0 -> SavedRecordsTab(records = allImpacts, onClearAll = onClearAll, onItemClick = onLogItemClick)
                    else -> CloudSyncTab(
                        supabaseUrl = supabaseUrl,
                        supabaseAnonKey = supabaseAnonKey,
                        isSyncingToCloud = isSyncingToCloud,
                        syncResultMessage = syncResultMessage,
                        onSaveCredentials = onSaveCredentials,
                        onSyncAllToSupabase = onSyncAllToSupabase,
                        onClearSyncMessage = onClearSyncMessage
                    )
                }
            }
        }
    }
}

@Composable
fun TabHeader(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
    val textColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium

    Box(
        modifier = modifier
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SavedRecordsTab(
    records: List<ImpactRecord>,
    onClearAll: () -> Unit,
    onItemClick: (ImpactRecord) -> Unit
) {
    val sortedRecords = remember(records) {
        records.sortedWith(compareByDescending<ImpactRecord> { it.id }.thenByDescending { it.timestamp })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        if (sortedRecords.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No recorded impact logs found.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pair and sync records, or trigger simulations to map impacts.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent bike vibrations & anomalies",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "CLEAR ALL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clickable { onClearAll() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sortedRecords) { record ->
                    ImpactRecordRowItem(record = record, onClick = { onItemClick(record) })
                }
            }
        }
    }
}

@Composable
fun ImpactRecordRowItem(record: ImpactRecord, onClick: () -> Unit) {
    val (colorBadge, label) = when (record.severity) {
        "Severe" -> Pair(Color(0xFFEF4444), "SEVERE")
        "Moderate" -> Pair(Color(0xFFF59E0B), "MODERATE")
        else -> Pair(Color(0xFF14B8A6), "MILD")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // Visual circle with G printed
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colorBadge.copy(alpha = 0.15f))
                    .border(2.dp, colorBadge, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${String.format("%.1f", record.impactG)}G",
                    color = colorBadge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = record.timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Lat: ${String.format("%.5f", record.latitude)}, Lng: ${String.format("%.5f", record.longitude)}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Live alert badge vs batch sync badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (record.isRealtime) Color(0xFFFDA4AF) else Color(0xFFBFDBFE))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (record.isRealtime) "RT SHOCK" else "SYNCED",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = if (record.isRealtime) Color(0xFF9F1239) else Color(0xFF1E3A8A)
            )
        }
    }
}

// SimulatorToolsTab and Quadruple removed as physical ESP32 track logs are used exclusively now.

@Composable
fun DeviceSelectionDialog(
    devices: List<Map<String, String>>,
    onDismiss: () -> Unit,
    onSelectPhysical: (String) -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("device_selection_dialog")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connect ESP32 Tracker",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close dialog",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Select your physical ESP32 bike tracker to capture real-time road vibrations and map shock coordinates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Physical Device Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PAIRED BLUETOOTH DEVICES",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.5.sp
                    )
                    
                    // Button to Open Bluetooth System Settings
                    TextButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open Bluetooth settings: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.testTag("open_system_bluetooth_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Pair icon",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pair New", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(12.dp)
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "No paired ESP32 devices found",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Click 'Pair New' above to open system settings, pair with 'ESP32_Vibe_Tracker', and return to this list.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(devices) { dev ->
                            val name = dev["name"] ?: "Unknown device"
                            val address = dev["address"] ?: "00:00:00:00:00:00"
                            val isTracker = name.contains("ESP32", ignoreCase = true) || name.contains("vibe", ignoreCase = true)
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isTracker) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable { onSelectPhysical(address) }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = "Device icon",
                                        tint = if (isTracker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = name,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isTracker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = address,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Select",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ImpactDetailsDialog(
    record: ImpactRecord,
    onDismiss: () -> Unit
) {
    val (color, titleText, desc) = when (record.severity) {
        "Severe" -> Triple(
            Color(0xFFEF4444),
            "CRITICAL COLLISION / SEVERE HOLE",
            "DANGER: Severe G-force shock registered. Frame structure damage or bicycle collision warning is advised."
        )
        "Moderate" -> Triple(
            Color(0xFFF59E0B),
            "SIGNIFICANT ROAD BUMP",
            "WARNING: Significant vibration observed. Potential gravel potholes or major speed bumps along the track."
        )
        else -> Triple(
            Color(0xFF14B8A6),
            "COZY ROAD SURFACE",
            "OK: Mild vibration registered. Standard asphalt surface unevenness or speed decelerating strips."
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag("impact_detail_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Severe Header Indicator Banner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(
                        text = titleText,
                        color = color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }

                // G-Force Large circular Meter display
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.12f))
                            .border(3.dp, color, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = String.format("%.2f", record.impactG),
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                color = color
                            )
                            Text(
                                text = "G FORCE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = color.copy(alpha = 0.7f),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                // Descriptions
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )

                HorizontalDivider()

                // Numerical coordinate table rows
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DataGridKeyValueRow("Timestamp Event", record.timestamp)
                    DataGridKeyValueRow("Latitude coordinate", String.format("%.6f", record.latitude))
                    DataGridKeyValueRow("Longitude coordinate", String.format("%.6f", record.longitude))
                    DataGridKeyValueRow("Transmission tag", if (record.isRealtime) "Real-time Telemetry Frame" else "Batch Buffer Log")
                }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                    modifier = Modifier.fillMaxWidth().testTag("dialog_close_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Close Map View",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun DataGridKeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = key,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Robust helper function to fetch Android device's GPS or network location coordinates.
 */
fun requestDeviceLocation(
    context: Context,
    onLocationResult: (Pair<Double, Double>) -> Unit
) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    if (hasFine || hasCoarse) {
        try {
            // Get last known from GPS
            val gpsLoc = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null

            // Get last known from Network
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
                onLocationResult(Pair(bestLoc.latitude, bestLoc.longitude))
            }

            // Query fresh updates as well for better real-time centering
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor
                    ) { loc ->
                        if (loc != null) {
                            onLocationResult(Pair(loc.latitude, loc.longitude))
                        }
                    }
                } else {
                    locationManager.requestSingleUpdate(
                        provider,
                        object : LocationListener {
                            override fun onLocationChanged(loc: Location) {
                                onLocationResult(Pair(loc.latitude, loc.longitude))
                            }
                            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        },
                        android.os.Looper.getMainLooper()
                    )
                }
            } else {
                Toast.makeText(context, "Please turn on your device's GPS/Location", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Toast.makeText(context, "Location permission is required", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not acquire location: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Please grant location permission first", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun CloudSyncTab(
    supabaseUrl: String,
    supabaseAnonKey: String,
    isSyncingToCloud: Boolean,
    syncResultMessage: String?,
    onSaveCredentials: (String, String) -> Unit,
    onSyncAllToSupabase: () -> Unit,
    onClearSyncMessage: () -> Unit
) {
    var urlInput by remember(supabaseUrl) { mutableStateOf(supabaseUrl) }
    var keyInput by remember(supabaseAnonKey) { mutableStateOf(supabaseAnonKey) }

    LaunchedEffect(syncResultMessage) {
        if (syncResultMessage != null) {
            kotlinx.coroutines.delay(5000)
            onClearSyncMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Supabase Database Sync",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Connect Room DB to remote Supabase tables. Real-time vibrations detected by physical or virtual ESP32 will auto-sync to the cloud in real-time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 15.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        // URL Input
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Supabase API Endpoint URL",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.TextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("https://your-project.supabase.co", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().testTag("supabase_url_field"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }

        // Anon Key Input
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Supabase Public Anon Key",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.TextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                placeholder = { Text("your-public-anon-jwt-key", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().testTag("supabase_key_field"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onSaveCredentials(urlInput, keyInput) },
                modifier = Modifier.weight(1f).height(38.dp).testTag("save_supabase_credentials"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onSyncAllToSupabase,
                enabled = !isSyncingToCloud && urlInput.isNotEmpty() && keyInput.isNotEmpty(),
                modifier = Modifier.weight(1.2f).height(38.dp).testTag("trigger_cloud_sync"),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSyncingToCloud) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync Now",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync Local DB", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Result Notification Message Panel
        AnimatedVisibility(visible = syncResultMessage != null) {
            syncResultMessage?.let { msg ->
                val isError = msg.contains("failed", ignoreCase = true) || msg.contains("Error", ignoreCase = true) || msg.contains("missing", ignoreCase = true)
                val bgColor = if (isError) Color(0xFF451A1A) else Color(0xFF132D1F)
                val borderColor = if (isError) Color(0xFFEF4444) else Color(0xFF10B981)
                val textColor = if (isError) Color(0xFFFCA5A5) else Color(0xFFA7F3D0)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor, RoundedCornerShape(8.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = "Sync Alert Status",
                        tint = borderColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = msg,
                        fontSize = 11.sp,
                        color = textColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

