package dev.nativeagent.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import dev.nativeagent.runner.PythonRunner;
import java.util.Map;

/** Runs synthetic inputs through the installed target's bound service and real WebView. */
public final class RunnerCheck extends Instrumentation {
    private String mode;
    @Override public void onCreate(Bundle args) { super.onCreate(args); mode = args.getString("mode", "python"); start(); }

    @Override public void onStart() {
        var report = new Bundle();
        var observations = new StringBuilder();
        try {
            if ("scripts".equals(mode)) {
                var ctx = getTargetContext();
                var shell = PythonRunner.run(ctx, Map.of("main.sh", "grep red input.txt | wc -l; echo temporary > scratch.txt",
                    "input.txt", "red\nblue\nred\n"), "main.sh");
                observations.append(shell).append('\n');
                if (shell.getInt("exitCode") != 0 || !shell.getString("stdout").strip().equals("2")) throw new AssertionError("Shell pipeline");
                var denied = PythonRunner.run(ctx, Map.of("main.sh", "su"), "main.sh");
                observations.append(denied).append('\n');
                if (denied.getInt("exitCode") != 127) throw new AssertionError("Native command dispatch");
                var js = PythonRunner.run(ctx, Map.of("main.mjs",
                    "import {sum} from './calc.mjs'; console.log(sum(2, 5));",
                    "calc.mjs", "export const sum = (a,b) => a+b;"), "main.mjs");
                observations.append(js).append('\n');
                if (js.getInt("exitCode") != 0 || !js.getString("stdout").equals("7\n")) throw new AssertionError("JavaScript modules");
                report.putString("stream", observations + "PASS Shell and JavaScript on Android");
                finish(Activity.RESULT_OK, report);
                return;
            }
            if ("storage".equals(mode)) {
                var ctx = getTargetContext();
                var resolver = ctx.getContentResolver();
                var uri = resolver.getPersistedUriPermissions().stream().map(android.content.UriPermission::getUri)
                    .filter(u -> android.provider.DocumentsContract.getTreeDocumentId(u).equals("primary:AAA-NativeAgent-Test-1121"))
                    .findFirst().orElseThrow();
                var document = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, "primary:AAA-NativeAgent-Test-1121/qa05-note.txt");
                for (String mode : new String[]{"r", "rw"}) {
                    try (var fd = resolver.openFileDescriptor(document, mode)) {
                        var stat = android.system.Os.fstat(fd.getFileDescriptor());
                        observations.append(mode).append(" fd=").append(android.system.Os.readlink("/proc/self/fd/" + fd.getFd()))
                            .append(" dev=").append(stat.st_dev).append(" links=").append(stat.st_nlink).append('\n');
                    }
                }
                var files = dev.nativeagent.projecttree.TreeFileStore.openSelected(ctx);
                if (!files.readFile("qa05-note.txt").strip().matches("LIME_50[45]")) throw new AssertionError("Fixture readback");
                String name = "qa-volume-" + java.util.UUID.randomUUID() + "/check.txt";
                files.writeChecked(name, "alpha\n", "");
                String backup = files.writeChecked(name, "beta\n", dev.nativeagent.files.Workspace.sha256("alpha\n"));
                if (!files.readBackup(name, backup).equals("alpha\n") || !files.listBackups(name).contains(backup)) throw new AssertionError("Snapshot");
                try { files.writeChecked(name, "wrong", ""); throw new AssertionError("Existing file accepted as new"); }
                catch (java.io.IOException expected) { }
                if (!files.readFile(name).equals("beta\n")) throw new AssertionError("Final contents");
                var testFile = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,
                    android.provider.DocumentsContract.getTreeDocumentId(uri) + "/" + name);
                byte[] invalid = {(byte) 0xc3};
                try (var output = resolver.openOutputStream(testFile, "wt")) { output.write(invalid); }
                var baseline = files.readForRestore(name);
                if (!baseline.unreadable()) throw new AssertionError("Invalid UTF-8 accepted as text");
                try {
                    files.writeChecked(name, "wrong", baseline.sha256());
                    throw new AssertionError("Normal write accepted corrupt file");
                } catch (java.io.IOException expected) { }
                try {
                    files.restoreChecked(name, backup, dev.nativeagent.files.Workspace.sha256("beta\n"));
                    throw new AssertionError("Restore accepted stale baseline");
                } catch (java.io.IOException expected) { }
                if (!files.readForRestore(name).equals(baseline)) throw new AssertionError("Rejected write changed file");
                String rawBackup = files.restoreChecked(name, backup, baseline.sha256());
                if (!files.readFile(name).equals("alpha\n") || !files.listBackups(name).contains(rawBackup)) {
                    throw new AssertionError("Restore or backup of unreadable bytes failed");
                }
                report.putString("stream", observations + "PASS authorized volume, new file, overwrite, backup and stale write");
                finish(Activity.RESULT_OK, report);
                return;
            }
            for (String sign : new String[]{"-", "+"}) {
                boolean passing = sign.equals("+");
                var files = Map.of("calc.py", "def add(a,b): return a " + sign + " b\n",
                    "test.py", "import sys,unittest\nfrom calc import add\nprint(sys.version.split()[0])\n"
                    + "class Check(unittest.TestCase):\n def test_add(self): self.assertEqual(add(7,5),12)\n"
                    + "unittest.main()\n");
                var result = PythonRunner.run(getTargetContext(), files, "test.py");
                observations.append(result).append('\n');
                if (result.getInt("exitCode") != (passing ? 0 : 1) || result.getBoolean("timedOut")
                    || !result.getString("stderr").contains(passing ? "OK" : "AssertionError")
                    || !result.getString("stdout").contains("3.12")) {
                    throw new AssertionError("Unexpected unittest result");
                }
            }
            report.putString("stream", observations + "PASS both phone-local unittest exit codes");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable failure) {
            report.putString("stream", observations + "FAIL " + failure);
            finish(Activity.RESULT_CANCELED, report);
        }
    }
}
