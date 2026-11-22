package dev.nativeagent.probe;

import android.util.JsonReader;
import android.util.JsonToken;
import dev.nativeagent.files.Workspace;
import dev.nativeagent.files.ProjectFiles;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FileSession {
    static final int MAX_STEPS = 12;
    private static final int MAX_INPUT_CHARS = 24_000;
    private static final int MAX_RESULT_CHARS = 12_000;
    private final ProjectFiles workspace;
    private final String requestId;
    interface PythonTask {
        JSONObject run(Map<String, String> files, String entry) throws IOException, InterruptedException;
    }
    private final PythonTask python;
    private final ProjectTools.Review review;
    private final ProjectGit git;
    interface CalculatorTask {
        JSONObject run(String expression) throws IOException, InterruptedException, JSONException;
    }
    private final CalculatorTask calculator;
    private final boolean captureCalculator;
    private final boolean readCalculator;
    interface TimerTask {
        JSONObject run(int seconds) throws IOException, InterruptedException, JSONException;
    }
    private final TimerTask timer;
    interface LogTask { JSONObject run() throws IOException, InterruptedException, JSONException; }
    private final LogTask logs;
    private final PhoneScreen.Target screenTarget;
    private final LogTask screen;
    private final long started = System.nanoTime();
    private int nextStep = 1;
    private volatile boolean stopped;

    FileSession(ProjectFiles workspace, String requestId) {
        this(workspace, requestId, null);
    }

    FileSession(ProjectFiles workspace, String requestId, PythonTask python) {
        this(workspace, requestId, python, (title, detail) -> false);
    }

    FileSession(ProjectFiles workspace, String requestId, PythonTask python, ProjectTools.Review review) {
        this(workspace, requestId, python, review, null);
    }

    FileSession(ProjectFiles workspace, String requestId, PythonTask python, ProjectTools.Review review, ProjectGit git) {
        this(workspace, requestId, python, review, git, null);
    }

    FileSession(ProjectFiles workspace, String requestId, PythonTask python, ProjectTools.Review review, ProjectGit git,
            CalculatorTask calculator) {
        this(workspace, requestId, python, review, git, calculator, false);
    }

    FileSession(ProjectFiles workspace, String requestId, PythonTask python, ProjectTools.Review review, ProjectGit git,
            CalculatorTask calculator, boolean captureCalculator) {
        this(workspace, requestId, python, review, git, calculator, captureCalculator, false);
    }

    FileSession(ProjectFiles workspace, String requestId, PythonTask python, ProjectTools.Review review, ProjectGit git,
            CalculatorTask calculator, boolean captureCalculator, boolean readCalculator) {
        this(workspace, requestId, python, review, git, calculator, captureCalculator, readCalculator, null);
    }

    FileSession(ProjectFiles workspace, String requestId, PythonTask python, ProjectTools.Review review, ProjectGit git,
            CalculatorTask calculator, boolean captureCalculator, boolean readCalculator, TimerTask timer) {
        this(workspace, requestId, python, review, git, calculator, captureCalculator, readCalculator, timer, null, null, null);
    }

    FileSession(String requestId, LogTask logs) {
        this(null, requestId, null, (title, detail) -> false, null, null, false, false, null, logs, null, null);
    }

    FileSession(String requestId, PhoneScreen.Target target, ProjectTools.Review review, LogTask screen) {
        this(null, requestId, null, review, null, null, false, false, null, null, target, screen);
    }

    private FileSession(ProjectFiles workspace, String requestId, PythonTask python, ProjectTools.Review review, ProjectGit git,
            CalculatorTask calculator, boolean captureCalculator, boolean readCalculator, TimerTask timer, LogTask logs,
            PhoneScreen.Target screenTarget, LogTask screen) {
        if (!requestId.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid request ID");
        this.workspace = workspace;
        this.requestId = requestId;
        this.python = python;
        this.review = review;
        this.git = git;
        this.calculator = calculator;
        this.captureCalculator = captureCalculator;
        this.readCalculator = readCalculator;
        this.timer = timer;
        this.logs = logs;
        this.screenTarget = screenTarget;
        this.screen = screen;
    }

    void stop() {
        stopped = true;
    }

    synchronized String handle(String message) throws JSONException {
        if (expired() || nextStep > MAX_STEPS) {
            return null;
        }
        Map<String, String> call;
        try {
            call = readCall(message);
        } catch (IOException | IllegalStateException invalid) {
            return null;
        }
        if (!requestId.equals(call.get("id")) || !Integer.toString(nextStep).equals(call.get("step"))) {
            return null;
        }
        String tool = call.getOrDefault("tool", "");
        Set<String> fields = switch (tool) {
            case "list_files", "read_file", "list_backups" -> Set.of("id", "step", "tool", "path");
            case "search_files" -> Set.of("id", "step", "tool", "path", "query");
            case "write_file", "preview_diff" -> Set.of("id", "step", "tool", "path", "content");
            case "restore_file", "backup_diff" -> Set.of("id", "step", "tool", "path", "backup");
            case "apply_patch" -> Set.of("id", "step", "tool", "path", "sha256", "before", "after");
            case "copy_file", "move_file" -> Set.of("id", "step", "tool", "path", "destination", "sha256");
            case "delete_file" -> Set.of("id", "step", "tool", "path", "sha256");
            case "git_init", "git_log" -> Set.of("id", "step", "tool", "path");
            case "git_status", "git_diff" -> Set.of("id", "step", "tool", "path", "files");
            case "git_commit" -> Set.of("id", "step", "tool", "path", "files", "message");
            case "phone_calculate" -> Set.of("id", "step", "tool", "path", "expression");
            case "phone_read_calculator" -> Set.of("id", "step", "tool", "path");
            case "phone_timer" -> Set.of("id", "step", "tool", "path", "seconds");
            case "diagnose_logcat", "phone_read_screen", "phone_open_tab", "phone_open_search", "phone_open_app" ->
                Set.of("id", "step", "tool", "path");
            case "run_python", "run_shell", "run_javascript" -> Set.of("id", "step", "tool", "path", "files");
            default -> Set.of();
        };
        if (!call.keySet().equals(fields)) return null;
        if ((logs != null) != tool.equals("diagnose_logcat") || (logs != null && nextStep != 1)) return null;
        if ((timer != null) != tool.equals("phone_timer") || (timer != null && nextStep != 1)) return null;
        boolean screenTool = tool.equals("phone_read_screen") || tool.equals("phone_open_tab")
            || tool.equals("phone_open_search") || tool.equals("phone_open_app");
        if ((screen != null) != screenTool || (screenTool && (nextStep != 1 || !screenTarget.tool().equals(tool)
            || !screenTarget.packageName.equals(call.get("path"))))) return null;
        boolean phoneTool = tool.equals("phone_calculate") || tool.equals("phone_read_calculator");
        if ((calculator != null) != phoneTool || (phoneTool && readCalculator != tool.equals("phone_read_calculator"))) return null;

        JSONObject result = new JSONObject().put("id", requestId).put("step", nextStep++).put("tool", tool);
        if (tool.equals("git_commit")) result.put("createdCommit", false);
        try {
            String path = call.get("path");
            ProjectTools.validatePath(path, tool.equals("list_files") || tool.equals("search_files") || tool.startsWith("git_"));
            switch (tool) {
                case "phone_open_search", "phone_open_app" -> {
                    result.put("phone", screen.run());
                }
                case "phone_open_tab" -> {
                    approve("打开时钟的" + screenTarget.label,
                        "切换到时钟的" + screenTarget.label + "标签页，并把该页可见控件文字发回当前 ChatGPT。");
                    result.put("phone", screen.run());
                }
                case "phone_read_screen" -> {
                    approve("读取" + screenTarget.label + "当前页面",
                        "打开 " + screenTarget.label + "，将当前页面至多 24 条可见控件文字发回 ChatGPT。"
                            + "其中可能包含联系人名称或消息预览。请确认你愿意分享这些文字。");
                    result.put("phone", screen.run());
                }
                case "diagnose_logcat" -> {
                    if (!path.equals("native-agent")) throw new IOException("Unexpected log scope");
                    result.put("diagnostics", logs.run());
                }
                case "phone_timer" -> {
                    if (!path.equals("clock")) throw new IOException("Unexpected app");
                    int seconds = TimerInput.seconds(call.get("seconds"));
                    approve("启动手机倒计时", "在系统时钟启动一个 " + seconds + " 秒的倒计时。计时结束时按手机设置提醒。");
                    JSONObject timerResult = timer.run(seconds);
                    result.put("phone", timerResult);
                    if (!timerResult.getBoolean("verifiedRunning")) {
                        result.put("errorCode", "timer_unverified")
                            .put("error", "Timer request was issued. Check the Clock app before retrying.");
                    }
                }
                case "phone_read_calculator" -> {
                    if (!path.equals("calculator")) throw new IOException("Unexpected app");
                    approve("读取系统计算器", "打开计算器，读取当前算式和结果的文字，并发回当前聊天。");
                    result.put("phone", calculator.run(null));
                }
                case "phone_calculate" -> {
                    if (!path.equals("calculator")) throw new IOException("Unexpected app");
                    String expression = call.get("expression");
                    CalculatorInput.validate(expression);
                    approve("使用系统计算器", "打开系统计算器并清空当前算式，输入 " + expression
                        + "，按等号，将读到的结果发回当前聊天。"
                        + (captureCalculator ? "\n\n只截取计算器窗口，图片存到手机本地。聊天收到尺寸和校验值。" : ""));
                    result.put("phone", calculator.run(expression));
                }
                case "list_files" -> result.put("entries", new JSONArray(workspace.listFiles(path)));
                case "search_files" -> result.put("search", ProjectTools.search(workspace, path, call.get("query"), this::expired));
                case "list_backups" -> result.put("backups", new JSONArray(workspace.listBackups(path)));
                case "read_file" -> {
                    approveRead(path);
                    String content = workspace.readFile(path);
                    result.put("content", content).put("sha256", Workspace.sha256(content));
                }
                case "preview_diff" -> {
                    approveRead(path);
                    String old = workspace.readOptional(path);
                    result.put("diff", Workspace.unifiedDiff(path, old, call.get("content")));
                }
                case "backup_diff" -> {
                    approveRead(path);
                    String saved = workspace.readBackup(path, call.get("backup"));
                    var current = workspace.readForRestore(path);
                    if (current.unreadable()) {
                        result.put("currentUnreadable", true).put("currentSha256", current.sha256())
                            .put("backupPreview", Workspace.unifiedDiff(path, null, saved));
                    } else {
                        result.put("diff", Workspace.unifiedDiff(path, saved, current.text()));
                    }
                }
                case "restore_file" -> {
                    String backupId = call.get("backup");
                    String proposed = workspace.readBackup(path, backupId);
                    var baseline = workspace.readForRestore(path);
                    String detail = baseline.unreadable()
                        ? "当前文件含无效 UTF-8，无法显示文字差异。当前字节将另存为备份。\n"
                            + "当前 SHA-256: " + baseline.sha256() + "\n\n备份内容预览\n"
                            + Workspace.unifiedDiff(path, null, proposed)
                        : Workspace.unifiedDiff(path, baseline.text(), proposed);
                    approve("恢复文件备份", path + "\n备份 ID: " + backupId + "\n\n" + detail);
                    if (expired()) throw new InterruptedException();
                    String backup = workspace.restoreChecked(path, backupId, baseline.sha256());
                    result.put("backup", backup).put("sha256", Workspace.sha256(workspace.readFile(path)));
                    if (baseline.unreadable()) result.put("replacedUnreadableBytes", true);
                }
                case "write_file", "apply_patch" -> {
                    String old = workspace.readOptional(path);
                    String proposed = tool.equals("apply_patch")
                        ? ProjectTools.patch(old, call.get("sha256"), call.get("before"), call.get("after"))
                        : call.get("content");
                    if (ProjectTools.needsReview(path)) {
                        String diff = Workspace.unifiedDiff(path, old, proposed);
                        approve("修改项目配置或敏感文件", path + "\n\n" + diff);
                    }
                    if (expired()) throw new InterruptedException();
                    String backup = workspace.writeChecked(path, proposed, old == null ? "" : Workspace.sha256(old));
                    result.put("backup", backup).put("sha256", Workspace.sha256(workspace.readFile(path)));
                }
                case "copy_file", "move_file", "delete_file" -> changeLocation(tool, path, call, result);
                case "git_init", "git_status", "git_diff", "git_commit", "git_log" -> {
                    if (git == null) throw new IOException("Git is unavailable in this workspace");
                    JSONObject operation = switch (tool) {
                        case "git_init" -> git.init(path);
                        case "git_log" -> git.log(path);
                        case "git_status" -> git.status(path, git.snapshot(path, call.get("files")));
                        case "git_diff" -> git.preview(path, git.snapshot(path, call.get("files"))).asJson();
                        default -> {
                            String names = call.get("files"), commitMessage = call.get("message");
                            ProjectGit.validateMessage(commitMessage);
                            var snapshot = git.snapshot(path, names);
                            var preview = git.preview(path, snapshot);
                            if (preview.diff().isEmpty()) {
                                result.put("head", preview.head() == null ? JSONObject.NULL : preview.head().name());
                                throw new ProjectGit.Rejected("git_no_changes", "Selected files match the current Git commit");
                            }
                            approve("创建本地 Git 提交", (path.isEmpty() ? "/" : path) + "\n" + commitMessage
                                + "\n\n" + preview.diff());
                            if (!snapshot.equals(git.snapshot(path, names))) {
                                throw new ProjectGit.Rejected("git_files_changed", "Selected files changed after review");
                            }
                            if (expired()) throw new InterruptedException();
                            yield git.commit(path, snapshot, commitMessage, preview.head());
                        }
                    };
                    result.put("repository", operation);
                    if (tool.equals("git_commit")) result.put("createdCommit", true);
                }
                case "run_python", "run_shell", "run_javascript" -> {
                    if (python == null) throw new IOException("Runtime unavailable");
                    String entry = call.get("path");
                    boolean supported = switch (tool) {
                        case "run_python" -> entry.endsWith(".py");
                        case "run_shell" -> entry.endsWith(".sh");
                        default -> entry.endsWith(".mjs") || entry.endsWith(".js");
                    };
                    if (!supported) throw new IOException("Entry extension does not match requested runtime");
                    Map<String, String> files = new LinkedHashMap<>();
                    String[] names = call.get("files").split("\n", -1);
                    if (names.length > 32) throw new IOException("Too many runtime inputs");
                    int totalBytes = 0;
                    for (String input : names) {
                        ProjectTools.validatePath(input, false);
                        if (input.length() > 240 || List.of(input.split("/", -1)).stream().anyMatch(part -> part.startsWith("."))) {
                            throw new IOException("Invalid runtime input path");
                        }
                        approveRead(input);
                        String source = workspace.readFile(input);
                        totalBytes += source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        if (totalBytes > 96 * 1024 || files.putIfAbsent(input, source) != null) {
                            throw new IOException("Runtime inputs exceed limit or repeat");
                        }
                    }
                    if (!files.containsKey(entry)) throw new IOException("Entry missing from snapshot");
                    if (stopped) return null;
                    JSONObject execution = python.run(files, entry);
                    result.put("execution", execution);
                }
                default -> throw new IllegalStateException("Unknown validated tool");
            }
            result.put("ok", !result.has("errorCode")
                && (!result.has("execution") || result.getJSONObject("execution").getInt("exitCode") == 0));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopped = true;
            return null;
        } catch (ReviewDenied denied) {
            result.put("ok", false).put("errorCode", "approval_denied").put("error", "User approval was denied or expired");
        } catch (ProjectGit.Rejected denied) {
            result.put("ok", false).put("errorCode", denied.code).put("error", denied.getMessage());
        } catch (IOException | IllegalArgumentException | SecurityException denied) {
            result.put("ok", false);
            if (screenTool && denied instanceof IOException) {
                result.put("errorCode", "phone_action_failed").put("error", denied.getMessage());
            } else {
                result.put("error", "File operation rejected or failed");
            }
        }
        String serialized = result.toString();
        if (serialized.length() <= MAX_RESULT_CHARS) return serialized;
        return new JSONObject().put("id", requestId).put("step", nextStep - 1).put("tool", tool)
            .put("ok", false).put("error", "Result exceeds the chat limit").toString();
    }

    private void changeLocation(String tool, String source, Map<String, String> call, JSONObject result)
            throws IOException, InterruptedException, JSONException {
        boolean copy = tool.equals("copy_file"), delete = tool.equals("delete_file");
        if (!copy && !(workspace instanceof Workspace)) {
            result.put("unsupported", "Moving and deleting require the app-private workspace. Copy is available here.");
            throw new IOException("Selected provider cannot guarantee single-file removal");
        }
        String destination = call.get("destination");
        if (!delete) ProjectTools.validatePath(destination, false);
        String before = workspace.readFile(source);
        String hash = Workspace.sha256(before);
        if (!hash.equals(call.get("sha256"))) throw new IOException("File changed");
        if (copy) {
            approveRead(source);
            if (ProjectTools.needsReview(destination)) {
                approve("复制到项目配置或敏感路径", destination + "\n\n" + Workspace.unifiedDiff(destination, null, before));
            }
            if (expired()) throw new InterruptedException();
            if (!hash.equals(Workspace.sha256(workspace.readFile(source)))) throw new IOException("Source changed");
            workspace.writeChecked(destination, before, "");
            result.put("destination", destination).put("sha256", Workspace.sha256(workspace.readFile(destination)));
            return;
        }
        approve(delete ? "删除工作区文件" : "移动工作区文件",
            source + (delete ? "" : " → " + destination) + "\n\n" + Workspace.unifiedDiff(source, before, null));
        if (expired()) throw new InterruptedException();
        Workspace privateFiles = (Workspace) workspace;
        String backup = delete ? privateFiles.deleteChecked(source, hash)
            : privateFiles.moveChecked(source, destination, hash);
        result.put("backup", backup).put("backupPath", source);
        if (!delete) result.put("destination", destination).put("sha256", Workspace.sha256(workspace.readFile(destination)));
    }

    private boolean expired() {
        return stopped || System.nanoTime() - started >= TimeUnit.MINUTES.toNanos(3);
    }

    private static final class ReviewDenied extends IOException {}

    private void approve(String title, String detail) throws InterruptedException, IOException {
        if (expired() || !review.ask(title, detail) || expired()) throw new ReviewDenied();
    }

    private void approveRead(String path) throws InterruptedException, IOException {
        if (ProjectTools.needsReview(path)) approve("允许将此文件用于当前聊天任务？", path);
    }

    private Map<String, String> readCall(String message) throws IOException {
        if (message.length() > MAX_INPUT_CHARS) throw new IOException("Request exceeds limit");
        var fields = new HashMap<String, String>();
        try (JsonReader reader = new JsonReader(new StringReader(message))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                JsonToken token = reader.peek();
                if (key.equals("files")) {
                    if (fields.containsKey(key) || token != JsonToken.BEGIN_ARRAY) throw new IOException("Expected files array");
                    reader.beginArray();
                    var inputs = new ArrayList<String>();
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.STRING || inputs.size() >= 32) throw new IOException("Invalid input list");
                        String path = reader.nextString();
                        if (path.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid path");
                        inputs.add(path);
                    }
                    reader.endArray();
                    fields.put(key, String.join("\n", inputs));
                    continue;
                }
                if (token != (key.equals("step") ? JsonToken.NUMBER : JsonToken.STRING)) {
                    throw new IOException("Incorrect field type");
                }
                if (fields.putIfAbsent(key, reader.nextString()) != null) throw new IOException("Duplicate field");
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing data");
        }
        return fields;
    }
}
