package dev.nativeagent.runner;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/** The caller supplies an explicit, authorized snapshot; only the caller can apply file changes. */
public final class PythonRunner {
    static final int MAX_REQUEST = 130_000;
    private PythonRunner() {}

    public static JSONObject run(Context context, Map<String,String> files, String entry)
            throws IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Run Python on a background thread");
        String payload = snapshot(files, entry);
        var reply = new ArrayBlockingQueue<String>(1);
        var active = new AtomicBoolean(true);
        Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == 2 && active.get()) {
                String result = msg.getData().getString("result");
                if (result != null && result.length() < 32_000) reply.offer(result);
            }
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!active.get()) return;
                var request = Message.obtain(null, 1);
                request.replyTo = receiver;
                var args = new Bundle();
                args.putString("request", payload);
                request.setData(args);
                try { new Messenger(binder).send(request); }
                catch (RemoteException error) { reply.offer(""); }
            }
            public void onServiceDisconnected(ComponentName name) { reply.offer(""); }
            public void onBindingDied(ComponentName name) { reply.offer(""); }
            public void onNullBinding(ComponentName name) { reply.offer(""); }
        };
        Context app = context.getApplicationContext();
        if (!app.bindService(new Intent(app, PythonService.class), connection, Context.BIND_AUTO_CREATE)) {
            throw new IOException("Could not bind the local Python service");
        }
        try {
            String result = reply.poll(30, TimeUnit.SECONDS);
            if (result == null) return failure("Local Python service exceeded its 30-second deadline", true);
            if (result.isEmpty()) return failure("Local Python service disconnected", false);
            return new JSONObject(result);
        } catch (JSONException error) {
            throw new IOException("Invalid local Python response", error);
        } finally {
            active.set(false);
            app.unbindService(connection);
        }
    }

    private static String snapshot(Map<String,String> files, String entry) throws IOException {
        if (files.isEmpty() || files.size() > 32 || !files.containsKey(entry)
            || !(entry.endsWith(".py") || entry.endsWith(".sh") || entry.endsWith(".mjs") || entry.endsWith(".js"))) {
            throw new IOException("Expected an entry script in a snapshot of up to 64 files");
        }
        int total = 0;
        JSONObject contents = new JSONObject();
        try {
            for (var file : files.entrySet()) {
                String path = file.getKey(), value = file.getValue();
                if (path == null || path.isEmpty() || path.length() > 240 || path.indexOf('\\') >= 0
                        || path.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("Invalid filename");
                for (String segment : path.split("/", -1)) {
                    if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.equals(".backups")) {
                        throw new IOException("Invalid filename component");
                    }
                }
                if (value == null || value.length() > 65_536) throw new IOException("Snapshot file exceeds 64 KiB");
                total += value.getBytes(StandardCharsets.UTF_8).length;
                if (total > 96 * 1024) throw new IOException("Snapshot exceeds 96 KiB");
                contents.put(path, value);
            }
            String result = new JSONObject().put("entry", entry).put("files", contents).toString();
            if (result.length() > MAX_REQUEST) throw new IOException("Serialized snapshot too large");
            return result;
        } catch (JSONException error) {
            throw new IOException("Invalid snapshot", error);
        }
    }

    static JSONObject failure(String error, boolean timedOut) {
        try {
            return new JSONObject().put("exitCode", -1).put("stdout", "").put("stderr", error)
                .put("timedOut", timedOut).put("truncated", false);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
