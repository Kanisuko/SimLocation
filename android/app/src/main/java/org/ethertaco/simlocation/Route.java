// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

/** WGS84 route interpolation, independent of Android and wall-clock time. */
final class Route {
    final double[] points, lengths;
    final double total;
    Route(double[] coordinates) {
        if (coordinates == null || coordinates.length < 2 || coordinates.length % 2 != 0 || coordinates.length > 20000)
            throw new IllegalArgumentException("路线必须包含 1–10000 个节点");
        points = coordinates.clone();
        lengths = new double[points.length / 2];
        for (int i = 0; i < lengths.length; i++) {
            double lat = points[2*i], lon = points[2*i+1];
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 85 || Math.abs(lon) > 180)
                throw new IllegalArgumentException("坐标超出范围");
            if (i > 0) lengths[i] = lengths[i-1] + distance(points[2*i-2], points[2*i-1], lat, lon);
        }
        total = lengths[lengths.length-1];
    }
    static double distance(double a, double b, double c, double d) {
        double x = Math.sin(Math.toRadians(c-a)/2), y = Math.sin(Math.toRadians(d-b)/2);
        double h = x*x + Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*y*y;
        return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
    double[] at(double metres) {
        if (metres <= 0) return new double[]{points[0], points[1]};
        if (metres >= total) return new double[]{points[points.length-2], points[points.length-1]};
        int i = 1;
        while (i < lengths.length-1 && lengths[i] < metres) i++;
        double f = (metres-lengths[i-1])/(lengths[i]-lengths[i-1]);
        double lonDelta = ((points[2*i+1]-points[2*i-1]+540)%360)-180;
        double lon = ((points[2*i-1]+f*lonDelta+540)%360)-180;
        return new double[]{points[2*i-2]+f*(points[2*i]-points[2*i-2]), lon};
    }
    float bearing(double metres, boolean backwards) {
        if (total == 0) return 0;
        double a=Math.max(0,Math.min(total-0.1,metres-0.05));
        double b=Math.min(total,a+0.1);
        double[] from=at(backwards ? b : a), to=at(backwards ? a : b);
        double lat1=Math.toRadians(from[0]), lat2=Math.toRadians(to[0]);
        double lon=Math.toRadians(to[1]-from[1]);
        double angle=Math.toDegrees(Math.atan2(Math.sin(lon)*Math.cos(lat2),
            Math.cos(lat1)*Math.sin(lat2)-Math.sin(lat1)*Math.cos(lat2)*Math.cos(lon)));
        return (float)((angle+360)%360);
    }
}
