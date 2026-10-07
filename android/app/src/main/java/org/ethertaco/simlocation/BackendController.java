// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import io.github.libxposed.service.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

/** App-side modern remote preferences. Optional: plain Root playback needs no framework. */
public final class BackendController {
    private static final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private static volatile XposedService service;
    private static Context app;
    private static JSONObject active;
    private static String token="";
    public static volatile String status="未连接现代系统后端（Root 回放可独立使用）";
    public static synchronized void initialize(Context context) {
        if(app!=null) return;
        app=context.getApplicationContext();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService s) { worker.execute(() -> {
                try {
                    if(s.getApiVersion()<101 || (s.getFrameworkProperties()&XposedService.PROP_CAP_REMOTE)==0) throw new IllegalStateException("框架缺少现代远程配置能力");
                    service=s; status=s.getFrameworkName()+" · API "+s.getApiVersion()+" · "+s.getScope();
                    synchronized(BackendController.class) { publish(); }
                } catch(Exception ex) { service=null; status="系统后端不可用："+ex.getMessage(); }
            }); }
            @Override public void onServiceDied(XposedService s) { if(service==s) { service=null; status="系统后端连接中断；会话将在 12 s 内失效"; } }
        });
        worker.scheduleAtFixedRate(() -> {
            synchronized(BackendController.class) {
                if(active==null) return;
                try { publish(); } catch(Exception ex) { status="系统配置心跳失败："+ex.getMessage(); }
            }
        },3,3,TimeUnit.SECONDS);
    }
    public static WirelessScenario saved(Context context) throws JSONException {
        return new WirelessScenario(context.getSharedPreferences("wireless",Context.MODE_PRIVATE).getString("scene",WirelessScenario.example()));
    }
    /** Called from a worker before starting providers. Throws instead of silently ignoring options. */
    public static synchronized String begin(Context context,boolean gps,boolean network) throws Exception {
        WirelessScenario scene=saved(context);
        active=null; token="";
        if(!scene.enabled()) { publish(); return ""; }
        XposedService s=service;
        if(s==null) throw new IllegalStateException("请在 LSPosed 启用 SimLocation 的系统框架与电话服务作用域，重启并打开主应用");
        var scope=s.getScope();
        if(!scope.contains("system") || scene.cell && !scope.contains("com.android.phone")) throw new IllegalStateException("系统后端作用域缺失，请检查 LSPosed 并重启");
        if(scene.scans) BackendHealthProvider.require(context,"wifi-scan");
        if(scene.connection) BackendHealthProvider.require(context,"wifi-connection");
        if(scene.cell) { BackendHealthProvider.require(context,"cell-cache"); BackendHealthProvider.require(context,"cell-async"); BackendHealthProvider.require(context,"cell-listener"); }
        if(scene.clearMock) { BackendHealthProvider.require(context,"location-stream"); BackendHealthProvider.require(context,"location-current"); BackendHealthProvider.require(context,"location-last"); }
        JSONObject next=new JSONObject().put("scene",scene.data).put("gps",gps).put("network",network)
            .put("user",Process.myUid()/100000).put("started",SystemClock.elapsedRealtime()).put("boot",Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1));
        String nextToken=UUID.randomUUID().toString(); next.put("token",nextToken);
        active=next; token=nextToken;
        try {
            publish();
            // Exercise protected system entry points even when their result cache is empty.
            try {
                context.getSystemService(android.location.LocationManager.class).getLastKnownLocation("gps");
                if(scene.cell) context.getSystemService(android.telephony.TelephonyManager.class).getAllCellInfo();
            } catch(SecurityException ex) {
                throw new IllegalStateException("系统配置验证需要位置权限，请允许精确位置后重试",ex);
            }
            long deadline=SystemClock.elapsedRealtime()+4000;
            while(!BackendHealthProvider.acknowledged(context,"session-system",nextToken)
                || scene.cell&&!BackendHealthProvider.acknowledged(context,"session-phone",nextToken)) {
                if(SystemClock.elapsedRealtime()>=deadline) throw new IllegalStateException("系统模块未确认当前配置，请重启手机后重试（覆盖安装后需重新加载）");
                Thread.sleep(50);
            }
        } catch(Exception ex) {
            active=null; token="";
            try { publish(); } catch(Exception cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
        return nextToken;
    }
    public static synchronized void end() {
        active=null; token="";
        worker.execute(() -> { synchronized(BackendController.class) {
            if(active==null) try { publish(); } catch(Exception ex) { status="停止配置发布失败；12 s 后失效："+ex.getMessage(); }
        } });
    }
    private static void publish() throws JSONException {
        XposedService s=service; if(s==null) { if(active!=null) throw new IllegalStateException("框架服务未连接"); return; }
        String value="";
        if(active!=null) {
            active.put("issued",SystemClock.elapsedRealtime()).put("wall",System.currentTimeMillis()); value=active.toString();
        }
        if(!s.getRemotePreferences("simulation").edit().putString("session",value).putString("profile",saved(app).data.toString()).commit()) throw new IllegalStateException("配置未送达框架");
    }
    public static void refreshProfile() { worker.execute(() -> { synchronized(BackendController.class) { try { publish(); } catch(Exception ex) { status="配置发布失败："+ex.getMessage(); } } }); }
    public static synchronized String token() { return token; }
    public static synchronized String description() {
        if(active==null) return "";
        JSONObject scene=active.optJSONObject("scene"); if(scene==null) return "";
        return " · 无线请求："+scene.optString("name")+(scene.optBoolean("wifiScan") ? " / WiFi 扫描" : "")
            +(scene.optBoolean("wifiConnection") ? " / WiFi 连接" : "")+(scene.optBoolean("cellEnabled") ? " / LTE/NR" : "")
            +(scene.optBoolean("clearMock") ? " / mock=false（实验）" : "");
    }
}
