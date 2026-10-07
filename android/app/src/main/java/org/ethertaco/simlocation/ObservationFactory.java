// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import android.net.wifi.*;
import android.os.SystemClock;
import android.telephony.*;
import org.json.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Android 15 system-process adapter. Failure leaves original data intact. No radio state changes. */
final class ObservationFactory {
    static Object construct(Class<?> type,Class<?>[] signature,Object... args) throws ReflectiveOperationException {
        Constructor<?> c=type.getDeclaredConstructor(signature); c.setAccessible(true); return c.newInstance(args);
    }
    static Object call(Object receiver,String name,Class<?>[] signature,Object... args) throws ReflectiveOperationException {
        Method m=receiver.getClass().getMethod(name,signature); m.setAccessible(true); return m.invoke(receiver,args);
    }
    static List<ScanResult> scans(WirelessScenario scene) throws Exception {
        List<ScanResult> result=new ArrayList<>();
        for(JSONObject a:scene.wifi) {
            ScanResult scan=new ScanResult(); scan.SSID=a.getString("ssid"); scan.BSSID=a.getString("bssid");
            scan.level=a.getInt("rssi"); scan.frequency=a.getInt("frequency");
            scan.capabilities=a.optString("capabilities","[ESS]"); scan.timestamp=SystemClock.elapsedRealtimeNanos()/1000;
            Class<?> ssid=Class.forName("android.net.wifi.WifiSsid");
            Object value=ssid.getMethod("fromBytes",byte[].class).invoke(null,(Object)scan.SSID.getBytes(StandardCharsets.UTF_8));
            Field f=ScanResult.class.getDeclaredField("wifiSsid"); f.setAccessible(true); f.set(scan,value);
            result.add(scan);
        }
        return result;
    }
    static WifiInfo connection(WirelessScenario scene,WifiInfo original) throws Exception {
        JSONObject a=scene.wifi.get(scene.connected);
        WifiInfo copy=(WifiInfo)construct(WifiInfo.class,new Class<?>[]{WifiInfo.class},original);
        Class<?> ssid=Class.forName("android.net.wifi.WifiSsid");
        Object value=ssid.getMethod("fromBytes",byte[].class).invoke(null,(Object)a.getString("ssid").getBytes(StandardCharsets.UTF_8));
        call(copy,"setSSID",new Class<?>[]{ssid},value);
        call(copy,"setBSSID",new Class<?>[]{String.class},a.getString("bssid"));
        call(copy,"setRssi",new Class<?>[]{int.class},a.getInt("rssi"));
        call(copy,"setFrequency",new Class<?>[]{int.class},a.getInt("frequency"));
        return copy;
    }
    static List<CellInfo> cells(WirelessScenario scene) throws Exception {
        List<CellInfo> result=new ArrayList<>();
        for(JSONObject c:scene.cells) {
            boolean nr="NR".equals(c.getString("type"));
            String mcc=c.getString("mcc"),mnc=c.getString("mnc"),operator=c.optString("operator","");
            int[] bands=new int[c.optJSONArray("bands")==null ? 0 : c.getJSONArray("bands").length()];
            for(int i=0;i<bands.length;i++) bands[i]=c.getJSONArray("bands").getInt(i);
            int status=c.optInt("connectionStatus",c.optBoolean("registered",false) ? 1 : 0);
            CellInfo info;
            if(nr) {
                Object identity=construct(CellIdentityNr.class,new Class<?>[]{int.class,int.class,int.class,int[].class,String.class,String.class,long.class,String.class,String.class,Collection.class},
                    c.getInt("pci"),c.getInt("tac"),c.getInt("arfcn"),bands,mcc,mnc,c.getLong("id"),operator,operator,Collections.emptyList());
                Object signal=construct(CellSignalStrengthNr.class,new Class<?>[]{int.class,int.class,int.class,int.class,int.class,int.class},
                    c.getInt("rsrp"),c.getInt("rsrq"),c.getInt("sinr"),c.getInt("rsrp"),c.getInt("rsrq"),c.getInt("sinr"));
                info=(CellInfo)construct(CellInfoNr.class,new Class<?>[]{int.class,boolean.class,long.class,CellIdentityNr.class,CellSignalStrengthNr.class},
                    status,c.optBoolean("registered",false),SystemClock.elapsedRealtimeNanos(),identity,signal);
            } else {
                Class<?> csg=Class.forName("android.telephony.ClosedSubscriberGroupInfo");
                Object identity=construct(CellIdentityLte.class,new Class<?>[]{int.class,int.class,int.class,int.class,int[].class,int.class,String.class,String.class,String.class,String.class,Collection.class,csg},
                    c.getInt("id"),c.getInt("pci"),c.getInt("tac"),c.getInt("arfcn"),bands,c.optInt("bandwidth",20000),mcc,mnc,operator,operator,Collections.emptyList(),null);
                Object signal=construct(CellSignalStrengthLte.class,new Class<?>[]{int.class,int.class,int.class,int.class,int.class,int.class},
                    CellInfo.UNAVAILABLE,c.getInt("rsrp"),c.getInt("rsrq"),c.getInt("sinr"),CellInfo.UNAVAILABLE,CellInfo.UNAVAILABLE);
                info=(CellInfo)construct(CellInfoLte.class,new Class<?>[0]);
                call(info,"setCellIdentity",new Class<?>[]{CellIdentityLte.class},identity);
                call(info,"setCellSignalStrength",new Class<?>[]{CellSignalStrengthLte.class},signal);
                call(info,"setRegistered",new Class<?>[]{boolean.class},c.optBoolean("registered",false));
                call(info,"setTimeStamp",new Class<?>[]{long.class},SystemClock.elapsedRealtimeNanos());
                call(info,"setCellConnectionStatus",new Class<?>[]{int.class},status);
            }
            result.add(info);
        }
        return result;
    }
}
