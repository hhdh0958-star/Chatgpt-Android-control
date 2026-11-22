package dev.nativeagent.probe;

import dev.nativeagent.files.Workspace;
import java.nio.file.Files;
import org.json.JSONObject;

final class FileSessionTest {
    private static final String ID = "1234567890abcdef1234567890abcdef";

    public static void main(String[] args) throws Exception {
        var workspace = new Workspace(Files.createTempDirectory("native-agent-protocol-"));
        var session = new FileSession(workspace, ID);
        String write = call(1, "write_file", "test.py").put("content", "print(1)\n").toString();
        check(session.handle(write.replace(ID, "missing")) == null, "wrong request ID");
        check(session.handle(write.replace("\"step\":1", "\"step\":1,\"step\":1")) == null, "duplicate field");
        check(session.handle(write + "{}") == null, "trailing object");
        check(session.handle(write.replace("write_file", "shell")) == null, "unsupported tool");
        check(new JSONObject(session.handle(write)).getBoolean("ok"), "write succeeds");
        check(session.handle(write) == null, "duplicate step");
        var read = new JSONObject(session.handle(call(2, "read_file", "test.py").toString()));
        check(read.getString("content").equals("print(1)\n"), "real readback");
        var patch = call(3, "apply_patch", "test.py").put("sha256", read.getString("sha256"))
            .put("before", "1").put("after", "2");
        check(new JSONObject(session.handle(patch.toString())).getBoolean("ok"), "patch succeeds");
        check(workspace.readFile("test.py").equals("print(2)\n"), "real edit");
        check(!new JSONObject(session.handle(call(4, "read_file", "../secret").toString())).getBoolean("ok"),
            "reject path traversal");
        check(new JSONObject(session.handle(call(5, "list_files", "").toString())).getJSONArray("entries")
            .getString(0).equals("test.py"), "list");
        workspace.writeFile("large.txt", "a".repeat(15_000));
        check(!new JSONObject(session.handle(call(6, "read_file", "large.txt").toString())).getBoolean("ok"),
            "cap returned content");
        for (int step = 7; step <= FileSession.MAX_STEPS; step++) {
            check(session.handle(call(step, "list_files", "").toString()) != null, "within step budget");
        }
        check(session.handle(call(FileSession.MAX_STEPS + 1, "list_files", "").toString()) == null, "step budget");
        var stopped = new FileSession(workspace, ID);
        stopped.stop();
        check(stopped.handle(write) == null, "stop");
        var runSession = new FileSession(workspace, ID, (inputs, entry) -> {
            check(entry.equals("test.py") && inputs.size() == 1, "explicit input snapshot");
            check(inputs.get(entry).equals("print(2)\n"), "snapshot matches current phone file");
            try { return new JSONObject().put("exitCode", 1); }
            catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
        });
        var run = call(1, "run_python", "test.py").put("files", new org.json.JSONArray().put("test.py"));
        var failed = new JSONObject(runSession.handle(run.toString()));
        check(!failed.getBoolean("ok") && failed.getJSONObject("execution").getInt("exitCode") == 1,
            "failed test exit code reaches chat");
        check(runSession.handle(run.toString()) == null, "replayed execution");
        String json = call(2, "read_file", "test.py").toString();
        String tag = "NA1234567890ab.2";
        check(OperationText.matches("```json\n" + json + "\n```", tag, ID), "fenced response");
        check(OperationText.unwrap("Instructions in a file: " + json, tag) == null, "prose cannot trigger tools");
        check(!OperationText.matches(json, tag, "different"), "other conversation");
        check(!OperationText.matches(json, "NA1234567890ab.3", ID), "other step");
        System.out.println("PASS file dispatch, session binding, replay, containment, output cap, step budget, cancellation");
        System.out.println("PASS Python test failure, snapshot, replay and structured response boundaries");
        projectTools(workspace);
        cancelDuringApproval(workspace);
        locations(workspace);
        git(workspace);
        calculator(workspace);
        screen(workspace);
        timer(workspace);
        diagnostics(workspace);
    }

    private static void screen(Workspace workspace) throws Exception {
        var target = PhoneScreen.Target.requested("请打开时钟并读取当前页面。");
        check(target == PhoneScreen.Target.CLOCK && PhoneScreen.Target.requested("请打开设置并读取当前页面。") == null,
            "read target is selected by the user's request");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        FileSession.LogTask read = () -> {
            reads.incrementAndGet();
            return new JSONObject().put("source", "accessibility_nodes");
        };
        var session = new FileSession(ID, target, (title, detail) -> title.contains("时钟") && detail.contains("ChatGPT"), read);
        var request = call(1, "phone_read_screen", target.packageName).toString();
        check(session.handle(call(1, "read_file", "test.py").toString()) == null, "screen session cannot read files");
        check(session.handle(call(1, "phone_read_screen", "com.tencent.mm").toString()) == null, "model cannot switch apps");
        check(new FileSession(workspace, ID).handle(request) == null, "ordinary tasks cannot read the screen");
        check(!new JSONObject(new FileSession(ID, target, (title, detail) -> false, read).handle(request)).getBoolean("ok")
            && reads.get() == 0, "denial prevents launch and read");
        check(new JSONObject(session.handle(request)).getBoolean("ok") && reads.get() == 1, "approved read");
        check(session.handle(call(2, "phone_read_screen", target.packageName).toString()) == null, "single read per approval");
        var stopwatch = PhoneScreen.Target.requested("请打开时钟的秒表并读取当前页面。");
        var qq = PhoneScreen.Target.requested("帮我打开 QQ 搜索");
        check(PhoneScreen.Target.requested("帮我在 QQ 搜索：NativeAgentQA") == PhoneScreen.Target.QQ_SEARCH
            && "NativeAgentQA".equals(PhoneScreen.qqQuery("帮我在 QQ 搜索：NativeAgentQA"))
            && PhoneScreen.qqQuery("QQ搜索") == null && PhoneScreen.qqQuery("QQ搜索：") == null,
            "QQ query is bound to the original user sentence");
        check(qq == PhoneScreen.Target.QQ_SEARCH && PhoneScreen.Target.requested("你能打开QQ搜索吗") == null
            && PhoneScreen.Target.requested("打开QQ搜索并发送消息") == null, "only complete direct navigation requests match");
        var search = new FileSession(ID, qq, (title, detail) -> { throw new AssertionError("navigation asks for review"); }, read);
        check(new JSONObject(search.handle(call(1, qq.tool(), qq.packageName).toString())).getBoolean("ok"),
            "user-requested search navigation has no second confirmation");
        var tab = new FileSession(ID, stopwatch, (title, detail) -> title.contains("秒表") && detail.contains("标签页"), read);
        check(tab.handle(request) == null, "a tab task accepts only the approved operation");
        check(session.handle(call(2, "phone_open_tab", target.packageName).toString()) == null, "read cannot escalate to click");
        check(new JSONObject(tab.handle(call(1, stopwatch.tool(), stopwatch.packageName).toString())).getBoolean("ok")
            && reads.get() == 3, "approved tab operation");
        var qqOpen = PhoneScreen.Target.requested("打开 QQ");
        check(qqOpen == PhoneScreen.Target.QQ_OPEN && PhoneScreen.Target.requested("打开 QQ 并发送消息") == null,
            "plain QQ launch is distinct from search and messaging");
        var launch = new FileSession(ID, qqOpen, (title, detail) -> false, read);
        check(launch.handle(call(1, qq.tool(), qq.packageName).toString()) == null, "launch cannot be replaced with search");
        check(new JSONObject(launch.handle(call(1, qqOpen.tool(), qqOpen.packageName).toString())).getBoolean("ok")
            && reads.get() == 4, "plain QQ launch is dispatched once");
        System.out.println("PASS screen task app binding, confirmation, scope isolation and single read");
    }

    private static void cancelDuringApproval(Workspace workspace) throws Exception {
        var active = new java.util.concurrent.atomic.AtomicReference<FileSession>();
        String path = "cancelled-settings.json";
        workspace.writeFile(path, "{\"before\":true}");
        var session = new FileSession(workspace, ID, null, (title, detail) -> {
            active.get().stop();
            return true;
        });
        active.set(session);
        var change = call(1, "write_file", path).put("content", "{\"after\":true}");
        var result = new JSONObject(session.handle(change.toString()));
        check(!result.getBoolean("ok") && workspace.readFile(path).equals("{\"before\":true}"),
            "cancelled approval does not change the file");
        check(session.handle(call(2, "read_file", path).toString()) == null, "stopped session stays closed");
        System.out.println("PASS cancellation during confirmation prevents write and disables further tools");
    }

    private static void diagnostics(Workspace workspace) throws Exception {
        var input = new java.io.BufferedReader(new java.io.StringReader(
            "11-21 20:00:00.123 72 80 I NativeAgentProbe: tool=read_file step=1 ok=true sha256=-\n"
            + "11-21 20:00:00.124 72 80 I NativeAgentProbe: tool=phone_timer step=2 ok=false sha256=- errorCode=timer_unverified\n"
            + "11-21 20:00:00.125 72 80 I NativeAgentProbe: arbitrary user text\n"
            + "11-21 20:00:00.126 71 80 I NativeAgentProbe: chat message submitted\n"
            + "11-21 20:00:00.127 72 80 I OtherApp: chat message submitted\n"
            + "11-21 20:00:00.128 72 80 I NativeAgentProbe: chat message submitted\n"));
        var read = LogDiagnostics.summarize(input, 72);
        check(read.getInt("successfulTools") == 1 && read.getInt("failedTools") == 1
            && read.getJSONArray("recent").length() == 3, "only same-process structured logs selected");
        var session = new FileSession(ID, () -> read);
        String request = call(1, "diagnose_logcat", "native-agent").toString();
        check(new FileSession(workspace, ID).handle(request) == null, "ordinary sessions cannot collect logs");
        check(session.handle(call(1, "read_file", "test.py").toString()) == null, "diagnostics cannot access files");
        check(new JSONObject(session.handle(request)).getJSONObject("diagnostics").getInt("failedTools") == 1,
            "filtered log count returned to caller");
        check(session.handle(call(2, "diagnose_logcat", "native-agent").toString()) == null, "one sample per task");
        System.out.println("PASS Logcat PID/tag and metadata filtering, scoped dispatch and one-sample limit");
    }

    private static void timer(Workspace workspace) throws Exception {
        int[] called = {0};
        FileSession.TimerTask clock = seconds -> {
            check(seconds == 27, "timer duration preserved");
            called[0]++;
            return new JSONObject().put("verifiedRunning", false);
        };
        var session = new FileSession(workspace, ID, null, (title, detail) -> detail.contains("27 秒"), null,
            null, false, false, clock);
        String call = call(1,"phone_timer","clock").put("seconds","27").toString();
        check(new FileSession(workspace, ID).handle(call) == null, "file session cannot start timer");
        var denied = new FileSession(workspace, ID, null, (title, detail) -> false, null, null, false, false, clock);
        check(!new JSONObject(denied.handle(call)).getBoolean("ok") && called[0] == 0, "declined timer");
        var result = new JSONObject(session.handle(call));
        check(!result.getBoolean("ok") && result.getString("errorCode").equals("timer_unverified"), "unverified is not success");
        check(session.handle(call(2,"phone_timer","clock").put("seconds","27").toString()) == null
            && called[0] == 1, "no duplicate timer");
        check(TimerInput.displayedSeconds("00:01:12") == 72, "read clock duration");
        check(TimerInput.displayedSeconds("26 秒") == 26 && TimerInput.displayedSeconds("1 分钟 12 秒") == 72,
            "read ColorOS timer content description");
        check(TimerInput.displayedSeconds("23:15 响铃") == -1, "reject clock alert label");
        System.out.println("PASS timer approval, duration, unverified receipt and duplicate prevention");
    }

    private static void calculator(Workspace workspace) throws Exception {
        check(CalculatorInput.normalizedFormula("2,190 × 73 − 2").equals("2190*73-2"), "normalize ColorOS formula");
        var called = new java.util.concurrent.atomic.AtomicInteger();
        FileSession.CalculatorTask calc = expression -> {
            called.incrementAndGet();
            check(expression.equals("219*73"), "approved expression preserved");
            return new JSONObject().put("display", "15987");
        };
        var allowed = new FileSession(workspace, ID, null, (title, detail) -> detail.contains("219*73"), null, calc);
        var requested = call(1,"phone_calculate","calculator").put("expression","219*73").toString();
        check(new FileSession(workspace, ID).handle(requested) == null, "ordinary file task cannot launch calculator");
        check(allowed.handle(call(1,"read_file","sample.txt").toString()) == null, "calculator task has no file tools");
        var denied = new FileSession(workspace, ID, null, (title, detail) -> false, null, calc);
        check(!new JSONObject(denied.handle(requested)).getBoolean("ok") && called.get() == 0, "denial stops action");
        var invalid = new FileSession(workspace, ID, null, (title, detail) -> true, null, calc);
        check(!new JSONObject(invalid.handle(requested.replace("219*73", "open settings"))).getBoolean("ok") && called.get() == 0,
            "invalid expression cannot reach accessibility actions");
        var result = new JSONObject(allowed.handle(requested));
        check(result.getJSONObject("phone").getString("display").equals("15987") && called.get() == 1, "actual callback result");
        check(allowed.handle(requested) == null && called.get() == 1, "no replay");
        var captureApproval = new FileSession(workspace, ID, null, (title, detail) -> {
            check(detail.contains("只截取计算器窗口"), "capture disclosed in confirmation");
            return true;
        }, null, calc, true);
        check(new JSONObject(captureApproval.handle(requested)).getBoolean("ok") && called.get() == 2,
            "calculator capture task explicitly reviewed");
        String readRequest = call(1, "phone_read_calculator", "calculator").toString();
        var readTask = new FileSession(workspace, ID, null, (title, detail) -> title.equals("读取系统计算器"), null,
            expression -> {
                check(expression == null, "read has no action expression");
                return new JSONObject().put("source", "accessibility_nodes").put("display", "6,023");
            }, false, true);
        check(readTask.handle(requested) == null, "read task cannot click calculator buttons");
        var read = new JSONObject(readTask.handle(readRequest)).getJSONObject("phone");
        check(read.getString("source").equals("accessibility_nodes") && !read.has("capture"), "read returns text only");
        System.out.println("PASS calculator task isolation, approval, validation and single execution");
    }

    private static void git(Workspace workspace) throws Exception {
        workspace.writeFile("git-fixture/math.py", "value = 1\n");
        var storage = Files.createTempDirectory("native-agent-git-");
        var git = new ProjectGit(workspace, storage);
        String project = "git-fixture";
        var session = new FileSession(workspace, ID, null,
            (title, detail) -> detail.contains("math.py") && detail.contains("+value = "), git);
        var names = new org.json.JSONArray().put("math.py");
        check(new JSONObject(session.handle(call(1,"git_init",project).toString())).getBoolean("ok"), "init repository");
        var status = new JSONObject(session.handle(call(2,"git_status",project).put("files",names).toString()));
        check(status.getJSONObject("repository").getJSONArray("files").getJSONObject(0).getString("state").equals("untracked"), "first status");
        var saved = new JSONObject(session.handle(call(3,"git_commit",project).put("files",names).put("message","Initial version").toString()));
        String hash = saved.getJSONObject("repository").getString("commit");
        check(saved.getBoolean("createdCommit") && hash.matches("[a-f0-9]{40}"), "real commit receipt");
        workspace.writeFile("git-fixture/math.py", "value = 2\n");
        var modified = new JSONObject(session.handle(call(4,"git_status",project).put("files",names).toString()));
        check(modified.getJSONObject("repository").getJSONArray("files").getJSONObject(0).getString("state").equals("modified"), "modified status");
        var preview = git.preview(project, git.snapshot(project, "math.py"));
        check(preview.head().name().equals(hash) && preview.diff().contains("-value = 1\n+value = 2"), "preview has exact base");
        var second = new JSONObject(session.handle(call(5,"git_commit",project).put("files",names).put("message","Update value").toString()));
        check(second.getBoolean("ok"), "second commit");
        var denied = new JSONObject(session.handle(call(6,"git_status",project)
            .put("files",new org.json.JSONArray().put("../outside.py")).toString()));
        check(!denied.getBoolean("ok"), "Git file paths stay under project");
        var history = new ProjectGit(workspace, storage).log(project).getJSONArray("commits");
        check(history.length() == 2 && history.getJSONObject(1).getString("id").equals(hash), "persistent commit history");
        try {
            git.commit(project, git.snapshot(project, "math.py"), "Stale review", preview.head());
            throw new AssertionError("Stale Git review allowed");
        } catch (java.io.IOException changed) {
            check(changed.getMessage().contains("Branch changed"), "stale Git base rejected");
        }
        var diff = new JSONObject(session.handle(call(7,"git_diff",project).put("files",names).toString()));
        check(diff.getJSONObject("repository").getString("diff").isEmpty(), "no diff after commit");
        var duplicate = new JSONObject(session.handle(call(8,"git_commit",project).put("files",names)
            .put("message","Retry").toString()));
        check(!duplicate.getBoolean("createdCommit") && !duplicate.getBoolean("ok")
            && duplicate.getString("errorCode").equals("git_no_changes")
            && duplicate.getString("head").equals(second.getJSONObject("repository").getString("commit")),
            "retry distinguishes the existing head from a new commit");
        workspace.writeFile("git-fixture/math.py", "value = 3\n");
        var deniedSession = new FileSession(workspace, ID, null, (title, detail) -> false, git);
        var request = call(1,"git_commit",project).put("files",names).put("message","Rejected or stale").toString();
        var refusal = new JSONObject(deniedSession.handle(request));
        check(!refusal.getBoolean("createdCommit") && refusal.getString("errorCode").equals("approval_denied"),
            "Git refusal leaves history alone");
        var staleSession = new FileSession(workspace, ID, null, (title, detail) -> {
            try { workspace.writeFile("git-fixture/math.py", "value = 4\n"); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
            return true;
        }, git);
        var stale = new JSONObject(staleSession.handle(request));
        check(!stale.getBoolean("createdCommit") && stale.getString("errorCode").equals("git_files_changed"),
            "Git source changed after review");
        check(git.log(project).getJSONArray("commits").length() == 2, "neither rejected commit is recorded");
        var secondWorkspace = new ProjectGit(workspace, Files.createTempDirectory("native-agent-git-separate-"));
        secondWorkspace.init(project);
        check(secondWorkspace.log(project).getJSONArray("commits").length() == 0
            && git.log(project).getJSONArray("commits").length() == 2,
            "same relative project in separate workspaces keeps separate Git history");
        System.out.println("PASS Git init, status, diff, approved commits, stale/refused review, history and directory boundary");
        System.out.println("Git test repository " + storage.resolve(Workspace.sha256(project)));
    }

    private static void locations(Workspace workspace) throws Exception {
        workspace.writeFile("location/source.txt", "original\n");
        String digest = Workspace.sha256("original\n");
        var session = new FileSession(workspace, ID, null, (title, detail) -> {
            check(detail.contains("location/"), "approval identifies location");
            if (title.startsWith("删除") || title.startsWith("移动")) check(detail.contains("+++ /dev/null"), "removal diff");
            return true;
        });
        var copied = new JSONObject(session.handle(call(1, "copy_file", "location/source.txt")
            .put("destination", "location/copy.txt").put("sha256", digest).toString()));
        check(copied.getBoolean("ok") && workspace.readFile("location/copy.txt").equals("original\n"), "copy");
        var collision = new JSONObject(session.handle(call(2, "move_file", "location/source.txt")
            .put("destination", "location/copy.txt").put("sha256", digest).toString()));
        check(!collision.getBoolean("ok") && workspace.readFile("location/source.txt").equals("original\n"), "no overwrite");
        var moved = new JSONObject(session.handle(call(3, "move_file", "location/source.txt")
            .put("destination", "location/moved.txt").put("sha256", digest).toString()));
        check(moved.getBoolean("ok") && workspace.readOptional("location/source.txt") == null, "move");
        var removed = new JSONObject(session.handle(call(4, "delete_file", "location/moved.txt")
            .put("sha256", digest).toString()));
        check(removed.getBoolean("ok") && workspace.readOptional("location/moved.txt") == null, "single file delete");
        var diff = new JSONObject(session.handle(call(5, "backup_diff", "location/moved.txt")
            .put("backup", removed.getString("backup")).toString()));
        check(diff.getBoolean("ok") && diff.getString("diff").contains("+++ /dev/null"), "diff after deletion");
        var restored = new JSONObject(session.handle(call(6, "restore_file", "location/moved.txt")
            .put("backup", removed.getString("backup")).toString()));
        check(restored.getBoolean("ok") && workspace.readFile("location/moved.txt").equals("original\n"), "recover deleted file");
        var refused = new FileSession(workspace, ID);
        check(!new JSONObject(refused.handle(call(1,"delete_file","location/moved.txt").put("sha256",digest).toString())).getBoolean("ok"),
            "requires explicit approval");
        check(workspace.readFile("location/moved.txt").equals("original\n"), "denial does not mutate");
        check(!new JSONObject(session.handle(call(7,"delete_file","location").put("sha256",digest).toString())).getBoolean("ok"),
            "directories cannot be removed");
        System.out.println("PASS copy, move without overwrite, single-file delete, restore and denial");
    }

    private static void projectTools(Workspace workspace) throws Exception {
        workspace.writeFile("code/math.py", "value = 2\n");
        String saved = workspace.writeFile("code/math.py", "value = 3\n");
        var session = new FileSession(workspace, ID, null, (title, detail) ->
            title.contains("恢复") && detail.contains("-value = 3") && detail.contains("+value = 2"));
        JSONObject hits = new JSONObject(session.handle(call(1, "search_files", "code").put("query", "value").toString()));
        check(hits.getJSONObject("search").getJSONArray("matches").getJSONObject(0).getInt("line") == 1, "line search");
        var history = new JSONObject(session.handle(call(2, "list_backups", "code/math.py").toString()));
        check(history.getJSONArray("backups").getString(0).equals(saved), "bound backup is discoverable");
        var diff = new JSONObject(session.handle(call(3, "backup_diff", "code/math.py").put("backup", saved).toString()));
        check(diff.getString("diff").contains("-value = 2"), "backup diff");
        var restored = new JSONObject(session.handle(call(4, "restore_file", "code/math.py").put("backup", saved).toString()));
        check(restored.getBoolean("ok") && workspace.readFile("code/math.py").equals("value = 2\n"), "approved restore");
        var denied = new JSONObject(session.handle(call(5, "write_file", "settings.json").put("content", "{}").toString()));
        check(!denied.getBoolean("ok") && workspace.readOptional("settings.json") == null, "sensitive create denied");
        var other = new JSONObject(session.handle(call(6,"restore_file","code/other.py").put("backup", saved).toString()));
        check(!other.getBoolean("ok") && workspace.readOptional("code/other.py") == null, "foreign backup denied");
        workspace.writeFile("settings.json", "old");
        var racing = new FileSession(workspace, ID, null, (title, detail) -> {
            try { workspace.writeFile("settings.json", "external edit"); }
            catch (java.io.IOException e) { throw new AssertionError(e); }
            return true;
        });
        var raced = new JSONObject(racing.handle(call(1,"write_file","settings.json").put("content","new").toString()));
        check(!raced.getBoolean("ok") && workspace.readFile("settings.json").equals("external edit"), "changed since review");
        // Pure validation only. No storage method is invoked for these synthetic destinations.
        for (String path : new String[]{"/system/build.prop","/vendor/file","/dev/block/bootdevice",
            "/proc/self/fd/3", "../system/file", "folder/../../boot", "file:///etc/fstab", ".git/hooks/post-checkout"}) {
            try { ProjectTools.validatePath(path, false); throw new AssertionError(path); }
            catch (java.io.IOException deniedPath) { /* expected */ }
        }
        check(new FileSession(workspace,ID).handle(call(1,"root_shell","").toString()) == null, "no native shell");
        System.out.println("PASS literal search, backup list/diff/restore, confirmation denial, stale approval, path boundary");
    }

    private static JSONObject call(int step, String tool, String path) throws Exception {
        return new JSONObject().put("id", ID).put("step", step).put("tool", tool).put("path", path);
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
