// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.util.Xml
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.*
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var rootReady by mutableStateOf(false)
    private var rootChecking by mutableStateOf(false)
    private var rootStatus by mutableStateOf("正在检测 Root…")
    private var message by mutableStateOf("")
    private var points by mutableStateOf(doubleArrayOf())
    private var selected by mutableStateOf(doubleArrayOf(31.2304,121.4737))
    private var routeMode by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var playbackStatus by mutableStateOf("已停止")
    private var position by mutableStateOf<DoubleArray?>(null)
    private var routeProgress by mutableIntStateOf(0)
    private var pending by mutableStateOf(false)
    private var speed by mutableStateOf("5")
    private var variation by mutableStateOf("0")
    private var period by mutableStateOf("10")
    private var interval by mutableStateOf("1")
    private var seed by mutableStateOf("42")
    private var loop by mutableStateOf(false)
    private var paceEnabled by mutableStateOf(false)
    private var paceFast by mutableStateOf("3:00")
    private var paceSlow by mutableStateOf("9:00")
    private var paceTarget by mutableStateOf("6:00")
    private var paceSigma by mutableStateOf("0.8")
    private var paceCorrelation by mutableStateOf("10")
    private var paceAccel by mutableStateOf("0.5")
    private var paceDecel by mutableStateOf("0.7")
    private var paceTurn by mutableStateOf("0.5")
    private var paceLookAhead by mutableStateOf("15")
    private var paceStopEvery by mutableStateOf("0")
    private var paceStopFor by mutableStateOf("5")
    private var gpsEnabled by mutableStateOf(true)
    private var networkEnabled by mutableStateOf(true)
    private var gpsAccuracy by mutableStateOf("5")
    private var networkAccuracy by mutableStateOf("50")
    private var networkInterval by mutableStateOf("1")
    private var showLive by mutableStateOf(false)
    private var livePoints by mutableStateOf(doubleArrayOf())
    private var manualDraw by mutableStateOf(false)
    private var selectLiveStart by mutableStateOf(true)
    private var liveCurves by mutableStateOf(true)
    private var liveRadius by mutableStateOf("6")
    private var readbacks by mutableStateOf("")
    private var backendStatus by mutableStateOf(BackendController.status)
    private var speedHistory by mutableStateOf(listOf<Double>())
    private var observer:Diagnostics?=null
    private var wirelessJson by mutableStateOf(WirelessScenario.example())
    private var wirelessExport=""
    private val importWireless=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null&&!running&&!pending) worker.execute {
            try {
                val bytes=contentResolver.openInputStream(uri)!!.use { it.readNBytes(60001) }
                require(bytes.size<=60000) { "场景文件超过 60 KB" }
                val validated=WirelessScenario(bytes.toString(Charsets.UTF_8)).data.toString()
                runOnUiThread { if(!running&&!pending) saveWireless(validated) }
            } catch(ex:Exception) { report(ex.message ?: "场景导入失败") }
        }
    }
    private val exportWireless=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot=wirelessExport
        if(uri!=null) worker.execute {
            try { contentResolver.openOutputStream(uri)!!.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }; report("场景已导出") }
            catch(ex:Exception) { report(ex.message ?: "场景导出失败") }
        }
    }
    private var web: WebView? = null
    private var mapLoaded = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            report("请在系统设置中允许精确位置权限")
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !running && !pending) worker.execute {
            try {
                val coordinates=ArrayList<Double>()
                contentResolver.openInputStream(uri)!!.use { stream ->
                    val bytes=stream.readNBytes(8*1024*1024+1)
                    require(bytes.size<=8*1024*1024) { "GPX 文件超过 8 MB" }
                    val parser=Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
                    parser.setInput(bytes.inputStream(),null)
                    var event=parser.eventType
                    while(event!=XmlPullParser.END_DOCUMENT) {
                        if(event==XmlPullParser.START_TAG && parser.name in listOf("trkpt","rtept")) {
                            require(coordinates.size<20000) { "最多支持 10000 个节点" }
                            coordinates.add(parser.getAttributeValue(null,"lat").toDouble())
                            coordinates.add(parser.getAttributeValue(null,"lon").toDouble())
                        }
                        event=parser.next()
                    }
                }
                val result=coordinates.toDoubleArray()
                require(result.size>=4 && Route(result).total>0) { "路线需要至少两个不同节点" }
                runOnUiThread { if(!running && !pending) { saveRoute(result); routeMode=true; fitMap() } }
            } catch(ex:Exception) { report(ex.message ?: "导入失败") }
        }
    }
    private var exportSnapshot=doubleArrayOf()
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val snapshot=exportSnapshot.clone()
        if(uri!=null) worker.execute {
            try {
                val gpx=buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"SimLocation\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>SimLocation route</name><trkseg>")
                    for(i in snapshot.indices step 2) append("<trkpt lat=\"${snapshot[i]}\" lon=\"${snapshot[i+1]}\"/>")
                    append("</trkseg></trk></gpx>")
                }
                contentResolver.openOutputStream(uri)!!.use { it.write(gpx.toByteArray(Charsets.UTF_8)) }
                report("GPX 已导出")
            } catch(ex:Exception) { report(ex.message ?: "导出失败") }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        rootReady=LocationService.running
        val prefs=getSharedPreferences("motion",MODE_PRIVATE)
        speed=prefs.getString("speed","5")!!; variation=prefs.getString("variation","0")!!
        period=prefs.getString("period","10")!!; interval=prefs.getString("interval","1")!!
        seed=prefs.getString("seed","42")!!; loop=prefs.getBoolean("loop",false)
        paceEnabled=prefs.getBoolean("pace-enabled",false)
        paceFast=prefs.getString("pace-fast","3:00")!!; paceSlow=prefs.getString("pace-slow","9:00")!!; paceTarget=prefs.getString("pace-target","6:00")!!
        paceSigma=prefs.getString("pace-sigma","0.8")!!; paceCorrelation=prefs.getString("pace-correlation","10")!!
        paceAccel=prefs.getString("pace-accel","0.5")!!; paceDecel=prefs.getString("pace-decel","0.7")!!
        paceTurn=prefs.getString("pace-turn","0.5")!!; paceLookAhead=prefs.getString("pace-lookahead","15")!!
        paceStopEvery=prefs.getString("pace-stop-every","0")!!; paceStopFor=prefs.getString("pace-stop-for","5")!!
        val sources=getSharedPreferences("signals",MODE_PRIVATE)
        gpsEnabled=sources.getBoolean("gps",true); networkEnabled=sources.getBoolean("network",true)
        gpsAccuracy=sources.getString("gps-accuracy","5")!!; networkAccuracy=sources.getString("network-accuracy","50")!!; networkInterval=sources.getString("network-interval","1")!!
        try { wirelessJson=BackendController.saved(this).data.toString() } catch(ex:Exception) { report("无线场景读取失败：${ex.message}") }
        if(LocationService.live) { livePoints=LocationService.activeRoute?.clone() ?: doubleArrayOf(); selectLiveStart=false }
        try {
            val saved=getSharedPreferences("route",MODE_PRIVATE).getString("points",null)
            if(saved!=null) {
                val array=JSONArray(saved)
                val loaded=DoubleArray(array.length()) { array.getDouble(it) }
                Route(loaded); points=loaded; selected=loaded.take(2).toDoubleArray()
            }
        } catch(ex:Exception) { report("保存的路线读取失败：${ex.message}") }
        setContent {
            MiuixTheme(colors=if(isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val pageAlpha=remember { Animatable(1f) }
                LaunchedEffect(tab) { showLive=tab==2; updateMap(); pageAlpha.snapTo(0.6f); pageAlpha.animateTo(1f,tween(180)) }
                LaunchedEffect(Unit) {
                    while(true) {
                        running=LocationService.running; paused=LocationService.pausedState
                        position=LocationService.current; routeProgress=LocationService.progress
                        playbackStatus=LocationService.status
                        backendStatus=BackendController.status
                        if(LocationService.live) LocationService.activeRoute?.let { livePoints=it }
                        if(running) speedHistory=(speedHistory+LocationService.currentSpeed*3.6).takeLast(120)
                        observer?.let { readbacks=it.snapshot(position) }
                        if(pending && (running || playbackStatus.startsWith("运行错误"))) pending=false
                        updateMap(); delay(500)
                    }
                }
                val labels=listOf("地图","路线","实时","回放","设置")
                Scaffold(bottomBar={ NavigationBar(items=labels.mapIndexed { i,label -> NavigationItem(label,navIcon(i)) },selected=tab,onClick={ tab=it }) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(labels[tab],fontSize=30.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            Text(if(paused) "● 已暂停" else if(running) "● 定位中" else "SimLocation",fontSize=13.sp,color=MiuixTheme.colorScheme.primary)
                        }
                        if(message.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(message,fontSize=13.sp,modifier=Modifier.weight(1f))
                            TextButton("关闭",{ message="" },minWidth=48.dp,minHeight=36.dp)
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha=pageAlpha.value; translationX=(1-pageAlpha.value)*12.dp.toPx() }) {
                            when(tab) { 0 -> MapPage(); 1 -> RoutePage { tab=0 }; 2 -> LivePage(); 3 -> PlaybackPage(); 4 -> SettingsPage() }
                        }
                    }
                }
            }
        }
        authorize(showResult=false)
    }
    @Composable private fun MapPage() {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Action("单点定位",!running&&!pending,Modifier.weight(1f),!routeMode) { routeMode=false; updateMap() }
                Action("编辑路线",!running&&!pending,Modifier.weight(1f),routeMode) { routeMode=true; updateMap() }
            }
            Text(if(running||pending) "回放期间地图只读 · 橙点为已发送位置" else if(routeMode) "点击添加节点 · 拖动圆点调整路线" else "点击地图选择位置",fontSize=13.sp,modifier=Modifier.padding(16.dp,10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory={ mapView().also { (it.parent as? android.view.ViewGroup)?.removeView(it) } },modifier=Modifier.fillMaxSize())
                TextButton("查看全程",{ fitMap() },modifier=Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
            Card(Modifier.fillMaxWidth().padding(12.dp),insideMargin=PaddingValues(16.dp)) {
                if(routeMode) {
                    Text(routeSummary(),fontWeight=FontWeight.SemiBold)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Action("撤销节点",!running&&!pending&&points.isNotEmpty(),Modifier.weight(1f)) { saveRoute(points.copyOf(points.size-2)) }
                        Action("清空路线",!running&&!pending&&points.isNotEmpty(),Modifier.weight(1f)) { confirmClear() }
                    }
                } else {
                    Text(String.format(Locale.ROOT,"%.6f, %.6f · WGS84",selected[0],selected[1]),fontSize=14.sp)
                    Action("模拟到此位置",!running&&!pending,Modifier.fillMaxWidth(),true) { start(selected) }
                    if(running&&LocationService.singlePoint) {
                        Text("单点位置持续发送中；地图应用是否采用，请到设置中的功能验证核对。",fontSize=13.sp)
                        Action("停止单点定位",!pending,Modifier.fillMaxWidth()) { control("stop") }
                    }
                }
            }
        }
    }
    @Composable private fun RoutePage(openMap:()->Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
                Text(routeSummary(),fontSize=22.sp,fontWeight=FontWeight.Bold)
                Text("按节点顺序直线连接；不自动沿道路规划。",fontSize=13.sp)
                Action("在地图上编辑",!running&&!pending,Modifier.fillMaxWidth(),true) { routeMode=true; openMap() }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Action("导入 GPX",!running&&!pending,Modifier.weight(1f)) { importFile.launch(arrayOf("*/*")) }
                    Action("导出 GPX",points.size>=4,Modifier.weight(1f)) { exportSnapshot=points.clone(); exportFile.launch("SimLocation-route.gpx") }
                }
            }
            if(points.isEmpty()) Text("先到地图点击添加节点，或导入已有 GPX。")
            // Large imported routes stay usable without composing 10000 rows at once.
            for(i in 0 until minOf(points.size/2,100)) Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${i+1} · ${if(i==0) "起点" else if(i==points.size/2-1) "终点" else "途经点"}",fontWeight=FontWeight.SemiBold)
                        Text(String.format(Locale.ROOT,"%.6f, %.6f",points[2*i],points[2*i+1]),fontSize=13.sp)
                    }
                    Action("删除",!running&&!pending) { saveRoute(points.filterIndexed { index,_ -> index/2!=i }.toDoubleArray()) }
                }
            }
            if(points.size/2>100) Text("此处显示前 100 个节点，全路线可在地图查看。")
        }
    }
    @Composable private fun PlaybackPage() {
        val editable=!pending&&(!running||paused)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
                Text(if(pending) "正在初始化" else playbackStatus,fontSize=15.sp)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).background(MiuixTheme.colorScheme.secondaryContainer)) {
                    Box(Modifier.fillMaxWidth(routeProgress/1000f).height(5.dp).background(MiuixTheme.colorScheme.primary))
                }
                Text(if(running&&LocationService.singlePoint) "当前为单点定位，位置持续发送" else routeSummary(),fontSize=13.sp,modifier=Modifier.padding(top=10.dp))
                Action("开始路线回放",!running&&!pending&&points.size>=4,Modifier.fillMaxWidth(),true) { start(points) }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Action(if(paused) "继续" else "暂停",running,Modifier.weight(1f)) { control("pause") }
                    Action("停止并恢复定位",!pending,Modifier.weight(1f)) { control("stop") }
                }
            }
            Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
                Text("运动参数",fontSize=20.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Toggle("使用配速模型",paceEnabled,editable) { paceEnabled=it }
                if(!paceEnabled) {
                    Field("速度 km/h",speed,editable) { speed=it }
                    Field("速度波动 ±%",variation,editable) { variation=it }
                    Field("波动周期 / s",period,editable) { period=it }
                } else Text("速度由下方的配速范围、目标配速和波动参数控制。关闭配速模型后可编辑基础速度参数。",fontSize=13.sp)
                Field("定位更新间隔 / s",interval,editable) { interval=it }
                Field("随机种子",seed,editable) { seed=it }
                Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("往返循环",modifier=Modifier.weight(1f)); Switch(loop,{ loop=it },enabled=editable&&!LocationService.live)
                }
                Text("终点沿原路返回。运行时参数锁定，暂停后保存生效。单点模式仅应用定位更新间隔，运动参数用于下一次路线回放。",fontSize=13.sp)
                Action("保存参数",editable,Modifier.fillMaxWidth()) { applyMotion() }
            }
            if(paceEnabled) PaceSettings(showSwitch=false)
        }
    }
    @Composable private fun SettingsPage() {
        var section by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var lat by rememberSaveable { mutableStateOf(selected[0].toString()) }
        var lon by rememberSaveable { mutableStateOf(selected[1].toString()) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Action("配置",true,Modifier.weight(1f),section==0) { section=0 }
                Action("功能验证",true,Modifier.weight(1f),section==1) { section=1 }
            }
            if(section==1) { VerificationPage(); return@Column }
            SourceSettings()
            WirelessSettings()
            PaceSettings()
            Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
                Text("设备与权限",fontSize=20.sp,fontWeight=FontWeight.Bold)
                Text(rootStatus,modifier=Modifier.padding(vertical=12.dp))
                Action("重新检测 Root",!pending&&!rootChecking,Modifier.fillMaxWidth(),true) { authorize() }
                Action("检查并授予应用权限",!pending,Modifier.fillMaxWidth()) { requestRuntimePermissions() }
                Text("精确位置与通知权限用于前台回放。HyperOS 请保留最近任务，退出前停止回放。",fontSize=13.sp)
            }
            Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
                Text("地点与坐标",fontSize=20.sp,fontWeight=FontWeight.Bold)
                TextField(query,{ query=it },label="搜索地点",singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                Action("查找并选中",!running&&!pending&&query.isNotBlank(),Modifier.fillMaxWidth()) { search(query) }
                Field("纬度 WGS84",lat,!running&&!pending) { lat=it }
                Field("经度 WGS84",lon,!running&&!pending) { lon=it }
                Action("选择此坐标",!running&&!pending,Modifier.fillMaxWidth()) { val p=doubleArrayOf(lat.toDouble(),lon.toDouble()); Route(p); selected=p; updateMap(); centerMap() }
                Text("搜索使用系统地理编码服务。坐标始终使用 WGS84。",fontSize=13.sp)
            }
            Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
                Text("SimLocation",fontSize=23.sp,fontWeight=FontWeight.Bold)
                Text("0.5.1 · Kanisuko\nRoot Edition · Redmi K60 / HyperOS 3\norg.ethertaco.simlocation",modifier=Modifier.padding(vertical=10.dp))
                Text("系统测试定位源；可选现代 LSPosed 系统后端。\n界面：Miuix · 地图：Leaflet / OpenStreetMap",fontSize=13.sp)
                Action("开源许可",true,Modifier.fillMaxWidth()) { showLicense() }
            }
        }
    }
    @Composable private fun Toggle(label:String,checked:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,modifier=Modifier.weight(1f)); Switch(checked,onChange,enabled=enabled)
        }
    }
    @Composable private fun SourceSettings() {
        val editable=!running&&!pending
        Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
            Text("定位源配置",fontSize=20.sp,fontWeight=FontWeight.Bold)
            Toggle("GPS 测试源",gpsEnabled,editable) { gpsEnabled=it }
            Toggle("network 测试源",networkEnabled,editable) { networkEnabled=it }
            Field("GPS 精度 / m",gpsAccuracy,editable) { gpsAccuracy=it }
            Field("network 精度 / m",networkAccuracy,editable) { networkAccuracy=it }
            Field("network 最短更新间隔 / s",networkInterval,editable) { networkInterval=it }
            Text("多个定位源可同时开启。network 是定位提供者，不等同于 WiFi 扫描或基站观测数据；后两者需要额外系统后端。未选中的真实源仍可能返回真实位置。",fontSize=13.sp)
            Action("保存定位源",editable,Modifier.fillMaxWidth()) { saveSources(); report("定位源已保存，下次启动生效") }
        }
    }
    @Composable private fun PaceSettings(showSwitch:Boolean=true) {
        val editable=!pending&&(!running||paused)
        var advanced by rememberSaveable { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
            Text("运动模型",fontSize=20.sp,fontWeight=FontWeight.Bold)
            if(showSwitch) Toggle("启用配速与速度波动",paceEnabled,editable) { paceEnabled=it }
            AnimatedVisibility(paceEnabled) {
                Column {
                    TextField(paceFast,{ paceFast=it },label="最快巡航配速 / 分:秒",enabled=editable,singleLine=true,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp))
                    TextField(paceTarget,{ paceTarget=it },label="目标配速 / 分:秒",enabled=editable,singleLine=true,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp))
                    TextField(paceSlow,{ paceSlow=it },label="最慢巡航配速 / 分:秒",enabled=editable,singleLine=true,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp))
                    Field("速度波动标准差 / km/h",paceSigma,editable) { paceSigma=it }
                    Text("方差为标准差的平方。起步、转弯、停留和终点减速可低于巡航速度；使用同一随机种子可复现。",fontSize=13.sp)
                    Toggle("高级参数",advanced) { advanced=it }
                    AnimatedVisibility(advanced) {
                        Column {
                            Field("波动相关时间 / s",paceCorrelation,editable) { paceCorrelation=it }
                            Field("最大加速度 / m/s²",paceAccel,editable) { paceAccel=it }
                            Field("最大减速度 / m/s²",paceDecel,editable) { paceDecel=it }
                            Field("急转弯速度比例 / 0.2–1",paceTurn,editable) { paceTurn=it }
                            Field("转弯预判距离 / m",paceLookAhead,editable) { paceLookAhead=it }
                            Field("周期停留间隔 / s，0 关闭",paceStopEvery,editable) { paceStopEvery=it }
                            Field("停留目标持续时间 / s",paceStopFor,editable) { paceStopFor=it }
                        }
                    }
                }
            }
            Action("保存运动模型",editable,Modifier.fillMaxWidth()) { applyMotion() }
        }
    }
    @Composable private fun WirelessSettings() {
        val editable=!running&&!pending
        val initial=remember(wirelessJson) { WirelessScenario(wirelessJson) }
        var scans by remember(wirelessJson) { mutableStateOf(initial.scans) }
        var connection by remember(wirelessJson) { mutableStateOf(initial.connection) }
        var cells by remember(wirelessJson) { mutableStateOf(initial.cell) }
        var clearMock by remember(wirelessJson) { mutableStateOf(initial.clearMock) }
        var targets by remember(wirelessJson) { mutableStateOf(initial.data.optJSONArray("targets")?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } } ?: "") }
        var draft by remember(wirelessJson) { mutableStateOf(initial.data.toString(2)) }
        var advanced by rememberSaveable { mutableStateOf(false) }
        fun validated():String {
            val data=JSONObject(draft).put("wifiScan",scans).put("wifiConnection",connection).put("cellEnabled",cells).put("clearMock",clearMock)
                .put("targets",JSONArray(targets.split(Regex("[\\s,;]+" )).filter { it.isNotBlank() }))
            return WirelessScenario(data.toString()).data.toString()
        }
        Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
            Text("无线观测与系统后端",fontSize=20.sp,fontWeight=FontWeight.Bold)
            Text(backendStatus,fontSize=13.sp,modifier=Modifier.padding(vertical=12.dp))
            Text("可选现代 LSPosed，API 101+；只选择系统框架与电话服务作用域，启用后重启。不开启以下选项时，Root 回放不依赖 LSPosed。",fontSize=13.sp)
            Toggle("模拟 WiFi 扫描列表",scans,editable) { scans=it }
            Toggle("模拟已连接 WiFi 观测",connection,editable) { connection=it }
            Toggle("模拟 LTE / NR 基站观测",cells,editable) { cells=it }
            Toggle("修改接收位置的 mock 属性（实验）",clearMock,editable) { clearMock=it }
            Text("四项可独立组合，仅在位置回放期间生效。停止或 12 s 心跳超时会恢复原结果；不修改真实无线连接、SIM 或公网 IP。mock 选项只改变位置对象的属性，不能保证不可检测。",fontSize=13.sp)
            TextField(targets,{ targets=it },label="额外作用的应用包名 / 每行一个",enabled=editable,singleLine=false,modifier=Modifier.fillMaxWidth().padding(vertical=12.dp))
            Text("空白表示仅 SimLocation 自身验证；第三方应用的 WiFi／基站和 mock 属性不会改变。高德地图包名为 com.autonavi.minimap。指定应用的数据在系统侧处理，不向应用进程注入；已经建立的基站订阅需重新订阅。",fontSize=13.sp)
            Text("场景：${initial.data.getString("name")} · ${initial.wifi.size} 个 AP · ${initial.cells.size} 个基站",modifier=Modifier.padding(top=12.dp))
            Toggle("编辑观测场景",advanced,editable) { advanced=it }
            AnimatedVisibility(advanced) {
                Column {
                    Text("BSSID / 基站标识需要对应地点的数据。样例使用测试标识，不能映射为地图选中的地点。connectedWifi 为已连接 AP 的索引（从 0 开始）。",fontSize=13.sp)
                    TextField(draft,{ draft=it },label="观测场景 JSON",enabled=editable,singleLine=false,modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=320.dp).padding(top=12.dp))
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Action("导入场景",editable,Modifier.weight(1f)) { importWireless.launch(arrayOf("application/json","text/plain","*/*")) }
                Action("导出场景",editable,Modifier.weight(1f)) { wirelessExport=JSONObject(validated()).toString(2); exportWireless.launch("SimLocation-scenario.json") }
            }
            Action("保存无线配置",editable,Modifier.fillMaxWidth(),true) { saveWireless(validated()) }
            Action("载入测试样例（关闭所有选项）",editable,Modifier.fillMaxWidth()) { saveWireless(WirelessScenario.example()) }
        }
    }
    @Composable private fun LivePage() {
        val editable=!pending&&(!running||LocationService.live)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Action("自动",editable,Modifier.weight(1f),!manualDraw) { manualDraw=false; updateMap() }
                Action("手绘",editable,Modifier.weight(1f),manualDraw) { manualDraw=true; updateMap() }
                Action("选起点",!running&&!pending,Modifier.weight(1f),selectLiveStart) { selectLiveStart=true; updateMap() }
            }
            Text(if(selectLiveStart) "点击地图设置起点" else if(manualDraw) "用手指绘制路径；橙点按设定速度移动" else "点击添加途经点；运行中可继续追加",fontSize=13.sp,modifier=Modifier.padding(16.dp,8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory={ mapView().also { (it.parent as? android.view.ViewGroup)?.removeView(it) } },modifier=Modifier.fillMaxSize())
                TextButton("查看全程",{ fitMap() },modifier=Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
            Column(Modifier.fillMaxWidth().heightIn(max=290.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text(if(livePoints.isEmpty()) "尚未选择起点" else "${livePoints.size/2} 个节点 · ${String.format(Locale.ROOT,"%.1f",Route(livePoints).total)} m",fontWeight=FontWeight.SemiBold)
                Toggle("平滑转弯（不自动沿道路）",liveCurves,editable) { liveCurves=it }
                if(liveCurves) Field("转弯半径 / m，0–30",liveRadius,editable) { liveRadius=it }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Action("开始实时模拟",!running&&!pending&&livePoints.isNotEmpty(),Modifier.weight(1f),true) { startLive() }
                    Action(if(paused) "继续" else "暂停",running&&LocationService.live,Modifier.weight(1f)) { control("pause") }
                    Action("停止",running||pending,Modifier.weight(1f)) { control("stop") }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Action("清空草稿",!running&&!pending,Modifier.weight(1f)) { livePoints=doubleArrayOf(); selectLiveStart=true; updateMap() }
                    Action("保存为路线",!running&&!pending&&livePoints.size>=4,Modifier.weight(1f)) { saveRoute(livePoints); report("已保存，可在路线页导出 GPX") }
                }
                if(running&&LocationService.live) Text(playbackStatus,fontSize=13.sp)
            }
        }
    }
    @Composable private fun VerificationPage() {
        DisposableEffect(Unit) {
            val probe=Diagnostics(this@MainActivity); observer=probe; probe.start()
            onDispose { probe.close(); if(observer===probe) observer=null }
        }
        Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(16.dp)) {
            Text("实际生效验证",fontSize=20.sp,fontWeight=FontWeight.Bold)
            Text("活动配置：${LocationService.activeConfiguration}",modifier=Modifier.padding(vertical=12.dp))
            Text("$backendStatus\n无线观测与 mock 属性：按活动场景配置，以下回读用于验证\n真实无线连接、移动数据与公网 IP：未改变",fontSize=13.sp)
            Text(readbacks.ifEmpty { "正在注册定位回调…" },modifier=Modifier.padding(vertical=16.dp),fontSize=14.sp)
            Text("模型速度 / km/h · 最近 60 秒",fontWeight=FontWeight.SemiBold)
            val history=speedHistory
            Canvas(Modifier.fillMaxWidth().height(100.dp).padding(vertical=8.dp)) {
                if(history.size>1) {
                    val max=maxOf(1.0,history.maxOrNull() ?: 1.0)
                    history.zipWithNext().forEachIndexed { index,pair ->
                        drawLine(Color(0xff3482ff),Offset(size.width*index/(history.size-1),size.height*(1-pair.first/max).toFloat()),Offset(size.width*(index+1)/(history.size-1),size.height*(1-pair.second/max).toFloat()),3f)
                    }
                }
            }
            if(history.isNotEmpty()) {
                val mean=history.average(); val variance=history.map { (it-mean)*(it-mean) }.average()
                Text(String.format(Locale.ROOT,"均值 %.2f km/h · 窗口方差 %.3f (km/h)²",mean,variance),fontSize=13.sp)
            }
            Text("图表来自运动模型；上方坐标来自本应用监听的 Android API。回调年龄用于识别缓存，不代表其他应用也会收到相同数据。",fontSize=13.sp,modifier=Modifier.padding(top=12.dp))
            Action("检查基站异步回调",true,Modifier.fillMaxWidth()) { observer?.requestCells() }
            Action("检查最近 / 单次位置",true,Modifier.fillMaxWidth()) { observer?.requestLocations() }
            Action("清空图表",true,Modifier.fillMaxWidth()) { speedHistory=emptyList() }
        }
    }
    @Composable private fun Field(label:String,value:String,enabled:Boolean,onChange:(String)->Unit) {
        TextField(value,onChange,label=label,enabled=enabled,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().padding(bottom=10.dp))
    }
    @Composable private fun Action(label:String,enabled:Boolean,modifier:Modifier=Modifier,primary:Boolean=false,action:()->Unit) {
        TextButton(label,{ try { action() } catch(ex:Exception) { report(ex.message ?: "操作失败") } },enabled=enabled,modifier=modifier.padding(top=8.dp),colors=if(primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
    }
    private fun routeSummary():String = if(points.isEmpty()) "尚未布设路线" else String.format(Locale.ROOT,"%d 个节点 · %.1f m",points.size/2,Route(points).total)
    private fun saveRoute(next:DoubleArray) {
        require(!running&&!pending) { "请先停止回放再编辑路线" }
        if(next.isNotEmpty()) Route(next)
        val prefs=getSharedPreferences("route",MODE_PRIVATE).edit()
        if(next.isEmpty()) prefs.remove("points") else prefs.putString("points",JSONArray(next.toList()).toString())
        check(prefs.commit()) { "路线保存失败" }
        points=next.clone(); updateMap()
    }
    private fun confirmClear() { android.app.AlertDialog.Builder(this).setTitle("清空路线？").setMessage("已保存的节点会被移除。").setNegativeButton("取消",null).setPositiveButton("清空") { _,_ -> try { saveRoute(doubleArrayOf()) } catch(ex:Exception) { report(ex.message!!) } }.show() }
    private fun settings()=Playback.Settings(if(paceEnabled) 0.0 else speed.toDouble(),if(paceEnabled) 0.0 else variation.toDouble(),if(paceEnabled) 10.0 else period.toDouble(),interval.toDouble(),loop,seed.toLong())
    private fun profile()=if(!paceEnabled) MotionProfile.disabled() else MotionProfile(true,MotionProfile.pace(paceFast),MotionProfile.pace(paceSlow),MotionProfile.pace(paceTarget),paceSigma.toDouble(),paceCorrelation.toDouble(),paceAccel.toDouble(),paceDecel.toDouble(),paceTurn.toDouble(),paceLookAhead.toDouble(),paceStopEvery.toDouble(),paceStopFor.toDouble())
    private fun applyMotion() {
        require(!pending&&(!running||paused)) { "请暂停后修改参数" }
        saveMotion()
        if(running) startService(motion(Intent(this,LocationService::class.java).setAction("settings")).putExtra("loop",if(LocationService.live||LocationService.singlePoint) false else loop))
        report(if(running) "参数已保存并应用到暂停的回放" else "参数已保存")
    }
    private fun saveSources() {
        require(gpsEnabled||networkEnabled) { "至少启用一个定位源" }
        require(gpsAccuracy.toDouble().isFinite() && gpsAccuracy.toDouble() in 0.01..10000.0 && networkAccuracy.toDouble().isFinite() && networkAccuracy.toDouble() in 0.01..10000.0 && networkInterval.toDouble().isFinite() && networkInterval.toDouble() in 0.2..60.0) { "精度应大于 0 且不超过 10000 m；间隔为 0.2–60 s" }
        getSharedPreferences("signals",MODE_PRIVATE).edit().putBoolean("gps",gpsEnabled).putBoolean("network",networkEnabled).putString("gps-accuracy",gpsAccuracy).putString("network-accuracy",networkAccuracy).putString("network-interval",networkInterval).apply()
    }
    private fun saveWireless(json:String) {
        require(!running&&!pending) { "请先停止回放再修改无线场景" }
        val validated=WirelessScenario(json).data.toString()
        check(getSharedPreferences("wireless",MODE_PRIVATE).edit().putString("scene",validated).commit()) { "场景保存失败" }
        wirelessJson=validated; BackendController.refreshProfile(); report("无线配置已保存，下次启动生效")
    }
    private fun sources(intent:Intent):Intent { saveSources(); return intent.putExtra("gps-enabled",gpsEnabled).putExtra("network-enabled",networkEnabled).putExtra("gps-accuracy",gpsAccuracy.toDouble()).putExtra("network-accuracy",networkAccuracy.toDouble()).putExtra("network-interval",networkInterval.toDouble()) }
    private fun saveMotion() {
        settings(); profile()
        getSharedPreferences("motion",MODE_PRIVATE).edit().putString("speed",speed).putString("variation",variation).putString("period",period)
            .putString("interval",interval).putString("seed",seed).putBoolean("loop",loop)
            .putBoolean("pace-enabled",paceEnabled).putString("pace-fast",paceFast).putString("pace-slow",paceSlow).putString("pace-target",paceTarget)
            .putString("pace-sigma",paceSigma).putString("pace-correlation",paceCorrelation).putString("pace-accel",paceAccel).putString("pace-decel",paceDecel)
            .putString("pace-turn",paceTurn).putString("pace-lookahead",paceLookAhead).putString("pace-stop-every",paceStopEvery).putString("pace-stop-for",paceStopFor).apply()
    }
    private fun motion(intent:Intent):Intent {
        val s=settings(); val p=profile()
        return intent.putExtra("speed",s.speed*3.6).putExtra("variation",s.variation*100).putExtra("period",s.period)
            .putExtra("interval",interval.toDouble()).putExtra("seed",seed.toLong()).putExtra("loop",loop)
            .putExtra("pace-enabled",p.enabled).putExtra("pace-fast",p.fastest).putExtra("pace-slow",p.slowest).putExtra("pace-target",p.target)
            .putExtra("pace-sigma",p.sigma*3.6).putExtra("pace-correlation",p.correlation).putExtra("pace-accel",p.acceleration).putExtra("pace-decel",p.deceleration)
            .putExtra("pace-turn",p.turnFactor).putExtra("pace-lookahead",p.lookAhead).putExtra("pace-stop-every",p.stopEvery).putExtra("pace-stop-for",p.stopFor)
    }
    private fun start(coordinates:DoubleArray) {
        require(rootReady) { if(rootChecking) "正在检测 Root，请稍候" else "未获得 Root，请检查 Root 管理器授权" }
        require(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) { "请允许精确位置权限" }
        val parsed=Route(coordinates)
        if(coordinates.size>2) require(parsed.total>0) { "路线需要至少两个不同节点" }
        val intent=Intent(this,LocationService::class.java).setAction("start").putExtra("points",coordinates)
        sources(intent)
        if(coordinates.size>2) { saveMotion(); motion(intent) } else {
            Playback.Settings(0.0,0.0,10.0,interval.toDouble(),false,42)
            intent.putExtra("speed",0.0).putExtra("interval",interval.toDouble())
        }
        pending=true; message=""; startForegroundService(intent)
    }
    private fun startLive() {
        require(rootReady) { if(rootChecking) "正在检测 Root，请稍候" else "未获得 Root，请检查 Root 管理器授权" }
        require(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) { "请允许精确位置权限" }
        require(livePoints.isNotEmpty()) { "请先选择起点" }
        saveMotion()
        if(liveCurves) livePoints=Trajectory.rounded(livePoints,liveRadius.toDouble())
        val intent=motion(sources(Intent(this,LocationService::class.java).setAction("start")))
            .putExtra("points",livePoints).putExtra("live",true).putExtra("loop",false)
        pending=true; message=""; startForegroundService(intent)
    }
    private fun appendLive(next:DoubleArray) {
        require(!pending&&(!running||LocationService.live)) { "请先停止普通路线回放" }
        Route(next)
        require(next.size<=4000) { "单次绘制最多 2000 个节点" }
        if(selectLiveStart||livePoints.isEmpty()) { livePoints=next.copyOf(2); selectLiveStart=false; updateMap(); return }
        var segment=livePoints.takeLast(2).toDoubleArray()+next
        if(liveCurves&&running) segment=Trajectory.rounded(segment,liveRadius.toDouble())
        val combined=livePoints+segment.drop(2).toDoubleArray(); Route(combined)
        if(running) startService(Intent(this,LocationService::class.java).setAction("append").putExtra("points",segment))
        livePoints=combined; updateMap()
    }
    private fun control(action:String) {
        if(action=="stop") {
            require(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) { "请允许精确位置权限以清理测试源" }
            startForegroundService(Intent(this,LocationService::class.java).setAction(action))
        } else if(running) startService(Intent(this,LocationService::class.java).setAction(action))
    }
    private fun authorize(showResult:Boolean=true) {
        if(rootChecking) return
        rootChecking=true; rootStatus="正在检测 Root…"
        worker.execute {
            try { RootAccess.verify(); runOnUiThread {
                if(isDestroyed||isFinishing) return@runOnUiThread
                rootChecking=false; rootReady=true; rootStatus="Root 已自动检测 · UID 0"
                if(showResult) report("Root 已验证")
                requestRuntimePermissions()
            } } catch(ex:Exception) { runOnUiThread {
                if(!isDestroyed&&!isFinishing) { rootChecking=false; rootReady=false; rootStatus=ex.message ?: "Root 检测失败"; if(showResult) report(rootStatus) }
            } }
        }
    }
    private fun requestRuntimePermissions() {
        val requested=mutableListOf<String>()
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            requested.addAll(listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
        for(permission in listOf(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.READ_PHONE_STATE))
            if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) requested.add(permission)
        if(requested.isNotEmpty()) permissions.launch(requested.toTypedArray())
    }
    @Suppress("DEPRECATION") private fun search(query:String) {
        require(Geocoder.isPresent()) { "系统地理编码不可用，请使用坐标或地图选点" }
        message="正在搜索…"
        worker.execute {
            try {
                val results=Geocoder(this,Locale.getDefault()).getFromLocationName(query,1)
                val result=results?.firstOrNull() ?: error("未找到地点")
                val p=doubleArrayOf(result.latitude,result.longitude); Route(p)
                runOnUiThread { if(!running&&!pending) { selected=p; message="已选择：${result.getAddressLine(0) ?: query}，请到地图查看"; updateMap(); centerMap() } }
            } catch(ex:Exception) { report(ex.message ?: "搜索失败") }
        }
    }
    private fun report(text:String) { runOnUiThread { message=text; Toast.makeText(this,text,Toast.LENGTH_SHORT).show() } }
    private fun showLicense() {
        val text=listOf("LICENSE","THIRD_PARTY_NOTICES.md","MIUIX_LICENSE","LEAFLET_LICENSE","LIBXPOSED_LICENSE").joinToString("\n\n") { name ->
            "$name\n"+assets.open(name).bufferedReader().use { it.readText() }
        }
        val view=android.widget.TextView(this).apply { this.text="SimLocation · Kanisuko\nAGPL-3.0-only\nhttps://github.com/Kanisuko/SimLocation\n\nMiuix: Apache-2.0 · Leaflet: BSD-2-Clause\n\n$text"; setPadding(24,24,24,24) }
        android.app.AlertDialog.Builder(this).setTitle("开源许可").setView(android.widget.ScrollView(this).apply { addView(view) }).setPositiveButton("关闭",null).show()
    }
    @Suppress("SetJavaScriptEnabled") private fun mapView():WebView {
        web?.let { return it }
        return object:WebView(this) {
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
                super.onSizeChanged(w,h,oldw,oldh)
                if(mapLoaded && h>0) evaluateJavascript("resizeMap(${h/resources.displayMetrics.density})",null)
            }
        }.also { view ->
            web=view
            view.layoutParams=android.view.ViewGroup.LayoutParams(-1,-1)
            if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
            view.webChromeClient=object:WebChromeClient() {
                override fun onConsoleMessage(event:ConsoleMessage):Boolean {
                    if(event.messageLevel()==ConsoleMessage.MessageLevel.ERROR) android.util.Log.e("SimLocationMap",event.message())
                    return true
                }
            }
            view.settings.apply { javaScriptEnabled=true; allowFileAccess=false; allowContentAccess=false; mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW; userAgentString="SimLocation/0.5.1 (Android; org.ethertaco.simlocation)" }
            view.addJavascriptInterface(object {
                @JavascriptInterface fun tap(lat:Double,lon:Double) { runOnUiThread {
                    if(showLive) { try { appendLive(doubleArrayOf(lat,lon)) } catch(ex:Exception) { report(ex.message ?: "追加失败") }; return@runOnUiThread }
                    if(running||pending) return@runOnUiThread
                    try { val p=doubleArrayOf(lat,lon); Route(p); if(routeMode) saveRoute(points+p) else { selected=p; updateMap() } } catch(ex:Exception) { report(ex.message!!) }
                } }
                @JavascriptInterface fun stroke(json:String) { runOnUiThread {
                    if(!showLive||!manualDraw||selectLiveStart) return@runOnUiThread
                    try {
                        require(json.length<=150000) { "绘制数据过大" }
                        val array=JSONArray(json); require(array.length() in 2..2000) { "路径至少需要两个点，最多 2000 个点" }
                        val next=DoubleArray(array.length()*2) { array.getJSONArray(it/2).getDouble(it%2) }; appendLive(next)
                    } catch(ex:Exception) { report(ex.message ?: "绘制失败") }
                } }
                @JavascriptInterface fun move(index:Int,lat:Double,lon:Double) { runOnUiThread {
                    if(running||pending||!routeMode||index !in 0 until points.size/2) return@runOnUiThread
                    try { val next=points.clone(); next[2*index]=lat; next[2*index+1]=lon; saveRoute(next) } catch(ex:Exception) { report(ex.message!!) }
                } }
            },"NativeMap")
            view.webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    val uri=request.url
                    if(request.hasGesture() && uri.scheme=="https" && uri.host in listOf("www.openstreetmap.org","leafletjs.com")) startActivity(Intent(Intent.ACTION_VIEW,uri))
                    return true
                }
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    val uri=request.url
                    if(uri.host=="app.simlocation.local" && uri.scheme=="https") {
                        val name=uri.path?.removePrefix("/") ?: ""
                        if(name !in listOf("map.html","map.js","map.css","leaflet.js","leaflet.css") && !name.matches(Regex("images/[a-z-]+\\.png"))) return WebResourceResponse("text/plain","UTF-8",null)
                        return try { WebResourceResponse(when { name.endsWith(".js")->"text/javascript"; name.endsWith(".css")->"text/css"; name.endsWith(".png")->"image/png"; else->"text/html" },"UTF-8",assets.open(name)) } catch(ex:Exception) { WebResourceResponse("text/plain","UTF-8",null) }
                    }
                    if(uri.scheme=="https" && uri.host=="tile.openstreetmap.org") return null
                    return WebResourceResponse("text/plain","UTF-8",null)
                }
                override fun onPageFinished(view:WebView,url:String) { if(url=="https://app.simlocation.local/map.html") {
                    mapLoaded=true
                    view.evaluateJavascript("resizeMap(${view.height/resources.displayMetrics.density})",null)
                    updateMap(); fitMap()
                } }
            }
            view.loadUrl("https://app.simlocation.local/map.html")
        }
    }
    private fun updateMap() {
        if(!mapLoaded) return
        val data=if(showLive) livePoints else points
        val route=JSONArray(); for(i in data.indices step 2) route.put(JSONArray(listOf(data[i],data[i+1])))
        val state=JSONObject().put("route",route).put("selected",JSONArray(selected.toList()))
            .put("current",position?.let { JSONArray(it.toList()) } ?: JSONObject.NULL).put("editable",!pending&&(!running||showLive&&LocationService.live))
            .put("mode",if(showLive) "live" else if(routeMode) "route" else "point").put("draw",showLive&&manualDraw&&!selectLiveStart)
        web?.evaluateJavascript("renderState($state)",null)
    }
    private fun fitMap() { if(mapLoaded) web?.evaluateJavascript("fitRoute()",null) }
    private fun centerMap() { if(mapLoaded) web?.evaluateJavascript("centerPoint(${selected[0]},${selected[1]})",null) }
    override fun onPause() { observer?.close(); web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume(); observer?.start() }
    override fun onDestroy() { web?.removeJavascriptInterface("NativeMap"); web?.destroy(); web=null; worker.shutdown(); super.onDestroy() }
}

private fun navIcon(index:Int):ImageVector = ImageVector.Builder("Tab$index",24.dp,24.dp,24f,24f).apply {
    path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f) {
        when(index) {
            0 -> { moveTo(3f,5f);lineTo(9f,3f);lineTo(15f,5f);lineTo(21f,3f);lineTo(21f,19f);lineTo(15f,21f);lineTo(9f,19f);lineTo(3f,21f);close();moveTo(9f,3f);lineTo(9f,19f);moveTo(15f,5f);lineTo(15f,21f) }
            1 -> { moveTo(4f,5f);lineTo(20f,5f);lineTo(20f,12f);lineTo(4f,12f);lineTo(4f,19f);lineTo(20f,19f) }
            2 -> { moveTo(4f,20f);lineTo(5f,15f);lineTo(16f,4f);lineTo(20f,8f);lineTo(9f,19f);close();moveTo(13f,7f);lineTo(17f,11f) }
            3 -> { moveTo(7f,4f);lineTo(20f,12f);lineTo(7f,20f);close() }
            else -> { moveTo(4f,6f);lineTo(20f,6f);moveTo(4f,12f);lineTo(20f,12f);moveTo(4f,18f);lineTo(20f,18f);moveTo(8f,3f);lineTo(8f,9f);moveTo(16f,9f);lineTo(16f,15f);moveTo(10f,15f);lineTo(10f,21f) }
        }
    }
}.build()
