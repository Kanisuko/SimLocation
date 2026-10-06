// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

public final class PlaybackTest {
    static void near(double a,double b) { if (Math.abs(a-b)>1e-7) throw new AssertionError(a+" != "+b); }
    public static void main(String[] args) {
        Route route=new Route(new double[]{0,0,0,0.001});
        Playback.Settings wave=new Playback.Settings(18,30,10,1,false,123);
        Playback one=new Playback(route,wave), split=new Playback(route,wave);
        one.advance(10);
        for (int i=0;i<100;i++) split.advance(0.1);
        near(one.travelled,50); near(split.travelled,one.travelled); near(split.speed(),one.speed());
        split.paused=true; double distance=split.travelled, elapsed=split.elapsed;
        split.advance(600); near(split.travelled,distance); near(split.elapsed,elapsed); near(split.speed(),0);
        split.paused=false; split.advance(1); if (split.travelled <= distance) throw new AssertionError("resume");
        Playback returning=new Playback(route,new Playback.Settings(3.6,0,10,1,true,42));
        returning.advance(route.total+2); near(returning.position(),route.total-2);
        if (!returning.backwards()) throw new AssertionError("reverse");
        near(route.bearing(returning.position(),true),270);
        returning.advance(route.total+3); near(returning.position(),5);
        if (returning.backwards() || returning.laps()!=1) throw new AssertionError("round trip");
        returning.paused=true;
        double before=returning.position();
        returning.configure(new Playback.Settings(7.2,0,10,0.5,false,42));
        near(returning.position(),before);
        returning.advance(600); near(returning.position(),before);
        returning.paused=false; returning.advance(1); near(returning.position(),before+2);
        returning.configure(new Playback.Settings(3.6,0,10,1,true,42));
        near(returning.position(),before+2);
        Playback jump=new Playback(route,new Playback.Settings(3.6,0,10,1,true,42));
        jump.advance(6*route.total+4); near(jump.position(),4);
        if (jump.laps()!=3) throw new AssertionError("long tick");
        Playback ended=new Playback(route,new Playback.Settings(36,0,10,0.2,false,42));
        ended.advance(1000); near(ended.position(),route.total); near(ended.speed(),0);
        if (!ended.completed()) throw new AssertionError("completion");
        try { new Playback.Settings(5,Double.NaN,10,1,false,0); throw new AssertionError("NaN"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("PASS: waveform distance, interval independence, pause/resume, reverse loop, laps and endpoint");
    }
}
