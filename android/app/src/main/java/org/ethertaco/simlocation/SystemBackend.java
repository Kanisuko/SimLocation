// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.wifi.WifiInfo;
import android.os.*;
import android.provider.Settings;
import android.telephony.*;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Modern API 101+; only system framework and phone service, no application-process hooks. */
public final class SystemBackend extends XposedModule {
    private static final String TAG="SimLocationBackend";
    private SharedPreferences preferences;
    private String cached="";
    private Snapshot parsed;
    private volatile Context context;
    private boolean phoneProcess;
    private final Set<Method> hooked=new HashSet<>();
    private final Map<String,String> capabilities=new ConcurrentHashMap<>();
    private final Set<String> reported=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService reporter=Executors.newSingleThreadScheduledExecutor();
    private final class Snapshot {
        final WirelessScenario scene; final int user,boot; final long issued,wall,started; final String token; final boolean gps,network;
        Snapshot(String json) throws JSONException {
            JSONObject s=new JSONObject(json); scene=new WirelessScenario(s.getJSONObject("scene").toString());
            user=s.getInt("user"); boot=s.getInt("boot"); issued=s.getLong("issued"); wall=s.getLong("wall"); started=s.getLong("started"); token=s.getString("token");
            if(started<0 || started>issued || user<0 || token.length()!=36) throw new JSONException("Invalid session");
            gps=s.getBoolean("gps"); network=s.getBoolean("network");
        }
        boolean allows(String pkg,int uid) { return SessionPolicy.allowed(pkg,uid,user,scene.targets); }
    }
    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        phoneProcess="com.android.phone".equals(p.getProcessName());
        log(Log.INFO,TAG,"API="+getApiVersion()+" process="+p.getProcessName()+"; bounded simulation sessions",null);
        try { preferences=getRemotePreferences("simulation"); }
        catch(Throwable ex) { log(Log.ERROR,TAG,"Remote preferences unavailable; all data stays original",ex); }
        reporter.scheduleAtFixedRate(this::refreshReports,5,15,TimeUnit.SECONDS);
    }
    private Context context() throws ReflectiveOperationException {
        if(context!=null) return context;
        Class<?> at=Class.forName("android.app.ActivityThread");
        Object application=at.getMethod("currentApplication").invoke(null);
        if(application instanceof Context c) return context=c;
        // The phone UID cannot use the system context's android attribution. Wait for PhoneApp.
        if(phoneProcess) return null;
        Object thread=at.getMethod("currentActivityThread").invoke(null);
        if(thread!=null) return context=(Context)at.getMethod("getSystemContext").invoke(thread);
        return null;
    }
    private synchronized Snapshot session() {
        try {
            if(preferences==null) return null;
            String raw=preferences.getString("session","");
            if(!raw.equals(cached)) { cached=raw; parsed=null; if(!raw.isEmpty()) parsed=new Snapshot(raw); }
            Snapshot s=parsed; if(s==null) return null;
            Context c=context(); if(c==null) return null;
            int boot=Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
            return SessionPolicy.active(s.issued,s.wall,s.boot,SystemClock.elapsedRealtime(),System.currentTimeMillis(),boot) ? s : null;
        } catch(Throwable ex) { return null; }
    }
    private boolean subscriptionTarget(String pkg,int uid) {
        try {
            WirelessScenario scene=new WirelessScenario(preferences==null ? WirelessScenario.example() : preferences.getString("profile",WirelessScenario.example()));
            return SessionPolicy.allowed(pkg,uid,getModuleApplicationInfo().uid/100000,scene.targets);
        } catch(Throwable ex) { return false; }
    }
    private void report(String kind,String state,String token,String detail) {
        String key=kind+":"+state+":"+token;
        if(!reported.add(key)) return;
        // Bounded by process lifetime, not route duration or number of callbacks.
        if(reported.size()>128) { reported.clear(); reported.add(key); }
        reporter.execute(() -> {
            try {
                Context c=context(); if(c==null) { reported.remove(key); return; }
                Bundle data=new Bundle(); data.putString("state",state); data.putString("token",token);
                data.putString("detail",detail); data.putInt("api",getApiVersion()); data.putInt("version",BuildConfig.VERSION_CODE);
                c.getContentResolver().call(BackendHealthProvider.URI,"report",kind,data);
            } catch(Throwable ex) { reported.remove(key); log(Log.WARN,TAG,"Health report unavailable: "+kind,ex); }
        });
    }
    private void installed(String name) { capabilities.put(name,"installed"); report(name,"installed","",""); log(Log.INFO,TAG,"installed "+name,null); }
    private void failed(String name,Throwable ex) { capabilities.put(name,"failed"); report(name,"failed","",ex.getClass().getSimpleName()); log(Log.WARN,TAG,"unsupported "+name,ex); }
    private void refreshReports() { capabilities.forEach((key,state)->report(key,state,"","")); }
    private Method method(Class<?> owner,String name,Class<?>... params) throws NoSuchMethodException {
        Method m=owner.getDeclaredMethod(name,params); m.setAccessible(true); return m;
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam p) {
        ClassLoader loader=p.getClassLoader();
        try {
            Class<?> manager=Class.forName("com.android.server.SystemServiceManager",false,loader);
            hook(method(manager,"startServiceFromJar",String.class,String.class)).intercept(chain -> {
                Object result=chain.proceed();
                if("com.android.server.wifi.WifiService".equals(chain.getArg(0)) && result!=null) {
                    installWifi(result.getClass().getClassLoader()); refreshReports();
                }
                return result;
            });
        } catch(Throwable ex) { failed("wifi-scan",ex); failed("wifi-connection",ex); }
        installLocations(loader);
        try {
            Class<?> registry=Class.forName("com.android.server.TelephonyRegistry",false,loader);
            Class<?> listener=Class.forName("com.android.internal.telephony.IPhoneStateListener",false,loader);
            Method registration=method(registry,"listenWithEventList",boolean.class,boolean.class,int.class,String.class,String.class,listener,int[].class,boolean.class);
            hook(registration).intercept(chain -> {
                String pkg=(String)chain.getArg(3); int uid=Binder.getCallingUid();
                if(!subscriptionTarget(pkg,uid)) return chain.proceed();
                Object[] args=chain.getArgs().toArray();
                args[5]=cellCallback(listener,args[5],pkg,uid,"onCellInfoChanged","cell-listener",null);
                return chain.proceed(args);
            }); installed("cell-listener");
        } catch(Throwable ex) { failed("cell-listener",ex); }
    }
    private synchronized void installWifi(ClassLoader loader) {
        for(String name:new String[]{"getScanResults","getConnectionInfo"}) {
            String kind=name.equals("getScanResults") ? "wifi-scan" : "wifi-connection";
            try {
                Class<?> wifi=Class.forName("com.android.server.wifi.WifiServiceImpl",false,loader);
                Method m=method(wifi,name,String.class,String.class); if(!hooked.add(m)) continue;
                hook(m).intercept(chain -> {
                    int uid=Binder.getCallingUid(); String pkg=(String)chain.getArg(0);
                    Object original=chain.proceed(); // Permission checks and original failures remain authoritative.
                    refreshReports(); Snapshot s=session();
                    if(s==null || !s.allows(pkg,uid)) return original;
                    try {
                        if(name.equals("getScanResults") && s.scene.scans && original!=null) {
                            List<?> real=(List<?>)ObservationFactory.call(original,"getList",new Class<?>[0]);
                            if(real.isEmpty()) return original; // Do not manufacture access after redaction/disabled scanning.
                            Object replacement=ObservationFactory.construct(original.getClass(),new Class<?>[]{List.class},ObservationFactory.scans(s.scene));
                            report(kind,"substituted",s.token,""); return replacement;
                        }
                        if(name.equals("getConnectionInfo") && s.scene.connection && original instanceof WifiInfo info) {
                            String bssid=info.getBSSID();
                            if(bssid==null || "02:00:00:00:00:00".equals(bssid) || info.getNetworkId()<0) return original;
                            Object replacement=ObservationFactory.connection(s.scene,info); report(kind,"substituted",s.token,""); return replacement;
                        }
                    } catch(Throwable ex) { report(kind,"failed",s.token,ex.getClass().getSimpleName()); }
                    return original;
                }); installed(kind);
            } catch(Throwable ex) { failed(kind,ex); }
        }
    }
    @Override public void onPackageReady(PackageReadyParam p) {
        if(!"com.android.phone".equals(p.getPackageName())) return;
        try {
            Class<?> phone=Class.forName("com.android.phone.PhoneInterfaceManager",false,p.getClassLoader());
            hook(method(phone,"getAllCellInfo",String.class,String.class)).intercept(chain -> {
                int uid=Binder.getCallingUid(); String pkg=(String)chain.getArg(0);
                Object original=chain.proceed(); refreshReports();
                return replaceCells(original,pkg,uid,"cell-cache",null);
            }); installed("cell-cache");
        } catch(Throwable ex) { failed("cell-cache",ex); }
        try {
            Class<?> phone=Class.forName("com.android.phone.PhoneInterfaceManager",false,p.getClassLoader());
            Class<?> callback=Class.forName("android.telephony.ICellInfoCallback",false,p.getClassLoader());
            hook(method(phone,"requestCellInfoUpdate",int.class,callback,String.class,String.class)).intercept(chain -> {
                int uid=Binder.getCallingUid(); String pkg=(String)chain.getArg(2);
                Snapshot s=session();
                if(s==null || !s.allows(pkg,uid) || !s.scene.cell) return chain.proceed();
                Object[] args=chain.getArgs().toArray();
                args[1]=cellCallback(callback,args[1],pkg,uid,"onCellInfo","cell-async",s.token);
                return chain.proceed(args);
            }); installed("cell-async");
        } catch(Throwable ex) { failed("cell-async",ex); }
    }
    private Object cellCallback(Class<?> contract,Object original,String pkg,int uid,String callback,String kind,String token) {
        if(original==null) return null;
        return Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,m,args) -> {
            if(m.getName().equals(callback) && args!=null && args.length==1) {
                args=args.clone(); args[0]=replaceCells(args[0],pkg,uid,kind,token);
            }
            try { return m.invoke(original,args); } catch(InvocationTargetException ex) { throw ex.getCause(); }
        });
    }
    private Object replaceCells(Object original,String pkg,int uid,String kind,String token) {
        Snapshot s=session();
        if(s==null || !s.scene.cell || !s.allows(pkg,uid) || token!=null && !token.equals(s.token)) return original;
        if(!(original instanceof List<?> values) || !hasCellIdentity(values)) return original;
        try { Object result=ObservationFactory.cells(s.scene); report(kind,"substituted",s.token,""); return result; }
        catch(Throwable ex) { report(kind,"failed",s.token,ex.getClass().getSimpleName()); return original; }
    }
    private boolean hasCellIdentity(List<?> cells) {
        for(Object value:cells) if(value instanceof CellInfo info) {
            Object id=info.getCellIdentity();
            try {
                String getter=id instanceof CellIdentityNr ? "getNci" : id instanceof CellIdentityLte ? "getCi" : "getCid";
                long n=((Number)id.getClass().getMethod(getter).invoke(id)).longValue();
                if(n>=0 && n!=CellInfo.UNAVAILABLE && n!=CellInfo.UNAVAILABLE_LONG) return true;
            } catch(ReflectiveOperationException ignored) { }
        }
        return false;
    }
    private Location clear(Location original,Snapshot s) {
        return LocationAttributeAdapter.clear(original,s.started,s.gps,s.network);
    }
    private void installLocations(ClassLoader loader) {
        for(String registration:new String[]{"LocationRegistration","GetCurrentLocationListenerRegistration"}) {
            String kind=registration.equals("LocationRegistration") ? "location-stream" : "location-current";
            try {
                Class<?> c=Class.forName("com.android.server.location.provider.LocationProviderManager$"+registration,false,loader);
                Class<?> result=Class.forName("android.location.LocationResult",false,loader);
                Method m=method(c,"acceptLocationChange",result);
                hook(m).intercept(chain -> {
                    Snapshot s=session(); Object value=chain.getArg(0);
                    if(s==null || !s.scene.clearMock || value==null) return chain.proceed();
                    Object[] replacementArgs=null;
                    try {
                        Object identity=ObservationFactory.call(chain.getThisObject(),"getIdentity",new Class<?>[0]);
                        String pkg=(String)ObservationFactory.call(identity,"getPackageName",new Class<?>[0]);
                        int uid=(Integer)ObservationFactory.call(identity,"getUid",new Class<?>[0]);
                        if(s.allows(pkg,uid)) {
                            List<?> locations=(List<?>)ObservationFactory.call(value,"asList",new Class<?>[0]);
                            List<Location> copies=new ArrayList<>(); boolean changed=false;
                            for(Object location:locations) { Location copy=clear((Location)location,s); copies.add(copy); changed|=copy!=location; }
                            if(changed) {
                                Object replaced=result.getMethod("wrap",List.class).invoke(null,copies);
                                replacementArgs=chain.getArgs().toArray(); replacementArgs[0]=replaced;
                            }
                        }
                    } catch(ReflectiveOperationException ex) { report(kind,"failed",s.token,ex.getClass().getSimpleName()); }
                    // Never run the original twice, including when it throws. All permission filters still run.
                    if(replacementArgs==null) return chain.proceed();
                    Object output=chain.proceed(replacementArgs); report(kind,"substituted",s.token,""); return output;
                }); installed(kind);
            } catch(Throwable ex) { failed(kind,ex); }
        }
        try {
            Class<?> service=Class.forName("com.android.server.location.LocationManagerService",false,loader);
            Class<?> request=Class.forName("android.location.LastLocationRequest",false,loader);
            hook(method(service,"getLastLocation",String.class,request,String.class,String.class)).intercept(chain -> {
                int uid=Binder.getCallingUid(); String pkg=(String)chain.getArg(2);
                Object original=chain.proceed(); Snapshot s=session();
                if(s==null || !s.scene.clearMock || !s.allows(pkg,uid) || !(original instanceof Location l)) return original;
                Location replacement=clear(l,s); if(replacement!=l) report("location-last","substituted",s.token,""); return replacement;
            }); installed("location-last");
        } catch(Throwable ex) { failed("location-last",ex); }
    }
}
