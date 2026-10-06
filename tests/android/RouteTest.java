// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

/** Runs on a JDK without a device; physical service testing is separate. */
public final class RouteTest {
    public static void main(String[] args) {
        Route shortRoute = new Route(new double[]{0,0,0,0.001});
        if (shortRoute.total < 110 || shortRoute.total > 112) throw new AssertionError("WGS84 distance");
        double[] midpoint = shortRoute.at(shortRoute.total/2);
        if (Math.abs(midpoint[1]-0.0005) > 1e-9) throw new AssertionError("midpoint");
        if (shortRoute.at(shortRoute.total+10)[1] != 0.001) throw new AssertionError("end clamp");
        Route crossing = new Route(new double[]{0,179.9,0,-179.9});
        if (crossing.total < 22000 || crossing.total > 23000) throw new AssertionError("date line distance");
        if (Math.abs(Math.abs(crossing.at(crossing.total/2)[1])-180) > 1e-9) throw new AssertionError("date line interpolation");
        Route duplicate = new Route(new double[]{0,0,0,0,0,0.001});
        if (!Double.isFinite(duplicate.at(1)[1])) throw new AssertionError("duplicate nodes");
        try { new Route(new double[]{Double.NaN,0}); throw new AssertionError("NaN accepted"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("PASS: Android route distance, endpoints, date line, duplicate nodes and validation");
    }
}
