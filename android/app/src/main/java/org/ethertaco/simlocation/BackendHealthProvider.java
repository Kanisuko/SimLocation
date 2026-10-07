// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import org.json.JSONObject;

/** Receives bounded capability counters, never configuration commands or observation identifiers. */
public final class BackendHealthProvider extends ContentProvider {
    public static final Uri URI=Uri.parse("content://org.ethertaco.simlocation.health");
    @Override public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method,String arg,Bundle extras) {
        int uid=Binder.getCallingUid();
        if(uid!=1000 && uid!=1001) throw new SecurityException("Only system and phone processes can report");
        if(!"report".equals(method) || arg==null || !arg.matches("[a-z-]{1,32}") || extras==null) throw new IllegalArgumentException("Invalid report");
        String state=extras.getString("state","");
        if(!state.matches("installed|substituted|failed")) throw new IllegalArgumentException("Invalid state");
        String detail=extras.getString("detail",""); if(detail.length()>200) detail=detail.substring(0,200);
        int boot=Settings.Global.getInt(getContext().getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        var prefs=getContext().getSharedPreferences("backend-health",Context.MODE_PRIVATE);
        var edit=prefs.edit(); if(prefs.getInt("boot",-2)!=boot) edit.clear();
        try {
            JSONObject event=new JSONObject().put("state",state).put("api",extras.getInt("api",0)).put("version",extras.getInt("version",0)).put("at",SystemClock.elapsedRealtime())
                .put("token",extras.getString("token","")).put("detail",detail);
            edit.putInt("boot",boot).putString(arg,event.toString()).apply();
        } catch(Exception ex) { throw new IllegalArgumentException(ex); }
        return Bundle.EMPTY;
    }
    public static String summary(Context c) {
        var prefs=c.getSharedPreferences("backend-health",Context.MODE_PRIVATE);
        int boot=Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if(boot!=prefs.getInt("boot",-2)) return "尚无本次启动的系统接口报告";
        StringBuilder text=new StringBuilder();
        for(String key:new String[]{"wifi-scan","wifi-connection","cell-cache","cell-async","cell-listener","location-stream","location-current","location-last"}) {
            String raw=prefs.getString(key,null); if(raw==null) { text.append(key).append(": 未报告\n"); continue; }
            try {
                JSONObject event=new JSONObject(raw); String state=event.getString("state");
                boolean current=!BackendController.token().isEmpty() && BackendController.token().equals(event.optString("token"));
                text.append(key).append(": ").append(state).append(" · API ").append(event.getInt("api"));
                if(state.equals("substituted")) text.append(current ? " · 当前会话命中" : " · 历史命中");
                if(!event.optString("detail").isEmpty()) text.append(" · ").append(event.optString("detail"));
                text.append('\n');
            } catch(Exception ex) { text.append(key).append(": 报告损坏\n"); }
        }
        return text.toString().trim();
    }
    public static void require(Context c,String key) throws Exception {
        var prefs=c.getSharedPreferences("backend-health",Context.MODE_PRIVATE);
        int boot=Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        String value=prefs.getString(key,null);
        if(boot<0 || boot!=prefs.getInt("boot",-2) || value==null) throw new IllegalStateException("系统接口 "+key+" 未加载，请启用模块并重启，解锁后稍等或打开功能验证页");
        JSONObject event=new JSONObject(value);
        if(event.optInt("version")!=BuildConfig.VERSION_CODE || event.optInt("api")<101 || "failed".equals(event.optString("state")))
            throw new IllegalStateException("系统接口 "+key+" 版本不符或不可用，请检查功能验证页并重启");
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o) { throw new SecurityException("Not readable"); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u,ContentValues v) { throw new SecurityException("Not writable"); }
    @Override public int delete(Uri u,String s,String[] a) { throw new SecurityException("Not writable"); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new SecurityException("Not writable"); }
}
