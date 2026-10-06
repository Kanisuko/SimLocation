// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

/** Monotonic route clock. Pauses exclude time; speed variation has an exact integral. */
final class Playback {
    static final class Settings {
        final double speed, variation, period, interval;
        final boolean loop;
        final long seed;
        Settings(double kmh, double percent, double period, double interval, boolean loop, long seed) {
            if (!Double.isFinite(kmh) || kmh < 0 || kmh > 300
                || !Double.isFinite(percent) || percent < 0 || percent > 80
                || !Double.isFinite(period) || period < 2 || period > 120
                || !Double.isFinite(interval) || interval < 0.2 || interval > 5)
                throw new IllegalArgumentException("速度 0–300 km/h；波动 0–80%；周期 2–120 s；更新间隔 0.2–5 s");
            speed=kmh/3.6; variation=percent/100; this.period=period; this.interval=interval;
            this.loop=loop; this.seed=seed;
        }
    }
    Route route;
    Settings settings;
    double travelled, elapsed;
    boolean paused;
    boolean streaming;
    MotionProfile profile=MotionProfile.disabled();
    private double modelSpeed, remainder;
    Playback(Route route, Settings settings) { this.route=route; this.settings=settings; }
    void configure(Settings next) {
        // Changing loop mode starts forward from the current position, without a jump.
        if (settings.loop != next.loop) travelled=position();
        settings=next;
    }
    void advance(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("时间增量无效");
        if (paused) return;
        if (!settings.loop && travelled>=route.total) { modelSpeed=0; return; }
        if(profile.enabled) {
            remainder+=seconds;
            // Fixed steps make the motion independent of provider update frequency.
            while(remainder+1e-10>=0.05) { modelStep(0.05); remainder=Math.max(0,remainder-0.05); }
            return;
        }
        double omega=2*Math.PI/settings.period;
        double phase=new java.util.Random(settings.seed).nextDouble()*2*Math.PI;
        double end=elapsed+seconds;
        double delta=settings.speed*(seconds+settings.variation*(Math.cos(omega*elapsed+phase)-Math.cos(omega*end+phase))/omega);
        travelled += Math.max(0,delta);
        if (!settings.loop || route.total == 0) travelled=Math.min(route.total,travelled);
        elapsed=end;
    }
    double position() {
        if (!settings.loop || route.total == 0) return Math.min(route.total,travelled);
        double cycle=travelled%(2*route.total);
        return cycle <= route.total ? cycle : 2*route.total-cycle;
    }
    boolean backwards() { return settings.loop && route.total > 0 && travelled%(2*route.total) >= route.total; }
    boolean completed() { return !settings.loop && travelled >= route.total; }
    long laps() { return settings.loop && route.total > 0 ? (long)(travelled/(2*route.total)) : 0; }
    double speed() {
        if (paused || completed() || route.total == 0) return 0;
        if(profile.enabled) return modelSpeed;
        double phase=new java.util.Random(settings.seed).nextDouble()*2*Math.PI;
        return settings.speed*(1+settings.variation*Math.sin(2*Math.PI*elapsed/settings.period+phase));
    }
    void configureProfile(MotionProfile next) {
        if(next.enabled!=profile.enabled) { modelSpeed=elapsed==0 ? 0 : speed(); remainder=0; }
        profile=next;
    }
    void append(double[] extra) {
        if(!streaming || settings.loop) throw new IllegalStateException("只有实时模式可以追加路径");
        new Route(extra);
        boolean same=route.points[route.points.length-2]==extra[0] && route.points[route.points.length-1]==extra[1];
        int skip=same ? 2 : 0;
        double[] joined=java.util.Arrays.copyOf(route.points,route.points.length+extra.length-skip);
        System.arraycopy(extra,skip,joined,route.points.length,extra.length-skip);
        route=new Route(joined);
    }
    private void modelStep(double dt) {
        if(!settings.loop && completed()) { modelSpeed=0; return; }
        double remaining=settings.loop ? (backwards() ? position() : route.total-position()) : route.total-position();
        double desired=profile.stopping(elapsed) ? 0 : profile.cruise(elapsed,settings.seed);
        double ahead=Math.max(0,Math.min(route.total,position()+(backwards() ? -1 : 1)*profile.lookAhead));
        double angle=Math.abs(route.bearing(position(),backwards())-route.bearing(ahead,backwards()));
        angle=Math.min(angle,360-angle);
        desired*=1-(1-profile.turnFactor)*Math.min(1,angle/90);
        double previous=modelSpeed;
        double a=profile.deceleration;
        // Account for the next integration step before applying the stopping bound.
        double stoppingBound=(Math.sqrt(Math.max(0,a*a*dt*dt-4*a*dt*previous+8*a*remaining))-a*dt)/2;
        desired=Math.min(desired,Math.max(0,stoppingBound));
        modelSpeed+=Math.max(-profile.deceleration*dt,Math.min(profile.acceleration*dt,desired-modelSpeed));
        modelSpeed=Math.max(0,Math.min(1000/profile.fastest,modelSpeed));
        double distance=(previous+modelSpeed)*dt/2;
        if((remaining<=0.001 && previous<=profile.deceleration*dt && !profile.stopping(elapsed)) || distance>=remaining) {
            travelled+=remaining; modelSpeed=0;
        } else travelled+=distance;
        if(!settings.loop) travelled=Math.min(route.total,travelled);
        elapsed+=dt;
    }
}
