package dev.nativeagent.probe;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A bounded read from this process's Android main buffer. No tool argument reaches argv. */
final class LogDiagnostics {
    private static final Pattern LINE = Pattern.compile(
        "^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([0-9]+)\\s+[0-9]+\\s+I\\s+NativeAgentProbe\\s*:\\s*(.{1,500})$");
    private static final Pattern TOOL = Pattern.compile(
        "tool=[a-z_]{1,32} step=[1-8] ok=(true|false) sha256=(?:[a-f0-9]{64}|-)"
        + "(?: (?:exitCode|timedOut|truncated|stdoutChars|stderrChars|errorCode|createdCommit|commit|timer|"
        + "verifiedRunning|remainingSeconds|captureSha256|captureBytes|captureFailed)=[A-Za-z0-9_\\-]{1,64})*");

    static boolean request(String text) { return text.startsWith("请诊断手机Agent日志"); }

    static JSONObject read(Context context) throws IOException, InterruptedException, JSONException {
        int pid = android.os.Process.myPid();
        var output = Files.createTempFile(context.getCacheDir().toPath(), "agent-logcat-", ".tmp");
        Process child = null;
        try {
            child = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-d", "-t", "180", "--pid",
                Integer.toString(pid), "-v", "threadtime", "NativeAgentProbe:I", "*:S")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!child.waitFor(3, TimeUnit.SECONDS) || child.exitValue() != 0) throw new IOException("Logcat unavailable");
            if (Files.size(output) > 768 * 1024) throw new IOException("Logcat exceeded read limit");
            try (var lines = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
                return summarize(lines, pid);
            }
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    static JSONObject summarize(BufferedReader lines, int pid) throws IOException, JSONException {
        var events = new ArrayDeque<JSONObject>();
        int successful = 0, failed = 0;
        for (String line; (line = lines.readLine()) != null;) {
            var matcher = LINE.matcher(line);
            if (!matcher.matches() || !Integer.toString(pid).equals(matcher.group(2))) continue;
            String body = matcher.group(3);
            var tool = TOOL.matcher(body);
            if (tool.matches()) {
                if (tool.group(1).equals("true")) successful++; else failed++;
            } else if (!body.equals("chat message submitted") && !body.equals("task stopped")
                && !body.equals("local task prepared; awaiting user send")) continue;
            if (events.size() == 16) events.removeFirst();
            events.addLast(new JSONObject().put("time", matcher.group(1)).put("event", body));
        }
        return new JSONObject().put("source", "android_logcat").put("scope", "current_native_agent_process")
            .put("successfulTools", successful).put("failedTools", failed).put("recent", new JSONArray(events));
    }
}
