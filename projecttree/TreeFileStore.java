package dev.nativeagent.projecttree;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import dev.nativeagent.files.ProjectFiles;
import dev.nativeagent.files.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Accesses the selected local storage tree exclusively through DocumentsContract. */
public final class TreeFileStore implements ProjectFiles {
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final String PREFS = "nativeagent.projecttree";
    private static final String SELECTED = "tree";
    private static final int RW = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int LIMIT = 256 * 1024;
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS};

    private final ContentResolver resolver;
    private final Uri tree;
    private final String rootId;
    private final Path backups;
    private final VolumeGuard guard;

    /** Stable per granted local volume and folder. Never expose its path through the chat protocol. */
    public String workspaceKey() throws IOException {
        guard.directory(rootId);
        return Workspace.sha256(AUTHORITY + "\n" + rootId);
    }

    private TreeFileStore(Context context, Uri tree) throws IOException {
        if (tree == null || !"content".equals(tree.getScheme()) || !AUTHORITY.equals(tree.getAuthority())
            || !DocumentsContract.isTreeUri(tree) || tree.getPathSegments().size() != 2) {
            throw new IOException("Expected a local storage tree");
        }
        this.tree = tree;
        rootId = DocumentsContract.getTreeDocumentId(tree);
        // Restrict selection to an actual project directory instead of a storage volume root.
        int colon = rootId.indexOf(':');
        if (colon < 1 || colon + 1 == rootId.length()) throw new IOException("Expected a project directory");
        resolver = context.getContentResolver();
        guard = new VolumeGuard(context, rootId);
        backups = context.getFilesDir().toPath().resolve("tree-backups").resolve(digest(utf8(rootId)));
    }

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static TreeFileStore openSelected(Context context) throws IOException {
        String saved = settings(context).getString(SELECTED, null);
        if (saved == null) return null;
        TreeFileStore store = new TreeFileStore(context, Uri.parse(saved));
        for (UriPermission grant : store.resolver.getPersistedUriPermissions()) {
            if (grant.getUri().equals(store.tree) && grant.isReadPermission() && grant.isWritePermission()) {
                return store;
            }
        }
        throw new IOException("Project folder authorization expired; select it again");
    }

    static void select(Context context, Uri tree, int flags) throws IOException {
        if ((flags & RW) != RW) throw new IOException("Read/write permission required");
        TreeFileStore store = new TreeFileStore(context, tree);
        Entry root = store.stat(store.document(store.rootId));
        if (!root.directory() || (root.flags & Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
            throw new IOException("Project folder is read only");
        }
        SharedPreferences preferences = settings(context);
        String previous = preferences.getString(SELECTED, null);
        store.resolver.takePersistableUriPermission(tree, RW);
        if (!preferences.edit().putString(SELECTED, tree.toString()).commit()) {
            if (!tree.toString().equals(previous)) store.resolver.releasePersistableUriPermission(tree, RW);
            throw new IOException("Unable to save project selection");
        }
        if (previous != null && !tree.toString().equals(previous)) {
            try {
                store.resolver.releasePersistableUriPermission(Uri.parse(previous), RW);
            } catch (SecurityException alreadyRevoked) {
                // The system may have revoked the previous grant before the new selection.
            }
        }
    }

    public static void clearSelection(Context context) throws IOException {
        SharedPreferences preferences = settings(context);
        String saved = preferences.getString(SELECTED, null);
        if (!preferences.edit().remove(SELECTED).commit()) throw new IOException("Unable to clear selection");
        if (saved != null) {
            try {
                context.getContentResolver().releasePersistableUriPermission(Uri.parse(saved), RW);
            } catch (SecurityException alreadyRevoked) { }
        }
    }

    private record Entry(String id, String name, String mime, int flags) {
        boolean directory() { return Document.MIME_TYPE_DIR.equals(mime); }
        static Entry read(Cursor cursor) {
            return new Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3));
        }
    }

    private Uri document(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }

    private Cursor query(Uri uri) throws IOException {
        Cursor cursor = resolver.query(uri, COLUMNS, null, null, null);
        if (cursor == null) throw new IOException("Storage provider is unavailable");
        return cursor;
    }

    private Entry stat(Uri uri) throws IOException {
        try (Cursor cursor = query(uri)) {
            if (!cursor.moveToFirst()) throw new IOException("Document was moved or removed");
            return Entry.read(cursor);
        }
    }

    static String[] segments(String path) throws IOException {
        if (path == null || path.length() > 1024 || path.isEmpty()) throw new IOException("Invalid project path");
        String[] parts = path.split("/", -1);
        if (parts.length > 32) throw new IOException("Project path is too deep");
        for (String part : parts) {
            if (!safeName(part)) throw new IOException("Invalid project path segment");
        }
        return parts;
    }

    private static boolean safeName(String part) {
        return part != null && !part.isEmpty() && !part.equals(".") && !part.equals("..")
            && part.indexOf('\\') < 0 && part.indexOf('/') < 0 && part.indexOf(':') < 0
            && part.length() <= 255 && part.chars().noneMatch(Character::isISOControl);
    }

    private List<Entry> children(String parent, String match) throws IOException {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        var entries = new ArrayList<Entry>();
        try (Cursor cursor = query(uri)) {
            while (cursor.moveToNext()) {
                Entry entry = Entry.read(cursor);
                if (!safeName(entry.name) || (match != null && !match.equals(entry.name))) continue;
                // The external-storage provider exposes volume:relative/path IDs. Validate the
                // provider's returned ID before opening a stream or accepting a directory.
                if (!(parent + "/" + entry.name).equals(entry.id)) throw new IOException("Unexpected child identity");
                entries.add(entry);
                if (entries.size() > (match == null ? 256 : 1)) throw new IOException("Ambiguous or oversized listing");
            }
        }
        return entries;
    }

    private String directory(String[] parts, int count, boolean create) throws IOException {
        String id = rootId;
        for (int i = 0; i < count; i++) {
            List<Entry> found = children(id, parts[i]);
            Entry entry;
            if (!found.isEmpty()) entry = found.get(0);
            else if (create) entry = create(id, parts[i], Document.MIME_TYPE_DIR);
            else throw new java.io.FileNotFoundException("Directory not found");
            if (!entry.directory()) throw new IOException("Expected a directory");
            id = entry.id;
        }
        return id;
    }

    private Entry create(String parent, String name, String type) throws IOException {
        guard.directory(parent);
        Uri uri = DocumentsContract.createDocument(resolver, document(parent), type, name);
        if (uri == null) throw new IOException("Unable to create document");
        Entry entry = stat(uri);
        if (!(parent + "/" + name).equals(entry.id) || !name.equals(entry.name)) {
            // Local provider may auto-rename when a competing writer creates the same name.
            // Keep that competing document intact and remove only our newly created empty one.
            // The provider returned a different identity. Do not mutate it a second time.
            throw new IOException("Provider renamed document during creation");
        }
        return entry;
    }

    private Entry file(String path, boolean createParents, boolean allowMissing) throws IOException {
        String[] parts = segments(path);
        String parent = directory(parts, parts.length - 1, createParents);
        List<Entry> found = children(parent, parts[parts.length - 1]);
        if (found.isEmpty()) {
            if (allowMissing) return null;
            throw new IOException("File not found");
        }
        Entry entry = found.get(0);
        if (entry.directory() || (entry.flags & Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
            throw new IOException("Expected a regular file");
        }
        return entry;
    }

    @Override
    public synchronized List<String> listFiles(String path) throws IOException {
        String id = rootId;
        if (!"".equals(path)) {
            String[] parts = segments(path);
            id = directory(parts, parts.length, false);
        }
        List<String> names = new ArrayList<>();
        for (Entry e : children(id, null)) names.add(e.name);
        Collections.sort(names);
        return names;
    }

    private byte[] read(String id) throws IOException {
        var descriptor = resolver.openFileDescriptor(document(id), "r");
        if (descriptor == null) throw new IOException("Provider did not open the file");
        try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            guard.file(descriptor, id);
            return bounded(stream);
        }
    }

    private static byte[] bounded(InputStream stream) throws IOException {
        byte[] result = stream.readNBytes(LIMIT + 1);
        if (result.length > LIMIT) throw new IOException("File exceeds size limit");
        return result;
    }

    private static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] utf8(String text) throws IOException {
        if (text == null || text.length() > LIMIT) throw new IOException("Text exceeds size limit");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        if (encoded.remaining() > LIMIT) throw new IOException("Text exceeds byte limit");
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public synchronized String readFile(String path) throws IOException {
        return decode(read(file(path, false, false).id));
    }

    public synchronized boolean isDirectory(String path) throws IOException {
        String[] parts = segments(path);
        String parent = directory(parts, parts.length - 1, false);
        var found = children(parent, parts[parts.length - 1]);
        return !found.isEmpty() && found.get(0).directory();
    }

    public synchronized String readOptional(String path) throws IOException {
        try {
            Entry entry = file(path, false, true);
            return entry == null ? null : decode(read(entry.id));
        } catch (java.io.FileNotFoundException missingParent) { return null; }
    }

    public synchronized String writeChecked(String path, String content, String expectedHash) throws IOException {
        if (expectedHash == null) throw new IOException("Missing baseline");
        return write(path, utf8(content), expectedHash);
    }

    @Override
    public synchronized RestoreBaseline readForRestore(String path) throws IOException {
        Entry entry;
        try {
            entry = file(path, false, true);
        } catch (java.io.FileNotFoundException missingParent) {
            entry = null;
        }
        if (entry == null) return new RestoreBaseline(null, "");
        byte[] bytes = read(entry.id);
        String hash = digest(bytes);
        try {
            return new RestoreBaseline(decode(bytes), hash);
        } catch (CharacterCodingException invalidText) {
            return new RestoreBaseline(null, hash);
        }
    }

    @Override
    public synchronized String restoreChecked(String path, String backupId, String expectedHash) throws IOException {
        if (expectedHash == null) throw new IOException("Missing baseline");
        return write(path, utf8(readBackup(path, backupId)), expectedHash, true);
    }

    @Override
    public synchronized String writeFile(String path, String content) throws IOException {
        return write(path, utf8(content), null);
    }

    private String write(String path, byte[] bytes, String expectedDigest) throws IOException {
        return write(path, bytes, expectedDigest, false);
    }

    private String write(String path, byte[] bytes, String expectedDigest, boolean restoring) throws IOException {
        boolean allowCreate = expectedDigest == null || expectedDigest.isEmpty();
        Entry entry = file(path, allowCreate, allowCreate);
        boolean created = entry == null;
        if (!created && "".equals(expectedDigest)) throw new IOException("File already exists");
        if (created) {
            String[] parts = segments(path);
            String parent = directory(parts, parts.length - 1, false);
            entry = create(parent, parts[parts.length - 1], "application/octet-stream");
        }
        return writeEntry(path, entry, bytes, expectedDigest, created, restoring);
    }

    private String writeEntry(String path, Entry entry, byte[] bytes, String expectedDigest, boolean created, boolean restoring)
        throws IOException {
        if ((entry.flags & Document.FLAG_SUPPORTS_WRITE) == 0) throw new IOException("File is read only");
        Uri uri = document(entry.id);
        ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "rw");
        if (fd == null) throw new IOException("Provider did not open a writable file");
        try (var stream = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            guard.file(fd, entry.id);
            FileChannel channel = stream.getChannel();
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("File is locked");
                byte[] original = readDescriptor(fd);
                if (!restoring) decode(original);
                if (!created && expectedDigest != null && !digest(original).equals(expectedDigest)) throw new IOException("File changed");
                if (created && original.length != 0) throw new IOException("New file changed");
                String backup = created ? "" : saveBackup(path, original);
                try {
                    replaceBytes(channel, bytes);
                    if (!Arrays.equals(bytes, readDescriptor(fd))) throw new IOException("File readback mismatch");
                } catch (IOException failure) {
                    // All accepted files are bounded; restore the same descriptor before releasing the lock.
                    try {
                        replaceBytes(channel, original);
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
                return backup;
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                throw new IOException("File is busy", busy);
            }
        }
    }

    private static byte[] readDescriptor(ParcelFileDescriptor fd) throws IOException {
        // Opening and closing a second descriptor for this inode could release a POSIX process lock.
        byte[] result = new byte[LIMIT + 1];
        int offset = 0;
        try {
            while (offset < result.length) {
                int count = Os.pread(fd.getFileDescriptor(), result, offset, result.length - offset, offset);
                if (count == 0) break;
                offset += count;
            }
        } catch (ErrnoException error) {
            throw new IOException("Unable to read project descriptor", error);
        }
        if (offset > LIMIT) throw new IOException("File exceeds size limit");
        return Arrays.copyOf(result, offset);
    }

    private static void replaceBytes(FileChannel channel, byte[] bytes) throws IOException {
        channel.position(0);
        ByteBuffer source = ByteBuffer.wrap(bytes);
        while (source.hasRemaining()) {
            if (channel.write(source) <= 0) throw new IOException("File write made no progress");
        }
        channel.truncate(bytes.length);
        channel.force(true);
    }

    private Path history(String path) throws IOException {
        segments(path);
        return backups.resolve(digest(utf8(path)));
    }

    private String saveBackup(String path, byte[] bytes) throws IOException {
        Path directory = Files.createDirectories(history(path));
        String id = UUID.randomUUID().toString();
        Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                replaceBytes(output, bytes);
            }
            Files.move(temporary, directory.resolve(id), StandardCopyOption.ATOMIC_MOVE);
            // Persist the backup entry and any newly created directories before mutating the shared file.
            Path appFiles = backups.getParent().getParent();
            for (Path parent = directory; !parent.equals(appFiles); parent = parent.getParent()) {
                syncDirectory(parent);
            }
            syncDirectory(appFiles);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return id;
    }

    private static void syncDirectory(Path directory) throws IOException {
        try {
            var descriptor = Os.open(directory.toString(),
                OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK, 0);
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw new IOException("Backup path is not a directory");
                Os.fsync(descriptor);
            } finally {
                Os.close(descriptor);
            }
        } catch (ErrnoException error) {
            throw new IOException("Unable to make project backup durable", error);
        }
    }

    @Override
    public synchronized String applyPatch(String path, String expectedSha256, String before, String after) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid patch");
        }
        return write(path, patched(readFile(path), before, after), expectedSha256);
    }

    static byte[] patched(String original, String before, String after) throws IOException {
        if (before == null || before.isEmpty() || after == null) throw new IOException("Invalid patch");
        int offset = original.indexOf(before);
        if (offset < 0 || original.indexOf(before, offset + 1) >= 0) throw new IOException("Expected one matching span");
        return utf8(original.substring(0, offset) + after + original.substring(offset + before.length()));
    }

    @Override
    public synchronized String restore(String path, String backupId) throws IOException {
        return writeFile(path, readBackup(path, backupId));
    }

    public synchronized List<String> listBackups(String path) throws IOException {
        return Workspace.listHistory(history(path));
    }

    public synchronized String readBackup(String path, String backupId) throws IOException {
        if (backupId == null || !backupId.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) {
            throw new IOException("Invalid backup ID");
        }
        try (InputStream input = Files.newInputStream(history(path).resolve(backupId), LinkOption.NOFOLLOW_LINKS)) {
            return decode(bounded(input));
        }
    }
}
