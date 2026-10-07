// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.*;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Location objects belong to the APK; su only handles explicit authorization. */
public final class LocationService extends Service {
    static volatile boolean running;
    static volatile String status = "已停止";
    static volatile boolean pausedState;
    static volatile int progress;
    static volatile double[] current;
    static volatile double[] activeRoute;
    static volatile boolean live;
    static volatile boolean singlePoint;
    static volatile double currentSpeed, currentAcceleration;
    static volatile String activeConfiguration="无活动配置";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService rootWorker = Executors.newSingleThreadExecutor();
    private final ArrayList<String> providers = new ArrayList<>();
    private LocationManager manager;
    private SharedPreferences recovery;
    private PowerManager.WakeLock wakeLock;
    private long renewWakeAt;
    private Playback playback;
    private long lastTick;
    private boolean destroyed;
    private int generation;
    private double previousSpeed;
    private boolean gpsEnabled=true, networkEnabled=true;
    private float gpsAccuracy=5, networkAccuracy=50;
    private double networkInterval=1;
    private long lastNetwork;
    private final Runnable tick = new Runnable() {
        public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                if (now >= renewWakeAt) keepAwake(now);
                double seconds=(now-lastTick)/1000.0;
                playback.advance(seconds);
                lastTick = now;
                double position=playback.position();
                double[] point = playback.route.at(position);
                boolean sent=false;
                for (String provider : providers) {
                    if("network".equals(provider) && lastNetwork>0 && now-lastNetwork<networkInterval*1000) continue;
                    Location location = new Location(provider);
                    location.setLatitude(point[0]); location.setLongitude(point[1]);
                    location.setAccuracy("gps".equals(provider) ? gpsAccuracy : networkAccuracy);
                    location.setTime(System.currentTimeMillis());
                    location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                    location.setSpeed((float)playback.speed());
                    if (playback.speed() > 0) location.setBearing(playback.route.bearing(position,playback.backwards()));
                    manager.setTestProviderLocation(provider,location);
                    sent=true;
                    if("network".equals(provider)) lastNetwork=now;
                }
                pausedState=playback.paused;
                if(sent) current=point;
                currentSpeed=playback.speed();
                currentAcceleration=seconds>0 ? (currentSpeed-previousSpeed)/seconds : 0;
                previousSpeed=currentSpeed;
                activeRoute=playback.route.points;
                progress=playback.route.total == 0 ? 0 : (int)(1000*position/playback.route.total);
                status = String.format(Locale.ROOT,"%s · %.6f, %.6f\n%.1f / %.1f m · %.2f km/h · %.1f s · %d 往返",
                    playback.paused ? "已暂停" : singlePoint ? "单点定位中" : playback.completed() ? live ? "等待追加路径" : "停留终点" : playback.backwards() ? "返程中" : "运行中",
                    point[0],point[1],position,playback.route.total,playback.speed()*3.6,playback.elapsed,playback.laps());
                handler.postDelayed(this,Math.round(playback.settings.interval*1000));
            } catch (Exception ex) { fail(ex); }
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        manager = (LocationManager)getSystemService(LOCATION_SERVICE);
        recovery = getSharedPreferences("provider-recovery",MODE_PRIVATE);
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SimLocation:playback");
        wakeLock.setReferenceCounted(false);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("playback","定位回放",NotificationManager.IMPORTANCE_LOW));
    }
    private void foreground() {
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,LocationService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this,"playback").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SimLocation").setContentText("定位模拟运行中 · 点击返回应用")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
        startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        foreground();
        if ("stop".equals(intent.getAction())) { status="已停止"; stopSelf(); return START_NOT_STICKY; }
        if ("pause".equals(intent.getAction())) {
            if (playback != null) {
                long now=SystemClock.elapsedRealtime();
                playback.advance((now-lastTick)/1000.0);
                playback.paused=!playback.paused; pausedState=playback.paused; lastTick=now; lastNetwork=0;
                handler.removeCallbacks(tick); handler.post(tick);
            }
            return START_NOT_STICKY;
        }
        if ("settings".equals(intent.getAction())) {
            if (playback != null && playback.paused) {
                try {
                    Playback.Settings next=singlePoint ? fixedSettings(intent) : settings(intent);
                    MotionProfile nextProfile=singlePoint ? MotionProfile.disabled() : profile(intent);
                    playback.configure(next); playback.configureProfile(nextProfile); describeConfiguration();
                    lastNetwork=0; handler.removeCallbacks(tick); handler.post(tick);
                }
                catch (Exception ex) { status="参数无效："+ex.getMessage(); }
            }
            return START_NOT_STICKY;
        }
        if("append".equals(intent.getAction())) {
            if(playback!=null && live) {
                try { long now=SystemClock.elapsedRealtime(); playback.advance((now-lastTick)/1000.0); playback.append(intent.getDoubleArrayExtra("points")); activeRoute=playback.route.points; lastTick=now; handler.removeCallbacks(tick); handler.post(tick); }
                catch(Exception ex) { status="追加路径失败："+ex.getMessage(); }
            }
            return START_NOT_STICKY;
        }
        if (!"start".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        final Route next;
        final Playback.Settings nextSettings;
        try {
            next = new Route(intent.getDoubleArrayExtra("points"));
            boolean fixed=next.points.length==2&&!intent.getBooleanExtra("live",false);
            nextSettings=fixed ? fixedSettings(intent) : settings(intent);
            if(!fixed) profile(intent);
            gpsEnabled=intent.getBooleanExtra("gps-enabled",true); networkEnabled=intent.getBooleanExtra("network-enabled",true);
            gpsAccuracy=(float)intent.getDoubleExtra("gps-accuracy",5); networkAccuracy=(float)intent.getDoubleExtra("network-accuracy",50);
            networkInterval=intent.getDoubleExtra("network-interval",1);
            if(!gpsEnabled&&!networkEnabled || !Float.isFinite(gpsAccuracy) || gpsAccuracy<=0 || gpsAccuracy>10000
                || !Float.isFinite(networkAccuracy) || networkAccuracy<=0 || networkAccuracy>10000 || !Double.isFinite(networkInterval)
                || networkInterval<0.2 || networkInterval>60) throw new IllegalArgumentException("定位源、精度或更新间隔无效");
            if (!manager.isLocationEnabled()) throw new IllegalStateException("请打开系统定位开关");
        } catch (Exception ex) { fail(ex); return START_NOT_STICKY; }
        handler.removeCallbacks(tick);
        final int request=++generation;
        status="正在验证 Root 并初始化定位源";
        rootWorker.submit(() -> {
            try {
                RootAccess.verify();
                if (!recovery.contains("original-mode")) {
                    String result=RootAccess.run("appops get org.ethertaco.simlocation android:mock_location");
                    Matcher match=Pattern.compile("(?:MOCK_LOCATION|mock_location):\\s*(allow|ignore|deny|default|foreground)").matcher(result);
                    String original;
                    if (match.find()) original=match.group(1);
                    else if (result.contains("No operations")) original="default";
                    else throw new IllegalStateException("无法读取原始模拟定位权限");
                    if (!recovery.edit().putString("original-mode",original).commit()) throw new IllegalStateException("无法保存恢复状态");
                }
                RootAccess.allowMock();
                BackendController.begin(this,gpsEnabled,networkEnabled);
                handler.post(() -> {
                    if (destroyed || request != generation) { BackendController.end(); return; }
                    try {
                        for (String provider : new String[]{"gps","network"}) {
                            if("gps".equals(provider)&&!gpsEnabled || "network".equals(provider)&&!networkEnabled) continue;
                            manager.addTestProvider(provider,"network".equals(provider),"gps".equals(provider),false,false,true,true,true,
                                ProviderProperties.POWER_USAGE_LOW,"gps".equals(provider) ? ProviderProperties.ACCURACY_FINE : ProviderProperties.ACCURACY_COARSE);
                            if (!providers.contains(provider)) providers.add(provider);
                            recovery.edit().putBoolean("owned-"+provider,true).commit();
                            manager.setTestProviderEnabled(provider,true);
                        }
                        playback=new Playback(next,nextSettings); pausedState=false; progress=0;
                        playback.streaming=intent.getBooleanExtra("live",false); live=playback.streaming; singlePoint=next.points.length==2&&!live;
                        playback.configureProfile(singlePoint ? MotionProfile.disabled() : profile(intent));
                        lastNetwork=0; previousSpeed=0; activeRoute=playback.route.points;
                        describeConfiguration();
                        lastTick=SystemClock.elapsedRealtime(); running=true;
                        keepAwake(lastTick);
                        handler.post(tick);
                    } catch (Exception ex) { fail(ex); }
                });
            } catch (Exception ex) { handler.post(() -> { if (!destroyed && request == generation) fail(ex); }); }
        });
        return START_NOT_STICKY;
    }
    private void fail(Exception ex) { status="运行错误："+ex.getMessage(); stopSelf(); }
    private void keepAwake(long now) {
        wakeLock.acquire(10*60*1000L);
        renewWakeAt=now+5*60*1000L;
    }
    private Playback.Settings settings(Intent intent) {
        return new Playback.Settings(intent.getDoubleExtra("speed",0),intent.getDoubleExtra("variation",0),
            intent.getDoubleExtra("period",10),intent.getDoubleExtra("interval",1),
            intent.getBooleanExtra("loop",false),intent.getLongExtra("seed",42));
    }
    private Playback.Settings fixedSettings(Intent intent) {
        return new Playback.Settings(0,0,10,intent.getDoubleExtra("interval",1),false,42);
    }
    private MotionProfile profile(Intent i) {
        return new MotionProfile(i.getBooleanExtra("pace-enabled",false),i.getDoubleExtra("pace-fast",180),i.getDoubleExtra("pace-slow",540),
            i.getDoubleExtra("pace-target",360),i.getDoubleExtra("pace-sigma",0.8),i.getDoubleExtra("pace-correlation",10),
            i.getDoubleExtra("pace-accel",0.5),i.getDoubleExtra("pace-decel",0.7),i.getDoubleExtra("pace-turn",0.5),
            i.getDoubleExtra("pace-lookahead",15),i.getDoubleExtra("pace-stop-every",0),i.getDoubleExtra("pace-stop-for",5));
    }
    private void describeConfiguration() {
        activeConfiguration=(singlePoint ? "单点定位 · " : "路线回放 · ")+(gpsEnabled ? "GPS "+gpsAccuracy+" m " : "")+(networkEnabled ? "network "+networkAccuracy+" m / "+networkInterval+" s " : "")+"发送间隔 "+playback.settings.interval+" s · "
            +(playback.profile.enabled ? "配速模型："+playback.profile.fastest+"–"+playback.profile.slowest+" s/km" : "基础速度模型")+BackendController.description();
    }
    @Override public void onDestroy() {
        BackendController.end();
        destroyed=true; generation++; running=false; live=false; singlePoint=false; pausedState=false; progress=0; current=null; currentSpeed=0; currentAcceleration=0; activeRoute=null; activeConfiguration="无活动配置"; handler.removeCallbacks(tick);
        if (wakeLock.isHeld()) wakeLock.release();
        boolean cleaned=true;
        for (String provider : new String[]{"gps","network"}) {
            if (providers.contains(provider) || recovery.getBoolean("owned-"+provider,false)) {
                try {
                    manager.removeTestProvider(provider);
                    recovery.edit().remove("owned-"+provider).commit();
                } catch (Exception ex) { cleaned=false; status="清理失败，请重新打开应用并停止："+ex.getMessage(); }
            }
        }
        final boolean restore=cleaned;
        rootWorker.submit(() -> {
            String original=recovery.getString("original-mode",null);
            if (restore && original != null) {
                try {
                    RootAccess.run("appops set org.ethertaco.simlocation android:mock_location "+original);
                    recovery.edit().remove("original-mode").commit();
                } catch (Exception ex) { status="权限恢复失败，请再次停止："+ex.getMessage(); }
            }
        });
        rootWorker.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
