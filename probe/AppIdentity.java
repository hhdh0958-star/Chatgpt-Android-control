package dev.nativeagent.probe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The tool dispatcher only runs inside an unprivileged Android application. */
final class AppIdentity {
    static void requireUnprivileged() throws IOException {
        int appId = android.os.Process.myUid() % 100000;
        if (appId < 10000 || appId > 19999) throw new IOException("Unexpected application identity");
        boolean effective = false, permitted = false;
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("CapEff:")) effective = line.substring(7).trim().matches("0+");
            if (line.startsWith("CapPrm:")) permitted = line.substring(7).trim().matches("0+");
        }
        if (!effective || !permitted) throw new IOException("Privileged process rejected");
    }
}
