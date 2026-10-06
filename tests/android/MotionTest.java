// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

public final class MotionTest {
    static void near(double a,double b,double tolerance) { if(Math.abs(a-b)>tolerance) throw new AssertionError(a+" != "+b); }
    static Playback create(Route route) {
        Playback p=new Playback(route,new Playback.Settings(10,0,10,1,false,42));
        p.configureProfile(new MotionProfile(true,180,540,360,1,8,0.5,0.7,0.5,15,120,5));
        return p;
    }
    public static void main(String[] args) {
        near(MotionProfile.pace("3:00"),180,0); near(MotionProfile.pace("9:00"),540,0);
        try { MotionProfile.pace("3:60"); throw new AssertionError("invalid pace"); } catch(IllegalArgumentException expected) {}
        Route r=new Route(new double[]{0,0,0,0.03});
        Playback one=create(r), split=create(r);
        one.advance(100); for(int i=0;i<1000;i++) split.advance(0.1);
        near(one.travelled,split.travelled,1e-6); near(one.speed(),split.speed(),1e-6);
        Playback step=create(r); double speed=0, min=100, max=0;
        for(int i=0;i<3000;i++) {
            step.advance(0.05); double next=step.speed();
            if(next-speed>0.025001 || speed-next>0.035001 || next>1000.0/180+1e-9) throw new AssertionError("speed/acceleration bound");
            if(i>300 && i<2000) { min=Math.min(min,next); max=Math.max(max,next); }
            speed=next;
        }
        if(max-min<0.1) throw new AssertionError("flat speed");
        double elapsed=step.elapsed, distance=step.travelled;
        step.paused=true; step.advance(600); near(step.elapsed,elapsed,0); near(step.travelled,distance,0);
        Playback stream=create(new Route(new double[]{0,0})); stream.streaming=true;
        stream.advance(50); near(stream.travelled,0,0); stream.append(new double[]{0,0,0,0.001}); stream.advance(2);
        if(stream.travelled<=0) throw new AssertionError("stream append");
        Playback end=create(new Route(new double[]{0,0,0,0.001})); end.advance(500);
        if(!end.completed()) throw new AssertionError("endpoint"); near(end.speed(),0,0);
        double[] rounded=Trajectory.rounded(new double[]{0,0,0,0.001,0.001,0.001},6);
        if(rounded.length<=6) throw new AssertionError("curve sampling");
        near(rounded[0],0,0); near(rounded[rounded.length-2],0.001,0);
        new Route(rounded);
        if(Trajectory.rounded(new double[]{0,0,0,0.001,0,0.002},6).length!=6) throw new AssertionError("straight stroke expanded");
        System.out.println("PASS: pace bounds, reproducibility, smooth acceleration, pauses, stream append and rounded corners");
    }
}
