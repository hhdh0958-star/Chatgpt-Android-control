package dev.nativeagent.probe;

import dev.nativeagent.files.ProjectFiles;
import dev.nativeagent.files.Workspace;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local snapshots built from explicit project files. Only the app-owned bare Git store is writable. */
final class ProjectGit {
    static final class Rejected extends IOException {
        final String code;
        Rejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final String BRANCH = "refs/heads/main";
    private final ProjectFiles files;
    private final Path storage;
    private static boolean configured;

    ProjectGit(ProjectFiles files, Path storage) {
        this.files = files;
        this.storage = storage;
    }

    private static synchronized void disableExternalConfig() {
        if (configured) return;
        SystemReader.setInstance(new SystemReader.Delegate(SystemReader.getInstance()) {
            @Override public FileBasedConfig openJGitConfig(Config parent, FS fs) { return empty(parent, fs); }
            @Override public FileBasedConfig openUserConfig(Config parent, FS fs) { return empty(parent, fs); }
            @Override public FileBasedConfig openSystemConfig(Config parent, FS fs) { return empty(parent, fs); }
            private FileBasedConfig empty(Config parent, FS fs) {
                return new FileBasedConfig(parent, new File("unused"), fs) {
                    @Override public void load() {}
                    @Override public void save() {}
                    @Override public boolean isOutdated() { return false; }
                };
            }
        });
        configured = true;
    }

    private Repository open(String project, boolean create) throws IOException {
        if (!project.isEmpty() && !files.isDirectory(project)) throw new IOException("Expected a project directory");
        disableExternalConfig();
        Path git = storage.resolve(Workspace.sha256(project));
        if (create) Files.createDirectories(storage);
        try {
            return new FileRepositoryBuilder().setGitDir(git.toFile()).setBare().setInitialBranch("main")
                .setMustExist(!create).build();
        } catch (org.eclipse.jgit.api.errors.InvalidRefNameException impossible) {
            throw new IOException("Invalid built-in branch", impossible);
        }
    }

    JSONObject init(String project) throws IOException, JSONException {
        try (Repository repo = open(project, true)) {
            boolean existing = repo.getObjectDatabase().exists();
            if (!existing) repo.create(true);
            return new JSONObject().put("existing", existing).put("branch", "main");
        }
    }

    Map<String, String> snapshot(String project, String names) throws IOException {
        var filesByName = new TreeMap<String, String>();
        int bytes = 0;
        for (String name : names.split("\n", -1)) {
            ProjectTools.validatePath(name, false);
            String full = project.isEmpty() ? name : project + "/" + name;
            ProjectTools.validatePath(full, false);
            if (ProjectTools.needsReview(full)) throw new Rejected("git_input_restricted", "Git accepts ordinary source files in this workspace");
            String content = files.readFile(full);
            bytes += content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 256 * 1024 || filesByName.putIfAbsent(name, content) != null) {
                throw new IOException("Selected files exceed Git snapshot limit or repeat");
            }
        }
        return filesByName;
    }

    JSONObject status(String project, Map<String, String> snapshot) throws IOException, JSONException {
        try (Repository repo = open(project, false); var scan = new RevWalk(repo);
                var formatter = new org.eclipse.jgit.lib.ObjectInserter.Formatter()) {
            ObjectId head = repo.resolve(BRANCH);
            DirCache base = head == null ? DirCache.newInCore() : DirCache.read(scan.getObjectReader(), scan.parseCommit(head).getTree());
            var entries = new JSONArray();
            for (var file : snapshot.entrySet()) {
                var entry = base.getEntry(file.getKey());
                ObjectId id = formatter.idFor(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8));
                entries.put(new JSONObject().put("path", file.getKey()).put("state",
                    entry == null ? "untracked" : entry.getObjectId().equals(id) ? "clean" : "modified"));
            }
            return new JSONObject().put("head", head == null ? JSONObject.NULL : head.name()).put("files", entries);
        }
    }

    record Preview(ObjectId head, String diff) {
        JSONObject asJson() throws JSONException {
            return new JSONObject().put("head", head == null ? JSONObject.NULL : head.name()).put("diff", diff);
        }
    }

    Preview preview(String project, Map<String, String> snapshot) throws IOException {
        try (Repository repo = open(project, false); var scan = new RevWalk(repo)) {
            ObjectId head = repo.resolve(BRANCH);
            DirCache base = head == null ? DirCache.newInCore() : DirCache.read(scan.getObjectReader(), scan.parseCommit(head).getTree());
            var diff = new StringBuilder();
            for (var file : snapshot.entrySet()) {
                var entry = base.getEntry(file.getKey());
                String old = entry == null ? null : new String(scan.getObjectReader()
                    .open(entry.getObjectId(), Constants.OBJ_BLOB).getBytes(256 * 1024), StandardCharsets.UTF_8);
                diff.append(Workspace.unifiedDiff(file.getKey(), old, file.getValue()));
                if (diff.length() > 10_000) throw new Rejected("git_diff_too_large", "Select fewer or smaller changes for review");
            }
            return new Preview(head, diff.toString());
        }
    }

    static void validateMessage(String message) throws Rejected {
        if (message.isBlank() || message.length() > 160 || message.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            throw new Rejected("git_invalid_message", "Use a single-line commit message of 1 to 160 characters");
        }
    }

    JSONObject commit(String project, Map<String, String> snapshot, String message, ObjectId reviewedHead)
            throws IOException, JSONException {
        validateMessage(message);
        try (Repository repo = open(project, false); var scan = new RevWalk(repo); var objects = repo.newObjectInserter()) {
            ObjectId old = repo.resolve(BRANCH);
            if (!java.util.Objects.equals(old, reviewedHead)) {
                throw new Rejected("git_head_changed", "Branch changed after review");
            }
            var previous = old == null ? null : scan.parseCommit(old);
            var tree = new TreeMap<String, DirCacheEntry>();
            if (previous != null) {
                var base = DirCache.read(scan.getObjectReader(), previous.getTree());
                for (int i = 0; i < base.getEntryCount(); i++) {
                    var entry = base.getEntry(i);
                    tree.put(entry.getPathString(), entry);
                }
            }
            for (var file : snapshot.entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(objects.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
                tree.put(file.getKey(), entry);
            }
            if (tree.size() > 512) throw new IOException("Git tree exceeds file count limit");
            var cache = DirCache.newInCore();
            var builder = cache.builder();
            tree.values().forEach(builder::add);
            builder.finish();
            ObjectId treeId = cache.writeTree(objects);
            if (previous != null && treeId.equals(previous.getTree())) {
                throw new Rejected("git_no_changes", "Selected files match the current Git commit");
            }
            var commit = new CommitBuilder();
            commit.setTreeId(treeId);
            if (old != null) commit.setParentId(old);
            var author = new PersonIdent("Native Agent", "local@native-agent.invalid", Instant.now(), ZoneOffset.UTC);
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage(message + "\n");
            ObjectId id = objects.insert(commit);
            objects.flush();
            RefUpdate ref = repo.updateRef(BRANCH);
            ref.setExpectedOldObjectId(old == null ? ObjectId.zeroId() : old);
            ref.setNewObjectId(id);
            var outcome = ref.update(scan);
            if (outcome != RefUpdate.Result.NEW && outcome != RefUpdate.Result.FAST_FORWARD) {
                throw new Rejected("git_ref_update_failed", "Git did not update the branch");
            }
            return new JSONObject().put("commit", id.name()).put("selectedFiles", snapshot.size());
        }
    }

    JSONObject log(String project) throws IOException, JSONException {
        try (Repository repo = open(project, false); var scan = new RevWalk(repo)) {
            var commits = new JSONArray();
            ObjectId head = repo.resolve(BRANCH);
            if (head != null) {
                scan.markStart(scan.parseCommit(head));
                for (int i = 0; i < 10; i++) {
                    var commit = scan.next();
                    if (commit == null) break;
                    commits.put(new JSONObject().put("id", commit.name()).put("message", commit.getShortMessage()));
                }
            }
            return new JSONObject().put("commits", commits);
        }
    }
}
