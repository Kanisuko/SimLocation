// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

/** Both clocks and the Android boot counter must agree; a stale lease never survives reboot. */
public final class SessionPolicy {
    public static final long LEASE_MS=12000;
    public static boolean active(long issued,long wallIssued,int boot,long now,long wallNow,int currentBoot) {
        return boot>=0 && boot==currentBoot && issued>=0 && wallIssued>0 && now>=issued && now-issued<LEASE_MS
            && wallNow>=wallIssued && wallNow-wallIssued<LEASE_MS;
    }
    public static boolean allowed(String target,int uid,int userId,java.util.Set<String> targets) {
        return target!=null && uid>=10000 && uid/100000==userId && targets.contains(target);
    }
}
