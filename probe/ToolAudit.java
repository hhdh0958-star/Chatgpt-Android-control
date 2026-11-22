package dev.nativeagent.probe;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;

/** Bounded app-private record without user messages or file contents. */
final class ToolAudit {
    private final File current;
    private final File previous;

    ToolAudit(File directory) {
        current = new File(directory, "tool-history.log");
        previous = new File(directory, "tool-history.previous.log");
    }

    synchronized List<String> recent() {
        var events = new ArrayDeque<String>();
        for (File file : List.of(previous, current)) {
            if (!file.isFile()) continue;
            try (var lines = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                for (String line; (line = lines.readLine()) != null;) {
                    if (line.isBlank() || line.length() > 512) continue;
                    if (events.size() == 12) events.removeFirst();
                    events.addLast(line);
                }
            } catch (IOException error) {
                Log.w("NativeAgentProbe", "Could not read execution history", error);
            }
        }
        return List.copyOf(events);
    }

    synchronized void append(String taskId, String event) {
        try {
            if (current.length() >= 128 * 1024) {
                java.nio.file.Files.move(current.toPath(), previous.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String line = Instant.now() + " " + taskId + " " + event + "\n";
            try (var out = new FileOutputStream(current, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            Log.w("NativeAgentProbe", "Could not append execution history", error);
        }
    }
}
