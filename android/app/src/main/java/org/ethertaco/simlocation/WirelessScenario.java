// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned user data, validated identically before publication and in system processes. */
public final class WirelessScenario {
    public static final String CLIENT="org.ethertaco.simlocation";
    public final JSONObject data;
    public final List<JSONObject> wifi,cells;
    public final Set<String> targets;
    public final boolean scans,connection,cell,clearMock;
    public final int connected;
    public WirelessScenario(String json) throws JSONException {
        if(json==null || json.length()>60000 || json.getBytes(StandardCharsets.UTF_8).length>60000) throw new IllegalArgumentException("场景超过 60 KB");
        data=new JSONObject(json);
        range(data,"version",1,1);
        for(String key:new String[]{"wifiScan","wifiConnection","cellEnabled","clearMock"}) require(!data.has(key)||data.get(key) instanceof Boolean,"开关 "+key+" 必须为布尔值");
        require(data.get("name") instanceof String,"场景名称必须为字符串");
        String name=data.getString("name"); require(!name.isBlank() && name.length()<=80,"场景名称需为 1–80 字符");
        scans=data.optBoolean("wifiScan",false); connection=data.optBoolean("wifiConnection",false);
        cell=data.optBoolean("cellEnabled",false); clearMock=data.optBoolean("clearMock",false);
        targets=new HashSet<>(); targets.add(CLIENT);
        JSONArray requested=data.optJSONArray("targets");
        require(!data.has("targets")||requested!=null,"targets 必须是数组");
        if(requested!=null) {
            require(requested.length()<=32,"最多指定 32 个应用");
            for(int i=0;i<requested.length();i++) {
                String p=requested.getString(i);
                require(p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") && !p.equals("com.android.phone"),"应用包名无效："+p);
                targets.add(p);
            }
        }
        wifi=new ArrayList<>(); cells=new ArrayList<>();
        JSONArray aps=data.getJSONArray("wifi"), towers=data.getJSONArray("cells");
        require(aps.length()<=64 && towers.length()<=32,"最多 64 个 AP、32 个基站");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<aps.length();i++) {
            JSONObject a=aps.getJSONObject(i);
            String b=a.getString("bssid").toLowerCase(Locale.ROOT), ssid=a.getString("ssid");
            require(b.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}") && (Integer.parseInt(b.substring(0,2),16)&1)==0
                && !b.equals("00:00:00:00:00:00") && !b.equals("02:00:00:00:00:00") && ids.add(b),"BSSID 无效或重复");
            require(ssid.getBytes(StandardCharsets.UTF_8).length<=32,"SSID 最多 32 UTF-8 字节");
            range(a,"rssi",-127,0); int frequency=(int)range(a,"frequency",2400,7125);
            require(frequency<=2500 || frequency>=4900 && frequency<=5900 || frequency>=5925,"WiFi 频率不在支持的频段");
            require(a.optString("capabilities","[ESS]").length()<=256,"WiFi 能力字符串过长");
            a.put("bssid",b); wifi.add(a);
        }
        connected=data.has("connectedWifi") ? (int)range(data,"connectedWifi",-1,63) : -1;
        require(connected>=-1 && connected<wifi.size(),"连接 AP 索引无效");
        require(!scans || !wifi.isEmpty(),"WiFi 扫描模拟至少需要一个 AP");
        require(!connection || connected>=0,"WiFi 连接模拟请选择一个 AP");
        ids.clear(); int primary=0;
        for(int i=0;i<towers.length();i++) {
            JSONObject c=towers.getJSONObject(i); String type=c.getString("type");
            require(type.equals("LTE") || type.equals("NR"),"当前支持 LTE / NR");
            String mcc=c.getString("mcc"),mnc=c.getString("mnc");
            require(c.get("mcc") instanceof String && c.get("mnc") instanceof String,"MCC / MNC 必须为字符串");
            require(mcc.matches("[0-9]{3}") && mnc.matches("[0-9]{2,3}"),"MCC 为三位，MNC 为两或三位，均需用字符串保留前导零");
            boolean nr=type.equals("NR"); long id=range(c,"id",0,nr ? 68719476735L : 268435455);
            require(ids.add(type+":"+mcc+":"+mnc+":"+id),"基站标识重复");
            range(c,"tac",0,nr ? 16777215 : 65535); range(c,"pci",0,nr ? 1007 : 503);
            range(c,"arfcn",0,nr ? 3279165 : 262143); range(c,"rsrp",nr ? -156 : -140,nr ? -31 : -43);
            range(c,"rsrq",nr ? -20 : -34,nr ? -3 : -3); range(c,"sinr",nr ? -23 : -20,nr ? 23 : 30);
            JSONArray bands=c.optJSONArray("bands");
            require(!c.has("bands")||bands!=null,"bands 必须是数组");
            if(bands!=null) { require(bands.length()<=16,"频段数量过多"); for(int j=0;j<bands.length();j++) { Object b=bands.get(j); require(b instanceof Number && ((Number)b).doubleValue()==Math.rint(((Number)b).doubleValue()) && bands.getInt(j)>0 && bands.getInt(j)<=1024,"频段编号无效"); } }
            require(!c.has("registered") || c.get("registered") instanceof Boolean,"registered 必须为布尔值");
            int status=c.has("connectionStatus") ? (int)range(c,"connectionStatus",0,2) : c.optBoolean("registered",false) ? 1 : 0;
            require(status>=0 && status<=2,"连接状态需为 0、1 或 2");
            require(status==0 || c.optBoolean("registered",false),"已连接基站必须 registered=true");
            if(status==1) primary++;
            rangeOptional(c,"bandwidth",0,20000,20000);
            require(c.optString("operator","").length()<=80,"运营商名称过长"); cells.add(c);
        }
        require(primary<=1,"只能有一个主服务基站");
        require(!cell || !cells.isEmpty(),"基站模拟至少需要一个 LTE / NR 基站");
    }
    private static void require(boolean good,String message) { if(!good) throw new IllegalArgumentException(message); }
    static long range(JSONObject o,String key,long min,long max) throws JSONException {
        Object value=o.get(key);
        require(value instanceof Number,"字段 "+key+" 必须为数值");
        double d=((Number)value).doubleValue();
        require(Double.isFinite(d) && d==Math.rint(d) && d>=min && d<=max,"字段 "+key+" 超出范围或不是整数"); return (long)d;
    }
    private static void rangeOptional(JSONObject o,String key,long min,long max,long fallback) throws JSONException { if(!o.has(key)) o.put(key,fallback); range(o,key,min,max); }
    public boolean enabled() { return scans || connection || cell || clearMock; }
    public static String example() {
        return """
            {"version":1,"name":"测试样例（非地点数据库）","wifiScan":false,"wifiConnection":false,"cellEnabled":false,"clearMock":false,"targets":[],"connectedWifi":0,
             "wifi":[{"ssid":"SimLocation-Test","bssid":"02:53:49:4d:00:01","rssi":-55,"frequency":2412,"capabilities":"[ESS]"}],
             "cells":[{"type":"LTE","mcc":"001","mnc":"01","id":12345,"tac":100,"pci":10,"arfcn":1300,"bands":[3],"bandwidth":20000,"rsrp":-90,"rsrq":-10,"sinr":15,"registered":true,"connectionStatus":1,"operator":"Test"},
                      {"type":"NR","mcc":"001","mnc":"01","id":123456789,"tac":101,"pci":20,"arfcn":640000,"bands":[78],"rsrp":-95,"rsrq":-12,"sinr":12,"registered":false,"connectionStatus":0,"operator":"Test"}]}
            """;
    }
}
