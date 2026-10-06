// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import java.util.ArrayList;

/** Round corners inside a bounded trim distance; never changes path endpoints. */
final class Trajectory {
    static double[] rounded(double[] input,double radius) {
        Route route=new Route(input);
        if(!Double.isFinite(radius) || radius<0 || radius>30) throw new IllegalArgumentException("转弯半径范围为 0–30 m");
        if(radius==0 || input.length<6) return input.clone();
        ArrayList<Double> output=new ArrayList<>(); add(output,input[0],input[1]);
        for(int i=2;i<input.length-2;i+=2) {
            Route before=new Route(new double[]{input[i-2],input[i-1],input[i],input[i+1]});
            Route after=new Route(new double[]{input[i],input[i+1],input[i+2],input[i+3]});
            double angle=Math.abs(before.bearing(0,false)-after.bearing(0,false));
            angle=Math.min(angle,360-angle);
            double trim=Math.min(radius,Math.min(before.total,after.total)/4);
            if(trim<0.1 || angle<5) { add(output,input[i],input[i+1]); continue; }
            double[] a=before.at(before.total-trim), b=after.at(trim);
            add(output,a[0],a[1]);
            int samples=Math.max(3,Math.min(8,(int)Math.ceil(trim)));
            for(int k=1;k<=samples;k++) {
                double t=(double)k/samples, u=1-t;
                double lonA=input[i+1]+(((a[1]-input[i+1]+540)%360)-180);
                double lonB=input[i+1]+(((b[1]-input[i+1]+540)%360)-180);
                add(output,u*u*a[0]+2*u*t*input[i]+t*t*b[0],wrap(u*u*lonA+2*u*t*input[i+1]+t*t*lonB));
            }
        }
        add(output,input[input.length-2],input[input.length-1]);
        if(output.size()>20000) throw new IllegalArgumentException("平滑后的路径超过 10000 节点");
        double[] result=new double[output.size()]; for(int i=0;i<result.length;i++) result[i]=output.get(i);
        return result;
    }
    private static double wrap(double lon) { return ((lon+540)%360)-180; }
    private static void add(ArrayList<Double> out,double lat,double lon) { out.add(lat); out.add(lon); }
}
