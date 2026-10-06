// SPDX-License-Identifier: AGPL-3.0-only
package org.ethertaco.simlocation;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Root is mandatory. This does not change Location's mock flag. */
final class RootAccess {
    static String run(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Root 授权超时，请检查 Root 管理器");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) throw new IllegalStateException(output.isEmpty() ? "su 执行失败" : output);
        return output;
    }
    static void verify() throws Exception {
        if (!"0".equals(run("id -u"))) throw new IllegalStateException("必须获得 UID 0；仅支持 Root 模式");
    }
    static void allowMock() throws Exception {
        verify();
        run("appops set org.ethertaco.simlocation android:mock_location allow");
    }
}
