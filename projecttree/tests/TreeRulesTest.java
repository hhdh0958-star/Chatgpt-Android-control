package dev.nativeagent.projecttree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host regression checks for path and patch processing shared with the Android adapter. */
public final class TreeRulesTest {
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(TreeFileStore.segments("项目/src/test.py"), new String[]{"项目", "src", "test.py"}));
        for (String bad : new String[]{"", "/file", "../file", "project//file", "project/.", "project/..",
                "primary:file", "project\\file", "file\n", "file/", "a".repeat(256), "a/".repeat(32) + "last"}) {
            rejects(() -> TreeFileStore.segments(bad));
        }
        check(new String(TreeFileStore.patched("print('你好')\n", "你好", "Android"), StandardCharsets.UTF_8)
                .equals("print('Android')\n"));
        rejects(() -> TreeFileStore.patched("aaa", "aa", "X"));
        rejects(() -> TreeFileStore.patched("hello", "", "x"));
        rejects(() -> TreeFileStore.patched("hello", "missing", "x"));
        rejects(() -> TreeFileStore.patched("hello", "hello", "\uD800"));
        rejects(() -> TreeFileStore.patched("hello", "hello", "中".repeat(90_000)));
        System.out.println("PASS local tree paths, nested names, unique patch, UTF-8 and byte caps");
    }

    private interface Attempt { void run() throws Exception; }

    private static void rejects(Attempt call) throws Exception {
        try {
            call.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    private static void check(boolean passed) {
        if (!passed) throw new AssertionError();
    }
}
