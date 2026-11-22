package dev.nativeagent.files;

import java.io.IOException;
import java.util.List;

/** UTF-8 files under a single user-authorized project root. */
public interface ProjectFiles {
    record RestoreBaseline(String text, String sha256) {
        public boolean unreadable() { return text == null && !sha256.isEmpty(); }
    }

    List<String> listFiles(String directory) throws IOException;
    boolean isDirectory(String path) throws IOException;
    String readOptional(String path) throws IOException;
    String readFile(String path) throws IOException;
    String writeFile(String path, String content) throws IOException;
    String applyPatch(String path, String expectedSha256, String before, String after) throws IOException;
    String restore(String path, String backupId) throws IOException;
    String readBackup(String path, String backupId) throws IOException;
    List<String> listBackups(String path) throws IOException;
    default RestoreBaseline readForRestore(String path) throws IOException {
        String content = readOptional(path);
        return new RestoreBaseline(content, content == null ? "" : Workspace.sha256(content));
    }
    default String restoreChecked(String path, String backupId, String expectedHash) throws IOException {
        return writeChecked(path, readBackup(path, backupId), expectedHash);
    }
    /** Empty expected hash requires a new file. A digest requires that exact previous content. */
    String writeChecked(String path, String content, String expectedHash) throws IOException;
}
