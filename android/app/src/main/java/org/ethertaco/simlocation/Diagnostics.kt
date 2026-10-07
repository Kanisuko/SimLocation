// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import android.telephony.*
import java.util.Locale

/** Observes public APIs in our own process. Never equates sent coordinates with readback. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class Diagnostics(private val context:Context) : AutoCloseable {
    private val manager=context.getSystemService(LocationManager::class.java)
    private val values=linkedMapOf<String,Location>()
    private val errors=linkedMapOf<String,String>()
    private val listeners=mutableListOf<LocationListener>()
    private var asyncResult="尚未请求"
    private var closed=false
    private var generation=0
    private var cellListener:TelephonyCallback?=null
    private var subscribed="尚未订阅"
    private var currentResult="尚未请求"
    private var recentResult="尚未读取"
    private val cancellations=mutableListOf<android.os.CancellationSignal>()
    fun start() {
        if(listeners.isNotEmpty()) return
        closed=false
        val currentGeneration=++generation
        errors.clear()
        for(provider in listOf("gps","network","fused")) {
            if(provider !in manager.allProviders) { errors[provider]="系统未提供"; continue }
            try {
                val listener=LocationListener { location -> values[provider]=Location(location) }
                manager.requestLocationUpdates(provider,500L,0f,listener,Looper.getMainLooper())
                listeners.add(listener)
            } catch(ex:Exception) { errors[provider]=ex.message ?: "读取失败" }
        }
        try {
            val listener=object:TelephonyCallback(),TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(info:MutableList<CellInfo>) { if(!closed&&generation==currentGeneration) subscribed=cellSummary(info,activeScene()) }
            }
            context.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(context.mainExecutor,listener)
            cellListener=listener; subscribed="等待订阅回调"
        } catch(ex:Exception) { subscribed="订阅不可用：${ex.javaClass.simpleName}（可能需要电话状态权限）" }
    }
    fun snapshot(expected:DoubleArray?):String = buildString {
        val scene=activeScene()
        for(provider in listOf("gps","network","fused")) {
            val value=values[provider]
            append(provider).append(": ")
            if(value==null) append(errors[provider] ?: "等待实际定位回调") else {
                val age=(SystemClock.elapsedRealtimeNanos()-value.elapsedRealtimeNanos)/1e9
                val distance=expected?.let { FloatArray(1).also { result -> Location.distanceBetween(it[0],it[1],value.latitude,value.longitude,result) }[0] }
                append(String.format(Locale.ROOT,"%.6f, %.6f\n  %.1f s 前 · 精度 %.1f m · mock=%s",value.latitude,value.longitude,age,value.accuracy,value.isMock))
                if(distance!=null) append(String.format(Locale.ROOT," · 偏差 %.1f m",distance))
                if(value.hasSpeed()) append(String.format(Locale.ROOT," · %.2f km/h",value.speed*3.6))
            }
            append('\n')
        }
        try {
            val wifi=context.applicationContext.getSystemService(WifiManager::class.java)
            val scans=wifi.scanResults
            val matched=scene?.takeIf { it.scans }?.let { s -> scans.size==s.wifi.size && s.wifi.all { a -> scans.any { scan -> scan.BSSID.equals(a.getString("bssid"),true)&&scan.SSID==a.getString("ssid")&&scan.level==a.getInt("rssi")&&scan.frequency==a.getInt("frequency") } } }
            append("WiFi 扫描缓存: ${scans.size} 个 AP · ${matchLabel(matched)}（不主动扫描）\n")
            scans.take(4).forEach { append("  ${it.SSID} · ${it.BSSID} · ${it.level} dBm / ${it.frequency} MHz\n") }
            val info=wifi.connectionInfo
            val ap=scene?.takeIf { it.connection }?.let { it.wifi[it.connected] }
            val connected=ap?.let { info.bssid.equals(it.getString("bssid"),true) && info.ssid.removeSurrounding("\"")==it.getString("ssid") && info.rssi==it.getInt("rssi") && info.frequency==it.getInt("frequency") }
            append("WiFi 连接: ${info.ssid} · ${info.bssid} · ${info.rssi} dBm · ${matchLabel(connected)}\n")
        } catch(ex:Exception) { append("WiFi 读取不可用: ${ex.javaClass.simpleName}\n") }
        try {
            val cells=context.getSystemService(TelephonyManager::class.java).allCellInfo ?: emptyList()
            append("基站缓存: ${cellSummary(cells,scene)}\n")
        } catch(ex:Exception) { append("基站读取不可用: ${ex.javaClass.simpleName}\n") }
        append("基站异步查询: $asyncResult\n")
        append("基站持续订阅: $subscribed\n")
        append("最近位置: $recentResult\n单次位置: $currentResult\n")
        append("\n系统接口报告（本次启动）:\n${BackendHealthProvider.summary(context)}\n")
    }
    private fun activeScene():WirelessScenario? = if(BackendController.token().isEmpty()) null else runCatching { BackendController.saved(context) }.getOrNull()
    private fun matchLabel(value:Boolean?):String=when(value) { true->"与活动配置一致"; false->"与活动配置不一致"; null->"未启用该项 / 原系统数据" }
    private fun cellSummary(values:List<CellInfo>,scene:WirelessScenario?):String {
        fun identity(value:CellInfo):String? = when(val id=value.cellIdentity) {
            is CellIdentityLte -> "LTE:${id.mccString}:${id.mncString}:${id.ci}:${id.tac}:${id.pci}:${id.earfcn}"
            is CellIdentityNr -> "NR:${id.mccString}:${id.mncString}:${id.nci}:${id.tac}:${id.pci}:${id.nrarfcn}"
            else -> null
        }
        val matched=scene?.takeIf { it.cell }?.let { s -> values.size==s.cells.size && s.cells.all { c ->
            val expected="${c.getString("type")}:${c.getString("mcc")}:${c.getString("mnc")}:${c.getLong("id")}:${c.getInt("tac")}:${c.getInt("pci")}:${c.getInt("arfcn")}"
            values.any { identity(it)==expected && it.isRegistered==c.optBoolean("registered",false) && it.cellSignalStrength.dbm==c.getInt("rsrp") }
        } }
        return "${values.size} 条 · ${matchLabel(matched)}"+values.take(4).joinToString("") { "\n  ${identity(it) ?: it.cellIdentity.javaClass.simpleName} · ${it.cellSignalStrength.dbm} dBm" }
    }
    fun requestCells() {
        if(closed) return
        asyncResult="等待系统回调…"
        val requestGeneration=generation
        val requestToken=BackendController.token()
        try {
            context.getSystemService(TelephonyManager::class.java).requestCellInfoUpdate(context.mainExecutor,object:TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(info:MutableList<CellInfo>) { if(!closed&&generation==requestGeneration) asyncResult=if(requestToken!=BackendController.token()) "旧会话回调：${info.size} 条，未与新配置比较" else cellSummary(info,activeScene()) }
                override fun onError(code:Int,detail:Throwable?) { if(!closed&&generation==requestGeneration) asyncResult="失败 $code: ${detail?.javaClass?.simpleName ?: "系统未提供详情"}" }
            })
        } catch(ex:Exception) { asyncResult="请求失败: ${ex.javaClass.simpleName}" }
    }
    fun requestLocations() {
        if(closed) return
        cancellations.forEach { it.cancel() }; cancellations.clear()
        val requestGeneration=generation
        val recent=mutableListOf<String>()
        val results=linkedMapOf<String,String>()
        fun label(provider:String,value:Location?):String = if(value==null) "$provider: 无数据" else "$provider: mock=${value.isMock} · ${(SystemClock.elapsedRealtimeNanos()-value.elapsedRealtimeNanos)/1000000000} s 前"
        for(provider in listOf("gps","network","fused")) {
            if(provider !in manager.allProviders) continue
            try {
                recent.add(label(provider,manager.getLastKnownLocation(provider)))
                val cancellation=android.os.CancellationSignal(); cancellations.add(cancellation)
                results[provider]="等待回调"
                manager.getCurrentLocation(provider,cancellation,context.mainExecutor) { value ->
                    if(!closed&&generation==requestGeneration) { results[provider]=label(provider,value); currentResult=results.values.joinToString(" / ") }
                }
            } catch(ex:Exception) { recent.add("$provider: ${ex.javaClass.simpleName}") }
        }
        recentResult=recent.joinToString(" / "); currentResult=results.values.joinToString(" / ")
    }
    override fun close() {
        closed=true; generation++; listeners.forEach { manager.removeUpdates(it) }; listeners.clear(); values.clear()
        cellListener?.let { context.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) }; cellListener=null
        cancellations.forEach { it.cancel() }; cancellations.clear()
    }
}
