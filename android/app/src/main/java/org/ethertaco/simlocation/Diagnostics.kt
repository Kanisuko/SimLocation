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
import android.telephony.TelephonyManager
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
    fun start() {
        if(listeners.isNotEmpty()) return
        closed=false
        errors.clear()
        for(provider in listOf("gps","network","fused")) {
            if(provider !in manager.allProviders) { errors[provider]="系统未提供"; continue }
            try {
                val listener=LocationListener { location -> values[provider]=Location(location) }
                manager.requestLocationUpdates(provider,500L,0f,listener,Looper.getMainLooper())
                listeners.add(listener)
            } catch(ex:Exception) { errors[provider]=ex.message ?: "读取失败" }
        }
    }
    fun snapshot(expected:DoubleArray?):String = buildString {
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
            append("WiFi 缓存观测: ${wifi.scanResults.size} 个 AP（未注入；不主动扫描）\n")
            append("WiFi 连接观测: RSSI ${wifi.connectionInfo.rssi} dBm（未注入）\n")
        } catch(ex:Exception) { append("WiFi 读取不可用: ${ex.javaClass.simpleName}\n") }
        try {
            val cells=context.getSystemService(TelephonyManager::class.java).allCellInfo ?: emptyList()
            append("基站缓存观测: ${cells.size} 条（未注入）\n")
        } catch(ex:Exception) { append("基站读取不可用: ${ex.javaClass.simpleName}\n") }
        append("基站异步查询: $asyncResult\n")
    }
    fun requestCells() {
        if(closed) return
        asyncResult="等待系统回调…"
        try {
            context.getSystemService(TelephonyManager::class.java).requestCellInfoUpdate(context.mainExecutor,object:TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(info:MutableList<android.telephony.CellInfo>) { if(!closed) asyncResult="收到 ${info.size} 条实际观测（未注入）" }
                override fun onError(code:Int,detail:Throwable?) { if(!closed) asyncResult="失败 $code: ${detail?.javaClass?.simpleName ?: "系统未提供详情"}" }
            })
        } catch(ex:Exception) { asyncResult="请求失败: ${ex.javaClass.simpleName}" }
    }
    override fun close() { closed=true; listeners.forEach { manager.removeUpdates(it) }; listeners.clear(); values.clear() }
}
