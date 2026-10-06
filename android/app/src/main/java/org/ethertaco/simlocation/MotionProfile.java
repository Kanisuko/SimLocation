// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

/** Reproducible, bounded cruising targets with acceleration-limited transitions. */
final class MotionProfile {
    final boolean enabled;
    final double fastest, slowest, target, sigma, correlation, acceleration, deceleration, turnFactor, lookAhead, stopEvery, stopFor;
    MotionProfile(boolean enabled,double fastest,double slowest,double target,double sigmaKmh,
                  double correlation,double acceleration,double deceleration,double turnFactor,double lookAhead,double stopEvery,double stopFor) {
        double[] values={fastest,slowest,target,sigmaKmh,correlation,acceleration,deceleration,turnFactor,lookAhead,stopEvery,stopFor};
        for(double value:values) if(!Double.isFinite(value)) throw new IllegalArgumentException("运动参数必须是有限数字");
        if(fastest<150 || slowest>1200 || slowest<fastest || target<fastest || target>slowest
            || sigmaKmh<0 || sigmaKmh>6 || correlation<2 || correlation>120 || acceleration<0.05 || acceleration>3
            || deceleration<0.05 || deceleration>3 || turnFactor<0.2 || turnFactor>1 || lookAhead<1 || lookAhead>100
            || stopEvery<0 || (stopEvery>0 && stopEvery<20) || stopEvery>3600 || stopFor<0 || stopFor>120
            || (stopEvery>0 && stopFor>=stopEvery))
            throw new IllegalArgumentException("请检查配速范围、波动、加减速和停留参数");
        this.enabled=enabled; this.fastest=fastest; this.slowest=slowest; this.target=target; sigma=sigmaKmh/3.6;
        this.correlation=correlation; this.acceleration=acceleration; this.deceleration=deceleration;
        this.turnFactor=turnFactor; this.lookAhead=lookAhead; this.stopEvery=stopEvery; this.stopFor=stopFor;
    }
    static MotionProfile disabled() { return new MotionProfile(false,180,540,360,0.8,10,0.5,0.7,0.5,15,0,5); }
    double cruise(double time,long seed) {
        long index=(long)Math.floor(time/correlation);
        double u=(time-index*correlation)/correlation;
        u=u*u*(3-2*u);
        double a=new java.util.Random(seed^(index*0x9E3779B97F4A7C15L)).nextGaussian();
        double b=new java.util.Random(seed^((index+1)*0x9E3779B97F4A7C15L)).nextGaussian();
        return Math.max(1000/slowest,Math.min(1000/fastest,1000/target+sigma*(a+(b-a)*u)));
    }
    boolean stopping(double time) { return stopEvery>0 && time>=stopEvery && time%stopEvery<stopFor; }
    static double pace(String text) {
        String[] parts=text.trim().split(":",-1);
        if(parts.length!=2) throw new IllegalArgumentException("配速格式为 分:秒，例如 6:00");
        int minutes=Integer.parseInt(parts[0]), seconds=Integer.parseInt(parts[1]);
        if(minutes<0 || seconds<0 || seconds>=60) throw new IllegalArgumentException("配速秒数范围为 00–59");
        return minutes*60.0+seconds;
    }
}
