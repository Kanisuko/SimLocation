// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;
import android.app.Instrumentation;
import android.os.Bundle;
import android.location.Location;
import org.json.*;

/** Runs on Android's real JSON implementation. No Root or LSPosed is required. */
public final class BackendTestRunner extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments=args==null ? Bundle.EMPTY : args; start(); }
    private int checks;
    private void check(boolean v) { checks++; if(!v) throw new AssertionError("Check "+checks); }
    private void reject(JSONObject value) throws Exception {
        try { new WirelessScenario(value.toString()); throw new AssertionError("Invalid scene accepted"); }
        catch(IllegalArgumentException | JSONException expected) { checks++; }
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            WirelessScenario sample=new WirelessScenario(WirelessScenario.example());
            check(!sample.enabled()); check(sample.targets.size()==1); check(sample.wifi.size()==1 && sample.cells.size()==2);
            JSONObject full=new JSONObject(WirelessScenario.example()).put("wifiScan",true).put("wifiConnection",true).put("cellEnabled",true).put("clearMock",true);
            check(new WirelessScenario(full.toString()).enabled());
            check(new WirelessScenario(new WirelessScenario(full.toString()).data.toString()).cells.size()==2);
            reject(new JSONObject(full.toString()).put("version",1.5));
            reject(new JSONObject(full.toString()).put("wifiScan","true"));
            reject(new JSONObject(full.toString()).put("connectedWifi",4));
            reject(new JSONObject(full.toString()).put("targets",new JSONArray().put("bad;command")));
            reject(new JSONObject(full.toString()).put("targets","all"));
            JSONObject bad=new JSONObject(full.toString()); bad.getJSONArray("wifi").getJSONObject(0).put("bssid","ff:ff:ff:ff:ff:ff"); reject(bad);
            bad=new JSONObject(full.toString()); bad.getJSONArray("wifi").getJSONObject(0).put("frequency",3500); reject(bad);
            bad=new JSONObject(full.toString()); bad.getJSONArray("cells").getJSONObject(1).put("id",68719476736L); reject(bad);
            bad=new JSONObject(full.toString()); bad.getJSONArray("cells").getJSONObject(0).put("mnc",1); reject(bad);
            bad=new JSONObject(full.toString()); bad.getJSONArray("cells").getJSONObject(0).put("rsrp",-500); reject(bad);
            bad=new JSONObject(full.toString()); bad.getJSONArray("cells").getJSONObject(0).put("id",4.5); reject(bad);
            bad=new JSONObject(full.toString()); bad.getJSONArray("cells").getJSONObject(1).put("registered",true).put("connectionStatus",1); reject(bad);
            check(SessionPolicy.active(100,10000,3,101,10001,3));
            check(!SessionPolicy.active(100,10000,3,101,10001,4));
            Location source=new Location("gps"); source.setMock(true); source.setElapsedRealtimeNanos(2000000);
            source.setLatitude(31); source.setLongitude(121); source.setAccuracy(5); source.setSpeed(2);
            Location copy=LocationAttributeAdapter.clear(source,1,true,false);
            check(copy!=source && !copy.isMock() && source.isMock());
            check(copy.getLatitude()==31 && copy.getLongitude()==121 && copy.getAccuracy()==5 && copy.getSpeed()==2 && copy.getElapsedRealtimeNanos()==2000000);
            check(LocationAttributeAdapter.clear(source,3,true,false)==source); // Old cached location.
            check(LocationAttributeAdapter.clear(source,1,false,true)==source); // Wrong source.
            check(LocationAttributeAdapter.clear(copy,1,true,false)==copy); // Already real.
            check(LocationAttributeAdapter.clear(null,1,true,false)==null);
            if("e2e".equals(arguments.getString("mode"))) BackendDeviceTest.run(this);
            result.putString("stream","\nBackend config tests PASS: "+checks+" checks\n"); finish(-1,result);
        } catch(Throwable ex) { result.putString("stream","\nFAIL: "+ex+"\n"); finish(0,result); }
    }
}
