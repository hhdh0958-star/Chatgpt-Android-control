package dev.nativeagent.probe;

import dev.nativeagent.files.ProjectFiles;
import dev.nativeagent.files.Workspace;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ProjectTools {
    interface Review {
        boolean ask(String title, String detail) throws InterruptedException;
    }
    private static final Set<String> SOURCE = Set.of("py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "java",
        "kt", "c", "cc", "cpp", "h", "hpp", "rs", "go", "css", "html", "md", "txt", "sh");
    private static final Set<String> EXCLUDED = Set.of("node_modules", "__pycache__", "build", "dist", "target");

    static void validatePath(String path, boolean rootAllowed) throws IOException {
        if (rootAllowed && path.isEmpty()) return;
        if (path.isEmpty() || path.length() > 1024 || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
            || path.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            throw new IOException("Invalid relative path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.length() > 255 || segment.equals(".") || segment.equals("..")
                || segment.equalsIgnoreCase(".backups") || segment.equalsIgnoreCase(".git")
                || segment.equalsIgnoreCase(".ssh")) throw new IOException("Reserved project path");
        }
    }

    static boolean needsReview(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String part : lower.split("/")) {
            if (part.startsWith(".") || part.contains("secret") || part.contains("credential") || part.contains("token")
                || part.contains("password") || part.contains("config") || part.contains("setting")
                || part.contains("signing") || part.contains("private") || part.contains("keystore")) return true;
        }
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 || !SOURCE.contains(name.substring(dot + 1));
    }

    static String patch(String old, String sha, String before, String after) throws IOException {
        if (old == null || !Workspace.sha256(old).equals(sha)) throw new IOException("File changed");
        int start = old.indexOf(before);
        if (before.isEmpty() || start < 0 || old.indexOf(before, start + 1) >= 0) throw new IOException("Ambiguous patch");
        return old.substring(0, start) + after + old.substring(start + before.length());
    }

    static JSONObject search(ProjectFiles files, String root, String query, BooleanSupplier stopped)
            throws IOException, JSONException, InterruptedException {
        if (query.isBlank() || query.length() > 128 || query.indexOf('\n') >= 0) throw new IOException("Expected literal text");
        var dirs = new ArrayDeque<String>();
        dirs.add(root);
        var hits = new JSONArray();
        int seen = 0, skipped = 0, scanned = 0, bytes = 0;
        boolean limited = false;
        outer: while (!dirs.isEmpty()) {
            String directory = dirs.removeFirst();
            for (String name : files.listFiles(directory)) {
                if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (++seen > 256 || scanned >= 80 || bytes >= 1024 * 1024) { limited = true; break outer; }
                String path = directory.isEmpty() ? name : directory + "/" + name;
                if (name.startsWith(".") || EXCLUDED.contains(name)) { skipped++; continue; }
                try {
                    validatePath(path, false);
                    if (files.isDirectory(path)) { dirs.addLast(path); continue; }
                    if (needsReview(path)) { skipped++; continue; }
                    String value = files.readFile(path);
                    scanned++;
                    bytes += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    int lineNo = 0;
                    for (String line : value.split("\n", -1)) {
                        lineNo++;
                        int offset = line.indexOf(query);
                        if (offset < 0) continue;
                        int start = Math.max(0, offset - 48);
                        hits.put(new JSONObject().put("path", path).put("line", lineNo)
                            .put("text", line.substring(start, Math.min(line.length(), start + 140))));
                        if (hits.length() == 20) { limited = true; break outer; }
                    }
                } catch (IOException unreadable) { skipped++; }
            }
        }
        return new JSONObject().put("matches", hits).put("filesScanned", scanned).put("skipped", skipped).put("limited", limited);
    }
}
