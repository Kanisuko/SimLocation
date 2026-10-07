// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;
import java.util.Set;
public final class SessionPolicyTest {
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(SessionPolicy.active(100,10000,3,101,10001,3));
        check(!SessionPolicy.active(100,10000,3,12100,22000,3)); // Exact expiry.
        check(!SessionPolicy.active(100,10000,3,99,10001,3)); // Monotonic rollback.
        check(!SessionPolicy.active(100,10000,3,101,9999,3)); // Wall clock rollback.
        check(!SessionPolicy.active(100,10000,3,101,10001,4)); // Reboot, even with matching clocks.
        check(!SessionPolicy.active(100,10000,-1,101,10001,-1));
        Set<String> allowed=Set.of("org.ethertaco.simlocation");
        check(SessionPolicy.allowed("org.ethertaco.simlocation",10123,0,allowed));
        check(!SessionPolicy.allowed("other.app",10123,0,allowed));
        check(!SessionPolicy.allowed("org.ethertaco.simlocation",1000,0,allowed));
        check(!SessionPolicy.allowed("org.ethertaco.simlocation",110123,0,allowed));
        check(!SessionPolicy.allowed(null,10123,0,allowed));
        System.out.println("SessionPolicyTest PASS");
    }
}
