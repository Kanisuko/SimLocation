// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.*;
import android.location.*;
import android.net.wifi.*;
import android.os.*;
import android.telephony.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Explicit opt-in real-device test. Sample affects only this application's wireless observations. */
final class BackendDeviceTest {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void await(BooleanSupplier condition,long timeout,String message) throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end) { if(condition.getAsBoolean()) return; Thread.sleep(150); }
        throw new AssertionError(message+"; playback="+LocationService.status+"; backend="+BackendController.status);
    }
    private static boolean running() {
        try { var f=LocationService.class.getDeclaredField("running"); f.setAccessible(true); return f.getBoolean(null); }
        catch(Exception ex) { throw new RuntimeException(ex); }
    }
    private static boolean sampleCells(List<CellInfo> values) {
        if(values==null || values.size()!=2) return false;
        boolean lte=false,nr=false;
        for(CellInfo c:values) {
            if(c.getCellIdentity() instanceof CellIdentityLte id) lte=id.getCi()==12345 && "001".equals(id.getMccString()) && c.getCellSignalStrength().getDbm()==-90;
            if(c.getCellIdentity() instanceof CellIdentityNr id) nr=id.getNci()==123456789 && "001".equals(id.getMccString()) && c.getCellSignalStrength().getDbm()==-95;
        }
        return lte && nr;
    }
    static void run(Instrumentation instrument) throws Exception {
        Context c=instrument.getTargetContext();
        check(!running(),"Stop existing playback before this test");
        // HyperOS can block a test process's background activity start. Launch with the test shell.
        try(var input=new ParcelFileDescriptor.AutoCloseInputStream(instrument.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
            .executeShellCommand("am start -W -n "+c.getPackageName()+"/.MainActivity"))) { input.readAllBytes(); }
        await(() -> BackendController.status.contains("API "),8000,"Modern framework service did not connect");
        var prefs=c.getSharedPreferences("wireless",Context.MODE_PRIVATE); String original=prefs.getString("scene",null);
        var manager=c.getSystemService(LocationManager.class); var wifi=c.getSystemService(WifiManager.class); var phone=c.getSystemService(TelephonyManager.class);
        List<ScanResult> baseline=wifi.getScanResults(); check(!baseline.isEmpty(),"WiFi scan cache must contain real observations");
        check(wifi.getConnectionInfo().getBSSID()!=null && !"02:00:00:00:00:00".equals(wifi.getConnectionInfo().getBSSID()),"Connected WiFi observation must be accessible");
        check(phone.getAllCellInfo()!=null && !phone.getAllCellInfo().isEmpty(),"Cell cache must contain real observations");
        AtomicReference<Location> gps=new AtomicReference<>();
        LocationListener location=gps::set;
        AtomicReference<List<CellInfo>> subscribed=new AtomicReference<>();
        class Subscription extends TelephonyCallback implements TelephonyCallback.CellInfoListener { @Override public void onCellInfoChanged(List<CellInfo> data) { subscribed.set(data); } }
        Subscription callback=new Subscription();
        boolean started=false;
        try {
            // The diagnostics endpoint must reject configuration/report forgery even by our own UID.
            try { c.getContentResolver().call(BackendHealthProvider.URI,"report","wifi-scan",new Bundle()); throw new AssertionError("Health report accepted unprivileged UID"); }
            catch(SecurityException expected) { }
            JSONObject scene=new JSONObject(WirelessScenario.example()).put("wifiScan",true).put("wifiConnection",true).put("cellEnabled",true).put("clearMock",true);
            check(prefs.edit().putString("scene",scene.toString()).commit(),"Cannot save test scene");
            BackendController.refreshProfile();
            // Subscriptions exist before playback, so the test also covers an existing own-app listener.
            manager.requestLocationUpdates("gps",500,0,location,Looper.getMainLooper());
            phone.registerTelephonyCallback(c.getMainExecutor(),callback);
            double[] point={31.2304,121.4737};
            c.startForegroundService(new Intent(c,LocationService.class).setAction("start").putExtra("points",point)); started=true;
            await(BackendDeviceTest::running,10000,"Playback did not start; inspect SimLocation status");
            check(BackendHealthProvider.acknowledged(c,"session-system",BackendController.token()),"System did not acknowledge current configuration");
            check(BackendHealthProvider.acknowledged(c,"session-phone",BackendController.token()),"Phone did not acknowledge current configuration");
            check(!BackendHealthProvider.acknowledged(c,"session-system",UUID.randomUUID().toString()),"Historical report accepted an unrelated session");
            await(() -> { List<ScanResult> result=wifi.getScanResults(); return result.size()==1 && "02:53:49:4d:00:01".equals(result.get(0).BSSID) && result.get(0).level==-55; },6000,"WiFi scan substitution failed");
            WifiInfo info=wifi.getConnectionInfo(); check("02:53:49:4d:00:01".equals(info.getBSSID()) && info.getRssi()==-55 && info.getFrequency()==2412,"WiFi connection substitution failed");
            check(sampleCells(phone.getAllCellInfo()),"LTE/NR cached substitution failed");
            CountDownLatch done=new CountDownLatch(1); AtomicReference<List<CellInfo>> async=new AtomicReference<>();
            phone.requestCellInfoUpdate(c.getMainExecutor(),new TelephonyManager.CellInfoCallback() { @Override public void onCellInfo(List<CellInfo> values) { async.set(values); done.countDown(); } });
            check(done.await(10,TimeUnit.SECONDS) && sampleCells(async.get()),"Asynchronous cell substitution failed");
            // notifyNow may have delivered baseline first; a new subscription while active exercises synthetic notification.
            phone.unregisterTelephonyCallback(callback); phone.registerTelephonyCallback(c.getMainExecutor(),callback);
            await(() -> sampleCells(subscribed.get()),6000,"Cell subscription substitution failed");
            await(() -> gps.get()!=null && !gps.get().isMock(),6000,"GPS listener still marked mock");
            Location last=manager.getLastKnownLocation("gps"); check(last!=null && !last.isMock(),"Last location still marked mock");
            CountDownLatch one=new CountDownLatch(1); AtomicReference<Location> current=new AtomicReference<>();
            manager.getCurrentLocation("gps",null,c.getMainExecutor(),value -> { current.set(value); one.countDown(); });
            check(one.await(8,TimeUnit.SECONDS) && current.get()!=null && !current.get().isMock(),"Current location still marked mock");
            // Halt publication without clearing the last lease: system data must recover independently.
            synchronized(BackendController.class) { var f=BackendController.class.getDeclaredField("active"); f.setAccessible(true); f.set(null,null); }
            Thread.sleep(SessionPolicy.LEASE_MS+500);
            check(!"02:53:49:4d:00:01".equals(wifi.getScanResults().get(0).BSSID),"Expired WiFi lease did not recover");
            check(!sampleCells(phone.getAllCellInfo()),"Expired cell lease did not recover");
            await(() -> gps.get()!=null && gps.get().isMock(),3000,"Expired mock-attribute lease did not recover");
            BackendController.end();
        } finally {
            BackendController.end();
            try {
                manager.removeUpdates(location); phone.unregisterTelephonyCallback(callback);
                if(started) {
                    c.startService(new Intent(c,LocationService.class).setAction("stop"));
                    await(() -> !running() && !c.getSharedPreferences("provider-recovery",Context.MODE_PRIVATE).contains("original-mode"),8000,"Service cleanup failed");
                }
            } finally {
                if(original==null) prefs.edit().remove("scene").commit(); else prefs.edit().putString("scene",original).commit();
                BackendController.refreshProfile();
            }
        }
        Bundle progress=new Bundle(); progress.putString("stream","\nWireless e2e PASS: session acknowledgements, WiFi, LTE/NR, callback, subscription, Location flags, expiry and stop\n"); instrument.sendStatus(0,progress);
    }
}
