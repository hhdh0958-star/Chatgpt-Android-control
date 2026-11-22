package dev.nativeagent.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

final class WorkspaceTest {
    private interface Operation {
        void run() throws IOException;
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("native-agent-test-");
        Workspace workspace = new Workspace(root);
        String original = "print(\"Hello Android\")\n";
        String edited = "print(\"Hello Android Agent\")\n";
        check(workspace.writeFile("test.py", original).isEmpty());
        check(workspace.listFiles("").equals(List.of("test.py")));
        check(workspace.readFile("test.py").equals(original));

        String digest = Workspace.sha256(original);
        String backup = workspace.applyPatch("test.py", digest, "Hello Android", "Hello Android Agent");
        check(workspace.readFile("test.py").equals(edited));
        rejects(() -> workspace.applyPatch("test.py", digest, "Hello Android", "stale"));
        rejects(() -> workspace.writeFile("../escape.txt", "denied"));
        rejects(() -> workspace.readFile(".backups/" + backup));
        rejects(() -> workspace.writeFile("test.py", "a".repeat(262145)));
        check(workspace.readFile("test.py").equals(edited));
        check(workspace.diff("test.py", backup).equals(
            "--- a/test.py\n+++ b/test.py\n@@ -1,1 +1,1 @@\n-print(\"Hello Android\")\n+print(\"Hello Android Agent\")\n"
        ));
        String undoRestore = workspace.restore("test.py", backup);
        check(workspace.readFile("test.py").equals(original));
        check(workspace.restore("test.py", undoRestore).length() == 36);
        check(workspace.readFile("test.py").equals(edited));
        nestedProject(workspace, root);
        pathsAndSnapshots(workspace, root, backup);
        System.out.println("PASS create, list, read, patch, stale hash, size limit, nested project, diffs, backup binding, restore");
    }

    private static void nestedProject(Workspace workspace, Path root) throws IOException {
        String name = "src/pkg/module.py";
        String first = "zero\none\ntwo\nthree\nfour\nfive";
        String second = first + "\n";
        String preview = workspace.previewDiff(name, first);
        check(preview.startsWith("--- /dev/null\n+++ b/src/pkg/module.py\n@@ -0,0 +1,6 @@\n+zero\n"));
        check(preview.endsWith("+five\n\\ No newline at end of file\n"));
        check(!Files.exists(root.resolve("src")));
        check(workspace.writeFile(name, first).isEmpty());
        check(workspace.writeFile("src/pkg/other.py", "v = 1\n").isEmpty());
        check(workspace.listFiles("src/pkg").equals(List.of("module.py", "other.py")));
        String edit = workspace.previewDiff(name, second);
        check(edit.equals("--- a/src/pkg/module.py\n+++ b/src/pkg/module.py\n@@ -3,4 +3,4 @@\n"
            + " two\n three\n four\n-five\n\\ No newline at end of file\n+five\n"));
        String backup = workspace.writeFile(name, second);
        Workspace reopened = new Workspace(root);
        check(reopened.diff(name, backup).equals(edit));
        check(reopened.previewDiff(name, second).isEmpty());
        rejects(() -> reopened.restore("src/pkg/other.py", backup));
        rejects(() -> reopened.diff("src/pkg/other.py", backup));
        check(reopened.readFile("src/pkg/other.py").equals("v = 1\n"));
        reopened.restore(name, backup);
        check(reopened.readFile(name).equals(first));
        Path module = root.resolve(name);
        if (Files.getFileAttributeView(module, PosixFileAttributeView.class) != null) {
            var perms = Files.getPosixFilePermissions(module);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(module, perms);
            reopened.writeFile(name, second);
            check(Files.getPosixFilePermissions(module).contains(PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static void pathsAndSnapshots(Workspace workspace, Path root, String backup) throws IOException {
        rejects(() -> workspace.writeFile("src/new/../escape.py", "bad"));
        check(!Files.exists(root.resolve("src/new")));
        rejects(() -> workspace.writeFile("src/.backups/bad", "bad"));
        rejects(() -> workspace.writeFile(root.resolve("absolute.txt").toString(), "bad"));
        rejects(() -> workspace.writeFile("bad\u0000name", "bad"));
        rejects(() -> workspace.writeFile("bad\nname", "bad"));
        rejects(() -> workspace.restore("test.py", "../" + backup));
        rejects(() -> workspace.restore("test.py", "bad"));
        if (Files.exists(root.resolve(".BACKUPS"))) rejects(() -> workspace.listFiles(".BACKUPS"));
        byte[] binary = {(byte) 0xff, (byte) 0xfe};
        Path binaryPath = root.resolve("bin");
        Files.write(binaryPath, binary);
        rejects(() -> workspace.writeFile("bin", "replacement"));
        check(java.util.Arrays.equals(binary, Files.readAllBytes(binaryPath)));
        Path link = root.resolve("link");
        Path target = Files.createTempDirectory("native-agent-outside-");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            System.out.println("SKIP symlink fixture unavailable: " + unsupported.getMessage());
            return;
        }
        rejects(() -> workspace.writeFile("link/escape.txt", "bad"));
        check(!Files.exists(target.resolve("escape.txt")));
        check(!workspace.listFiles("").contains("link"));
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static void rejects(Operation operation) throws IOException {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected operation to fail");
    }
}

