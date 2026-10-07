// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;
import android.location.Location;

/** Only copies eligible locations; never mutates a provider's shared or cached object. */
final class LocationAttributeAdapter {
    static Location clear(Location original,long started,boolean gps,boolean network) {
        if(original==null || !original.isMock() || original.getElapsedRealtimeNanos()/1000000<started) return original;
        String provider=original.getProvider();
        if(!(gps && "gps".equals(provider) || network && "network".equals(provider) || (gps||network) && "fused".equals(provider))) return original;
        Location copy=new Location(original); copy.setMock(false); return copy;
    }
}
