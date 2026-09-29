package com.example.myapplication.feature.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val AMAP_PRIVACY_PREFERENCES = "amap_privacy"
private const val AMAP_PRIVACY_ACCEPTED = "map_privacy_accepted"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapPicker(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    onPick: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(AMAP_PRIVACY_PREFERENCES, 0) }
    var privacyAccepted by remember {
        mutableStateOf(preferences.getBoolean(AMAP_PRIVACY_ACCEPTED, false))
    }

    if (!privacyAccepted) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("使用高德地图") },
            text = { Text("地图选点功能将使用高德地图 SDK，并可能处理设备、网络和位置信息。仅在你同意后初始化高德地图。") },
            confirmButton = {
                TextButton(onClick = {
                    preferences.edit().putBoolean(AMAP_PRIVACY_ACCEPTED, true).apply()
                    privacyAccepted = true
                }) { Text("同意并继续") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
        return
    }

    AmapPickerContent(lat, lon, onDismiss, onPick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmapPickerContent(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    onPick: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var selectedMapPoint by remember { mutableStateOf<LatLng?>(null) }
    var selectedPlaceName by remember { mutableStateOf<String?>(null) }
    var markerRefresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf("") }
    var mapStatus by remember { mutableStateOf("正在加载高德地图…") }
    var focus by remember {
        mutableStateOf(
            Triple(
                lat.coerceIn(-85.0, 85.0),
                lon.coerceIn(-180.0, 180.0),
                if (lat == 35.0 && lon == 105.0) 4f else 16f,
            ),
        )
    }

    val mapView = remember(context) {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        ServiceSettings.updatePrivacyShow(context, true, true)
        ServiceSettings.updatePrivacyAgree(context, true)
        MapView(context).apply { onCreate(null) }
    }
    val selectionMarker = remember(mapView) { arrayOfNulls<Marker>(1) }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        fun selectPoint(point: LatLng, name: String? = null) {
            val wgs84 = gcj02ToWgs84(point.latitude, point.longitude)
            selected = wgs84
            selectedMapPoint = point
            selectedPlaceName = name
            markerRefresh++
            mapStatus = name?.let { "已选择：$it" } ?: "已选择地图位置"
        }

        mapView.map.setTouchPoiEnable(true)
        mapView.map.setOnMapLoadedListener { mapStatus = "拖动或缩放地图，点击目标地点" }
        mapView.map.setOnMapClickListener { point -> selectPoint(point) }
        mapView.map.setOnPOIClickListener { poi -> selectPoint(poi.coordinate, poi.name) }
    }

    LaunchedEffect(mapView, selectedMapPoint, selectedPlaceName, markerRefresh) {
        val point = selectedMapPoint
        if (point == null) {
            selectionMarker[0]?.remove()
            selectionMarker[0] = null
        } else {
            val marker = selectionMarker[0]
            if (marker == null || marker.isRemoved) {
                selectionMarker[0] = mapView.map.addMarker(
                    MarkerOptions()
                        .position(point)
                        .title(selectedPlaceName)
                        .visible(true)
                        .zIndex(Float.MAX_VALUE),
                )
            } else {
                marker.position = point
                marker.title = selectedPlaceName
                marker.isVisible = true
                marker.zIndex = Float.MAX_VALUE
                marker.setToTop()
            }
        }
    }

    LaunchedEffect(mapView, focus) {
        val point = wgs84ToGcj02(focus.first, focus.second)
        mapView.map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(point.first, point.second), focus.third),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("地图选点") },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("返回") } },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            selected?.let { "已选：%.5f, %.5f".format(it.first, it.second) }
                                ?: "请在地图上点选最终位置",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { selected?.let { onPick(it.first, it.second) } },
                            enabled = selected != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) { Text("使用此位置") }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("搜索城市 / 学校 / 街道") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = query.isNotBlank() && !searching,
                        onClick = {
                            searching = true
                            searchMessage = "正在搜索…"
                            val requestedQuery = query.trim()
                            val inputtips = Inputtips(context, InputtipsQuery(requestedQuery, ""))
                            inputtips.setInputtipsListener { tips, errorCode ->
                                val match = if (errorCode == 1000) {
                                    tips.orEmpty().firstOrNull { it.point != null }
                                } else null
                                if (match == null) {
                                    searchMessage = "未找到地点，请补充城市或街道名称"
                                } else {
                                    val point = match.point
                                    val wgs84 = gcj02ToWgs84(point.latitude, point.longitude)
                                    selected = null
                                    selectedMapPoint = null
                                    selectedPlaceName = null
                                    focus = Triple(wgs84.first, wgs84.second, 17f)
                                    searchMessage = "已定位到：${match.name}"
                                    mapStatus = "请放大地图并点击最终位置"
                                }
                                searching = false
                            }
                            inputtips.requestInputtipsAsyn()
                        },
                    ) { Text(if (searching) "搜索中" else "搜索") }
                }

                if (searchMessage.isNotEmpty()) {
                    Text(
                        searchMessage,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text(
                    mapStatus,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

private const val EARTH_RADIUS = 6378245.0
private const val EE = 0.006693421622965943

private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
    if (outsideChina(lat, lon)) return lat to lon
    val delta = coordinateDelta(lat, lon)
    return lat + delta.first to lon + delta.second
}

private fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
    if (outsideChina(lat, lon)) return lat to lon
    var wgsLat = lat
    var wgsLon = lon
    repeat(3) {
        val converted = wgs84ToGcj02(wgsLat, wgsLon)
        wgsLat -= converted.first - lat
        wgsLon -= converted.second - lon
    }
    return wgsLat to wgsLon
}

private fun outsideChina(lat: Double, lon: Double): Boolean =
    lon !in 72.004..137.8347 || lat !in 0.8293..55.8271

private fun coordinateDelta(lat: Double, lon: Double): Pair<Double, Double> {
    val x = lon - 105.0
    val y = lat - 35.0
    var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
        0.2 * sqrt(abs(x))
    dLat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    dLat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
    dLat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0

    var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
        0.1 * sqrt(abs(x))
    dLon += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    dLon += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
    dLon += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0

    val radLat = lat / 180.0 * PI
    var magic = sin(radLat)
    magic = 1 - EE * magic * magic
    val sqrtMagic = sqrt(magic)
    val latitudeDelta = dLat * 180.0 /
        ((EARTH_RADIUS * (1 - EE) / (magic * sqrtMagic)) * PI)
    val longitudeDelta = dLon * 180.0 /
        (EARTH_RADIUS / sqrtMagic * cos(radLat) * PI)
    return latitudeDelta to longitudeDelta
}
