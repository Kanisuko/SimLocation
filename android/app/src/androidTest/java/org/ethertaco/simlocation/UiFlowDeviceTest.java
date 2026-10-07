// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Root device regression: launch, fixed point, selected motion model and paused reconfiguration. */
final class UiFlowDeviceTest {
    private static void check(boolean result,String message) { if(!result) throw new AssertionError(message); }
    private static void await(BooleanSupplier condition,String message) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+12000;
        while(SystemClock.elapsedRealtime()<deadline) { if(condition.getAsBoolean()) return; Thread.sleep(100); }
        throw new AssertionError(message+"; "+LocationService.status);
    }
    private static void set(Instrumentation i,MainActivity a,String name,Object value) {
        i.runOnMainSync(() -> { try {
            // Kotlin state delegates expose their values through private accessors.
            String setter="set"+Character.toUpperCase(name.charAt(0))+name.substring(1);
            Method m=MainActivity.class.getDeclaredMethod(setter,value instanceof Boolean ? boolean.class : String.class);
            m.setAccessible(true); m.invoke(a,value);
        } catch(Exception e) { throw new RuntimeException(e); } });
    }
    private static Object call(MainActivity a,String name,Class<?>[] types,Object... args) {
        try { Method m=MainActivity.class.getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(a,args); }
        catch(InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private static boolean ready(MainActivity a) { return (Boolean)call(a,"getRootReady",new Class<?>[0]); }
    private static void stop(Instrumentation i,MainActivity a,Context c) throws Exception {
        i.runOnMainSync(() -> call(a,"control",new Class<?>[]{String.class},"stop"));
        await(() -> !LocationService.running&&!c.getSharedPreferences("provider-recovery",0).contains("original-mode"),"Cleanup did not finish");
    }
    private static void restore(SharedPreferences prefs,Map<String,?> values) {
        var e=prefs.edit().clear();
        values.forEach((k,v) -> {
            if(v instanceof String s) e.putString(k,s); else if(v instanceof Boolean b) e.putBoolean(k,b);
            else if(v instanceof Integer n) e.putInt(k,n); else if(v instanceof Long n) e.putLong(k,n);
            else if(v instanceof Float n) e.putFloat(k,n); else throw new IllegalArgumentException("Unexpected preference type");
        }); check(e.commit(),"Cannot restore preferences");
    }
    static void run(Instrumentation i,boolean mapCheck,boolean moving,int attributeCheck) throws Exception {
        check(!LocationService.running,"Stop existing playback before this test");
        Context c=i.getTargetContext();
        SharedPreferences motion=c.getSharedPreferences("motion",0), signals=c.getSharedPreferences("signals",0), wireless=c.getSharedPreferences("wireless",0);
        var oldMotion=new HashMap<>(motion.getAll()); var oldSignals=new HashMap<>(signals.getAll()); var oldWireless=new HashMap<>(wireless.getAll());
        LocationManager locations=c.getSystemService(LocationManager.class);
        AtomicReference<Location> gps=new AtomicReference<>(), network=new AtomicReference<>();
        LocationListener gpsListener=gps::set, networkListener=network::set;
        Instrumentation.ActivityMonitor monitor=i.addMonitor(MainActivity.class.getName(),null,false);
        MainActivity a=null;
        Handler diagnostic=new Handler(Looper.getMainLooper());
        AtomicReference<Exception> diagnosticFailure=new AtomicReference<>();
        try {
            JSONObject scene=new JSONObject(WirelessScenario.example());
            if(mapCheck) scene.put("clearMock",true).put("targets",new JSONArray().put("com.autonavi.minimap"));
            check(wireless.edit().putString("scene",scene.toString()).commit(),"Cannot set test scene");
            try(var input=new ParcelFileDescriptor.AutoCloseInputStream(i.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
                .executeShellCommand("am start -W -n "+c.getPackageName()+"/.MainActivity"))) { input.readAllBytes(); }
            a=(MainActivity)monitor.waitForActivityWithTimeout(12000); check(a!=null,"MainActivity did not launch");
            MainActivity activity=a;
            await(() -> ready(activity),"Root was not detected automatically");
            set(i,a,"gpsEnabled",true); set(i,a,"networkEnabled",true); set(i,a,"networkInterval","1");
            set(i,a,"interval","0.4"); set(i,a,"paceEnabled",!moving); set(i,a,"paceFast","invalid");
            set(i,a,"speed",moving ? "15" : "invalid"); set(i,a,"variation",moving ? "0" : "invalid"); set(i,a,"period",moving ? "10" : "invalid");
            locations.requestLocationUpdates("gps",0,0,gpsListener,Looper.getMainLooper());
            locations.requestLocationUpdates("network",0,0,networkListener,Looper.getMainLooper());
            double[] point={31.2304,121.4737};
            double[] coordinates=moving ? new double[]{point[0],point[1],point[0],point[1]+0.01} : point;
            i.runOnMainSync(() -> call(activity,"start",new Class<?>[]{double[].class},(Object)coordinates));
            await(() -> LocationService.running&&LocationService.singlePoint==!moving,"Location session did not start");
            await(() -> gps.get()!=null&&network.get()!=null&&Math.abs(gps.get().getLatitude()-point[0])<1e-7
                && Math.abs(network.get().getLongitude()-point[1])<(moving ? 0.001 : 1e-7),"Coordinates did not reach both providers");
            check(moving ? gps.get().getSpeed()>3 : gps.get().getSpeed()==0&&network.get().getSpeed()==0,"Unexpected movement speed");
            check(LocationService.activeConfiguration.contains("0.4 s"),"Point ignored configured interval");
            long sent=gps.get().getElapsedRealtimeNanos();
            await(() -> gps.get().getElapsedRealtimeNanos()>sent,"Point stopped sending after first fix");
            if(mapCheck) {
                if(attributeCheck>0) diagnostic.post(new Runnable() { public void run() {
                    try {
                    Location l=new Location("gps"); l.setLatitude(point[0]); l.setLongitude(point[1]); l.setAccuracy(5);
                    l.setTime(System.currentTimeMillis()); l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                    if(attributeCheck==1) { l.setSpeed(0); l.setBearing(0); }
                    if(attributeCheck==3) {
                        // Bounded measurement noise while the simulated device remains stationary.
                        double phase=SystemClock.elapsedRealtime()/4000.0;
                        l.setLatitude(point[0]+0.000015*Math.sin(phase));
                        l.setLongitude(point[1]+0.000015*Math.cos(phase)); l.setSpeed(0);
                    }
                    locations.setTestProviderLocation("gps",l);
                    diagnostic.postDelayed(this,100);
                    } catch(Exception ex) { diagnosticFailure.set(ex); }
                } });
                try(var input=new ParcelFileDescriptor.AutoCloseInputStream(i.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
                    .executeShellCommand("monkey -p com.autonavi.minimap -c android.intent.category.LAUNCHER 1"))) { input.readAllBytes(); }
                Bundle progress=new Bundle(); progress.putString("stream","\nMap "+(moving ? "route" : "point")+" session active for 45 s; inspect map adoption separately\n"); i.sendStatus(0,progress);
                Thread.sleep(45000);
                check(diagnosticFailure.get()==null,"Attribute comparison failed: "+diagnosticFailure.get());
                return;
            }
            stop(i,a,c);
            for(var v:Map.of("paceFast","3:00","paceSlow","9:00","paceTarget","6:00").entrySet()) set(i,a,v.getKey(),v.getValue());
            Playback.Settings selected=(Playback.Settings)call(a,"settings",new Class<?>[0]);
            check(selected.speed==0&&selected.variation==0,"Inactive basic fields were parsed in pace mode");
            double[] route={31.2304,121.4737,31.2304,121.4757};
            i.runOnMainSync(() -> call(activity,"start",new Class<?>[]{double[].class},(Object)route));
            await(() -> LocationService.running&&!LocationService.singlePoint&&(Boolean)call(activity,"getRunning",new Class<?>[0]),"Pace route did not start");
            i.runOnMainSync(() -> call(activity,"control",new Class<?>[]{String.class},"pause"));
            await(() -> LocationService.pausedState&&(Boolean)call(activity,"getPaused",new Class<?>[0]),"Route did not pause");
            set(i,a,"paceEnabled",false); set(i,a,"paceFast","invalid");
            set(i,a,"speed","12"); set(i,a,"variation","10"); set(i,a,"period","8"); set(i,a,"interval","0.2");
            i.runOnMainSync(() -> call(activity,"applyMotion",new Class<?>[0]));
            await(() -> LocationService.activeConfiguration.contains("0.2 s")&&LocationService.activeConfiguration.contains("基础速度模型"),"Paused settings were not applied");
            i.runOnMainSync(() -> call(activity,"control",new Class<?>[]{String.class},"pause"));
            await(() -> !LocationService.pausedState&&LocationService.currentSpeed>2.9,"Updated basic speed did not take effect");
            stop(i,a,c);
        } finally {
            i.runOnMainSync(() -> diagnostic.removeCallbacksAndMessages(null));
            try { if(a!=null) stop(i,a,c); }
            finally {
                locations.removeUpdates(gpsListener); locations.removeUpdates(networkListener); i.removeMonitor(monitor);
                restore(motion,oldMotion); restore(signals,oldSignals); restore(wireless,oldWireless); BackendController.refreshProfile();
            }
        }
        Bundle progress=new Bundle(); progress.putString("stream","\nUI flow PASS: automatic Root, point providers/interval, inactive fields and paused model switching\n"); i.sendStatus(0,progress);
    }
}
