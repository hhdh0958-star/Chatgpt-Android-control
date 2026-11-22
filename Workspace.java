package dev.nativeagent.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** The root and its contents must be private to the service UID. */
public final class Workspace implements ProjectFiles {
    private static final int MAX_BYTES = 256 * 1024;
    private final Path root;
    private final Path backups;

    public Workspace(Path root) throws IOException {
        this.root = Files.createDirectories(root).toRealPath();
        backups = this.root.resolve(".backups");
        Files.createDirectories(backups);
        if (Files.isSymbolicLink(backups)) throw new IOException("Invalid backup directory");
    }

    public synchronized List<String> listFiles(String directory) throws IOException {
        Path path = directory.isEmpty() ? root : resolve(directory);
        try (Stream<Path> entries = Files.list(path)) {
            List<String> names = entries.filter(entry -> !entry.equals(backups))
                .filter(entry -> !Files.isSymbolicLink(entry))
                .limit(257).map(entry -> entry.getFileName().toString()).sorted().toList();
            if (names.size() > 256) throw new IOException("Directory exceeds listing limit");
            return names;
        }
    }

    public synchronized String readFile(String name) throws IOException {
        return readText(resolve(name));
    }

    public synchronized boolean isDirectory(String name) throws IOException {
        return Files.isDirectory(resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized String readOptional(String name) throws IOException {
        Path path = resolve(name);
        return Files.notExists(path, LinkOption.NOFOLLOW_LINKS) ? null : readText(path);
    }

    public synchronized String writeChecked(String name, String content, String expectedHash) throws IOException {
        String old = readOptional(name);
        if (!(old == null ? "" : sha256(old)).equals(expectedHash)) throw new IOException("File changed");
        return writeFile(name, content);
    }

    /** A regular text file under this application's private root. */
    public synchronized String deleteChecked(String name, String expectedHash) throws IOException {
        Path source = resolve(name);
        String snapshot = saveRemovalBackup(source, expectedHash);
        Files.delete(source);
        return snapshot;
    }

    /** The destination parent must already exist. Never overwrite an existing entry. */
    public synchronized String moveChecked(String name, String destination, String expectedHash) throws IOException {
        Path source = resolve(name);
        Path target = resolve(destination);
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)
            || !Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Move destination unavailable");
        String snapshot = saveRemovalBackup(source, expectedHash);
        Files.move(source, target);
        return snapshot;
    }

    private String saveRemovalBackup(Path source, String expectedHash) throws IOException {
        String content = readText(source);
        if (!sha256(content).equals(expectedHash)) throw new IOException("File changed");
        Path history = Files.createDirectories(historyDirectory(source));
        String id = UUID.randomUUID().toString();
        Path staging = Files.createTempFile(history, ".backup-", ".tmp");
        try {
            Files.write(staging, encodeText(content));
            Files.move(staging, history.resolve(id), StandardCopyOption.ATOMIC_MOVE);
            return id;
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private String readText(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(MAX_BYTES + 1);
            if (content.length > MAX_BYTES) throw new IOException("File exceeds size limit");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
        }
    }

    public synchronized String writeFile(String name, String content) throws IOException {
        byte[] bytes = encodeText(content);
        Path path = resolve(name);
        Files.createDirectories(path.getParent());
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists) readText(path); // This interface edits text; every accepted snapshot must be restorable.
        String backup = "";
        Path snapshot = null;
        Path temporary = Files.createTempFile(path.getParent(), ".agent-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (exists) {
                var permissions = Files.getFileAttributeView(path, PosixFileAttributeView.class);
                if (permissions != null) Files.setPosixFilePermissions(temporary, permissions.readAttributes().permissions());
                backup = UUID.randomUUID().toString();
                Path history = historyDirectory(path);
                Files.createDirectories(history);
                snapshot = history.resolve(backup);
                Files.copy(path, snapshot);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            if (snapshot != null) Files.deleteIfExists(snapshot);
            throw failed;
        } finally {
            Files.deleteIfExists(temporary);
        }
        return backup;
    }

    public synchronized String applyPatch(String name, String expectedSha256, String before, String after)
        throws IOException {
        String current = readFile(name);
        if (!sha256(current).equals(expectedSha256)) throw new IOException("File changed");
        int offset = current.indexOf(before);
        if (before.isEmpty() || offset < 0 || current.indexOf(before, offset + 1) >= 0) {
            throw new IOException("Patch requires exactly one matching span");
        }
        return writeFile(name, current.substring(0, offset) + after + current.substring(offset + before.length()));
    }

    public synchronized String restore(String name, String backupId) throws IOException {
        return writeFile(name, readText(snapshot(resolve(name), backupId)));
    }

    public synchronized String readBackup(String name, String id) throws IOException {
        Path history = historyDirectory(resolve(name));
        if (!validBackupId(id)) throw new IOException("Invalid backup ID");
        return readText(history.resolve(id));
    }

    public synchronized List<String> listBackups(String name) throws IOException {
        return listHistory(historyDirectory(resolve(name)));
    }

    public static boolean validBackupId(String id) {
        return id != null && id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    }

    public static List<String> listHistory(Path directory) throws IOException {
        if (Files.notExists(directory)) return List.of();
        try (var stream = Files.list(directory)) {
            return stream.filter(p -> validBackupId(p.getFileName().toString()))
                .sorted(java.util.Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()
                    .thenComparing(Path::toString))
                .limit(20).map(p -> p.getFileName().toString()).toList();
        }
    }

    /** Unified diff between the last known contents at backupId and the current text. */
    public synchronized String diff(String name, String backupId) throws IOException {
        Path path = resolve(name);
        return unifiedDiff(path, readText(snapshot(path, backupId)), readText(path));
    }

    /** Produce the diff for a proposed write without creating files or backups. */
    public synchronized String previewDiff(String name, String proposed) throws IOException {
        encodeText(proposed);
        Path path = resolve(name);
        String original = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? readText(path) : null;
        return unifiedDiff(path, original, proposed);
    }

    private static byte[] encodeText(String text) throws IOException {
        if (text.length() > MAX_BYTES) throw new IOException("Write exceeds size limit");
        var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
        if (encoded.remaining() > MAX_BYTES) throw new IOException("Write exceeds size limit");
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private Path historyDirectory(Path path) throws IOException {
        Path directory = backups.resolve(sha256(root.relativize(path).toString()));
        if (Files.isSymbolicLink(backups) || Files.isSymbolicLink(directory)) throw new IOException("Invalid backup directory");
        return directory;
    }

    private Path snapshot(Path path, String backupId) throws IOException {
        if (backupId == null || !backupId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IOException("Invalid backup ID");
        }
        Path bound = historyDirectory(path).resolve(backupId);
        if (Files.exists(bound, LinkOption.NOFOLLOW_LINKS)) return bound;
        // Older releases placed explicitly requested snapshot IDs directly under .backups.
        return backups.resolve(backupId);
    }

    private String unifiedDiff(Path path, String original, String proposed) {
        String name = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        return unifiedDiff(name, original, proposed);
    }

    public static String unifiedDiff(String name, String original, String proposed) {
        if (java.util.Objects.equals(proposed, original)) return "";
        StringBuilder output = new StringBuilder("--- ").append(original == null ? "/dev/null" : "a/" + name)
            .append("\n+++ ").append(proposed == null ? "/dev/null" : "b/" + name).append('\n');
        String[] before = lines(original == null ? "" : original);
        String[] after = lines(proposed == null ? "" : proposed);
        int prefix = 0;
        while (prefix < before.length && prefix < after.length && before[prefix].equals(after[prefix])) prefix++;
        int suffix = 0;
        while (suffix < before.length - prefix && suffix < after.length - prefix
            && before[before.length - suffix - 1].equals(after[after.length - suffix - 1])) suffix++;
        int start = Math.max(0, prefix - 3);
        int trailing = Math.min(3, suffix);
        int beforeEnd = before.length - suffix;
        int afterEnd = after.length - suffix;
        int removed = beforeEnd + trailing - start;
        int added = afterEnd + trailing - start;
        output.append("@@ -").append(removed == 0 ? start : start + 1).append(',').append(removed)
            .append(" +").append(added == 0 ? start : start + 1).append(',').append(added).append(" @@\n");
        for (int i = start; i < prefix; i++) appendLine(output, ' ', before[i]);
        for (int i = prefix; i < beforeEnd; i++) appendLine(output, '-', before[i]);
        for (int i = prefix; i < afterEnd; i++) appendLine(output, '+', after[i]);
        for (int i = 0; i < trailing; i++) appendLine(output, ' ', after[afterEnd + i]);
        return output.toString();
    }

    private static String[] lines(String value) {
        return value.isEmpty() ? new String[0] : value.split("(?<=\n)");
    }

    private static void appendLine(StringBuilder output, char change, String line) {
        output.append(change).append(line);
        if (!line.endsWith("\n")) output.append("\n\\ No newline at end of file\n");
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Path resolve(String name) throws IOException {
        Path relative;
        try {
            relative = Path.of(name);
        } catch (InvalidPathException invalid) {
            throw new IOException("Invalid path", invalid);
        }
        if (name.isEmpty() || relative.getRoot() != null) throw new IOException("Expected a relative path");
        Path current = root;
        for (Path part : relative) {
            String segment = part.toString();
            if (segment.equals(".") || segment.equals("..") || segment.equals(".backups")
                || segment.chars().anyMatch(Character::isISOControl)) {
                throw new IOException("Invalid path segment");
            }
            current = current.resolve(part);
            if (current.equals(backups)) throw new IOException("Private backup directory");
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link denied");
        }
        return current;
    }
}

