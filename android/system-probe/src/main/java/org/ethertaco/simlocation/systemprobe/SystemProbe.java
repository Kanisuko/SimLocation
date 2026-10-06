// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation.systemprobe;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Optional compatibility probe. Preserves every return value, argument and exception. */
public final class SystemProbe extends XposedModule {
    private static final String TAG="SimLocationSystem";
    private static final String CLIENT="org.ethertaco.simlocation";
    private final Set<Method> installed=new HashSet<>();
    private final Set<Method> observed=new HashSet<>();
    private boolean wifiReady;
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO,TAG,"modern API="+getApiVersion()+" process="+param.getProcessName()+"; observation probe only",null);
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if(getApiVersion()<101) return;
        // WiFi is loaded from an APEX jar after this callback, with its own class loader.
        try {
            Class<?> manager=Class.forName("com.android.server.SystemServiceManager",false,param.getClassLoader());
            for(Method method:manager.getDeclaredMethods()) {
                if(!method.getName().equals("startServiceFromJar") || method.getParameterCount()!=2
                    || method.getParameterTypes()[0]!=String.class || method.getParameterTypes()[1]!=String.class) continue;
                hook(method).intercept(chain -> {
                    Object service=chain.proceed();
                    if("com.android.server.wifi.WifiService".equals(chain.getArg(0)) && service!=null) {
                        try { installWifi(service.getClass().getClassLoader()); }
                        catch(Throwable ex) { log(Log.WARN,TAG,"WiFi probe unavailable",ex); }
                    }
                    return service;
                });
                log(Log.INFO,TAG,"WiFi APEX loader probe installed",null);
            }
        } catch(Throwable ex) { log(Log.WARN,TAG,"System loader probe unavailable",ex); }
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if(!"com.android.phone".equals(param.getPackageName())) return;
        try {
            Class<?> phone=Class.forName("com.android.phone.PhoneInterfaceManager",false,param.getClassLoader());
            install(phone,"getAllCellInfo",0,2);
            install(phone,"requestCellInfoUpdate",2,4);
        } catch(Throwable ex) { log(Log.WARN,TAG,"Phone probe unavailable",ex); }
    }
    private synchronized void installWifi(ClassLoader loader) throws ClassNotFoundException {
        if(wifiReady) return;
        Class<?> wifi=Class.forName("com.android.server.wifi.WifiServiceImpl",false,loader);
        install(wifi,"getScanResults",0,2);
        install(wifi,"getConnectionInfo",0,2);
        wifiReady=true;
    }
    private void install(Class<?> owner,String name,int packageArgument,int count) {
        boolean found=false;
        for(Method method:owner.getDeclaredMethods()) {
            if(!method.getName().equals(name) || method.getParameterCount()!=count
                || method.getParameterTypes()[packageArgument]!=String.class) continue;
            found=true;
            synchronized(installed) { if(!installed.add(method)) continue; }
            try {
                hook(method).intercept(chain -> {
                    Object result=chain.proceed(); // Never bypass permission checks or change data.
                    if(CLIENT.equals(chain.getArg(packageArgument))) {
                        synchronized(observed) {
                            if(observed.add(method)) log(Log.INFO,TAG,"SimLocation reached "+owner.getName()+"#"+name
                                +"; result="+(result==null ? "null/void" : result.getClass().getName()),null);
                        }
                    }
                    return result;
                });
                log(Log.INFO,TAG,"probe installed: "+method.toGenericString(),null);
            } catch(Throwable ex) { log(Log.WARN,TAG,"Cannot install "+name,ex); }
        }
        if(!found) log(Log.WARN,TAG,"unsupported ROM signature: "+owner.getName()+"#"+name,null);
    }
}
