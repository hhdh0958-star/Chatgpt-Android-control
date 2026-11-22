package dev.nativeagent.probe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.ComponentName;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads a single user-selected foreground application window through Android accessibility. */
final class PhoneScreen {
    private static final String CHAT = "com.openai.chatgpt";
    private static final int MAX_NODES = 256;
    private static final int MAX_LABELS = 24;
    private static final java.util.regex.Pattern QQ_QUERY = java.util.regex.Pattern.compile(
        "(?:请帮我|帮我|请)?(?:在\\s*)?(?i:qq)\\s*搜(?:索)?+[：: ]*+(.+)|(?i:search QQ for) (.+)");

    static String qqQuery(String request) {
        var match = QQ_QUERY.matcher(request.strip());
        if (!match.matches()) return null;
        String query = (match.group(1) == null ? match.group(2) : match.group(1)).strip();
        return query.isEmpty() || query.length() > 40 || query.codePoints().anyMatch(
            c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) ? null : query;
    }

    enum Target {
        CALCULATOR("计算器", "com.coloros.calculator"),
        CLOCK("时钟", "com.coloros.alarmclock"),
        WECHAT("微信", "com.tencent.mm"),
        STOPWATCH("秒表", "com.coloros.alarmclock", "global"),
        WORLD_CLOCK("世界时钟", "com.coloros.alarmclock", "clock"),
        QQ_SEARCH("QQ搜索", "com.tencent.mobileqq"),
        QQ_OPEN("QQ", "com.tencent.mobileqq");

        final String label;
        final String packageName;
        final String tabId;

        Target(String label, String packageName) {
            this(label, packageName, null);
        }

        Target(String label, String packageName, String tabId) {
            this.label = label;
            this.packageName = packageName;
            this.tabId = tabId;
        }

        String tool() {
            if (this == QQ_SEARCH) return "phone_open_search";
            if (this == QQ_OPEN) return "phone_open_app";
            return tabId == null ? "phone_read_screen" : "phone_open_tab";
        }

        static Target requested(String request) {
            String command = request.strip().replaceFirst("^(?:请帮我|帮我|请)", "")
                .replaceFirst("[。.!！]$", "").strip();
            if (command.matches("打开\\s*(?i:qq)\\s*(?:的)?\\s*搜索(?:页)?") || qqQuery(request) != null) return QQ_SEARCH;
            if (command.equalsIgnoreCase("open QQ search")) return QQ_SEARCH;
            if (command.matches("打开\\s*(?i:qq)") || command.equalsIgnoreCase("open QQ")) return QQ_OPEN;
            for (Target target : values()) {
                if (target == QQ_SEARCH || target == QQ_OPEN) continue;
                String expected = "打开" + (target.tabId != null ? "时钟的" : "") + target.label;
                if (command.equals(expected + "并读取当前页面")
                    || (target.tabId != null && command.equals(expected))) return target;
            }
            return null;
        }
    }

    static JSONObject read(AccessibilityService service, Target target, int chatWindow, String qqQuery)
            throws IOException, InterruptedException, JSONException {
        awaitWindow(service, CHAT, chatWindow, 3500);
        launch(service, target.packageName);
        if (target == Target.QQ_OPEN) {
            AccessibilityNodeInfo root;
            try {
                root = awaitWindow(service, target.packageName, -1, 20_000);
            } catch (IOException unavailable) {
                throw new IOException("QQ did not reach the foreground. Check whether Android is waiting for launch approval.", unavailable);
            }
            Thread.sleep(600);
            var foreground = awaitWindow(service, target.packageName, root.getWindowId(), 1500);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!matches(service.getRootInActiveWindow(0), target.packageName, foreground.getWindowId())) {
                throw new IOException("QQ left the foreground before returning");
            }
            returnToChat(service);
            awaitWindow(service, CHAT, chatWindow, 5000);
            return new JSONObject().put("app", "QQ").put("foregroundVerified", true);
        }
        int targetWindow = -1;
        boolean clicked = false;
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        try {
            var root = awaitWindow(service, target.packageName, -1, 5000);
            targetWindow = root.getWindowId();
            // Let the first page render, then require the same foreground window for collection.
            Thread.sleep(600);
            root = service.getRootInActiveWindow(0);
            if (!matches(root, target.packageName, targetWindow)) throw new IOException("App window changed");
            if (target == Target.QQ_SEARCH) {
                targetWindow = openQqSearch(service, target);
                if (qqQuery != null) enterQqQuery(service, target, targetWindow, qqQuery);
            }
            if (target.tabId != null) {
                var tab = findTab(root, target);
                if (!tab.isSelected()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (!tab.isClickable() || !tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        throw new IOException("Clock tab click was rejected");
                    }
                    clicked = true;
                }
                long deadline = SystemClock.uptimeMillis() + 2500;
                do {
                    Thread.sleep(100);
                    root = service.getRootInActiveWindow(0);
                    if (!matches(root, target.packageName, targetWindow)) throw new IOException("Clock window changed");
                    if (findTab(root, target).isSelected()) break;
                    if (SystemClock.uptimeMillis() >= deadline) throw new IOException("Clock tab selection was not verified");
                } while (true);
            }
            var queue = new ArrayDeque<AccessibilityNodeInfo>();
            if (target != Target.QQ_SEARCH) queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < MAX_NODES && labels.size() < MAX_LABELS) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                var node = queue.removeFirst();
                if (!matches(node, target.packageName, targetWindow) || !node.isVisibleToUser()
                    || node.isPassword() || node.isEditable() || node.isAccessibilityDataSensitive()) continue;
                add(labels, node.getText());
                add(labels, node.getContentDescription());
                for (int i = 0; i < node.getChildCount() && queue.size() + visited < MAX_NODES; i++) {
                    var child = node.getChild(i, 0);
                    if (child != null) queue.add(child);
                }
            }
        } finally {
            if (!Thread.currentThread().isInterrupted() && targetWindow >= 0
                && matches(service.getRootInActiveWindow(0), target.packageName, targetWindow)) {
                if (target == Target.QQ_SEARCH) returnToChat(service);
                else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            }
        }
        awaitWindow(service, CHAT, chatWindow, 3500);
        if (target == Target.QQ_SEARCH) return new JSONObject().put("app", "QQ").put("searchOpened", true)
            .put("queryEntered", qqQuery != null);
        var result = new JSONObject().put("app", target.tabId == null ? target.label : "时钟")
            .put("source", "accessibility_nodes").put("labels", new JSONArray(labels));
        if (target.tabId != null) result.put("selectedTab", target.label).put("clicked", clicked);
        return result;
    }

    private static void enterQqQuery(AccessibilityService service, Target target, int window, String query)
            throws IOException, InterruptedException {
        var info = service.getServiceInfo();
        int previousFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        service.setServiceInfo(info);
        try {
            service.clearCache();
            var root = awaitWindow(service, target.packageName, window, 1800);
            if (!qqSearchVisible(root, target)) throw new IOException("QQ search page changed");
            var editor = qqSearchEditor(root, target);
            if (editor == null) throw new IOException("QQ search input is not available to Android accessibility");
            CharSequence before = editor.getText();
            if (query.contentEquals(before == null ? "" : before)) return;
            if (before != null && !before.toString().isBlank() && !editor.isShowingHintText()) {
                throw new IOException("QQ search already contains another query");
            }
            var arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                throw new IOException("QQ search rejected text entry");
            }
            long deadline = SystemClock.uptimeMillis() + 2000;
            do {
                Thread.sleep(100);
                service.clearCache();
                root = service.getRootInActiveWindow(0);
                if (!matches(root, target.packageName, window)) throw new IOException("QQ search page changed");
                editor = qqSearchEditor(root, target);
                if (editor != null && editor.getText() != null && query.contentEquals(editor.getText())) return;
            } while (SystemClock.uptimeMillis() < deadline);
            throw new IOException("QQ search text could not be verified");
        } finally {
            info.flags = previousFlags;
            service.setServiceInfo(info);
            service.clearCache();
        }
    }

    private static AccessibilityNodeInfo qqSearchEditor(AccessibilityNodeInfo root, Target target) throws IOException {
        var candidates = root.findAccessibilityNodeInfosByViewId(target.packageName + ":id/z5z").stream()
            .filter(node -> matches(node, target.packageName, root.getWindowId()) && node.isVisibleToUser()
                && node.isEnabled() && node.isEditable() && !node.isPassword() && !node.isAccessibilityDataSensitive()
                && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)).toList();
        if (candidates.size() > 1) throw new IOException("QQ search input is ambiguous");
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static void returnToChat(AccessibilityService service) {
        // Resume the existing activity without requesting the launcher entry shared by app clones.
        service.startActivity(new Intent().setComponent(new ComponentName(CHAT, CHAT + ".MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private static int openQqSearch(AccessibilityService service, Target target) throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 6000;
        boolean searchClicked = false;
        boolean conversationClosed = false;
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            service.clearCache();
            var root = service.getRootInActiveWindow(0);
            if (root != null && !matches(root, target.packageName, -1)) throw new IOException("QQ left the foreground");
            if (root != null) {
                if (qqSearchVisible(root, target)) return root.getWindowId();
                if (!searchClicked) {
                    var entry = findQqControl(root, target, "z7v", "android.view.ViewGroup");
                    if (entry != null && entry.isClickable()) {
                        if (!entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            throw new IOException("QQ search click was rejected");
                        }
                        searchClicked = true;
                    } else if (!conversationClosed) {
                        var composer = findQqControl(root, target, "input", "android.widget.EditText");
                        var back = findQqControl(root, target, "ivTitleBtnLeft", "android.widget.TextView");
                        if (composer != null && composer.isEditable() && !composer.isPassword()
                            && back != null && back.isClickable()) {
                            if (composer.getText() != null && composer.getText().length() != 0 && !composer.isShowingHintText()) {
                                throw new IOException("QQ has an unsent draft in the open conversation");
                            }
                            if (!back.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                throw new IOException("QQ conversation back button was rejected");
                            }
                            conversationClosed = true;
                        }
                    }
                }
            }
            Thread.sleep(200);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(searchClicked ? "QQ search page was not verified" : "QQ search entry was not available");
    }

    private static AccessibilityNodeInfo findQqControl(AccessibilityNodeInfo root, Target target, String id, String className)
            throws IOException {
        var candidates = root.findAccessibilityNodeInfosByViewId(target.packageName + ":id/" + id).stream()
            .filter(node -> matches(node, target.packageName, root.getWindowId()) && node.isVisibleToUser()
                && node.isEnabled() && className.contentEquals(String.valueOf(node.getClassName()))).toList();
        if (candidates.size() > 1) throw new IOException("QQ navigation control is ambiguous");
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean qqSearchVisible(AccessibilityNodeInfo root, Target target) {
        return visibleControl(root, target, "b9j")
            || (visibleControl(root, target, "cyj") && visibleControl(root, target, "result_layout"));
    }

    private static boolean visibleControl(AccessibilityNodeInfo root, Target target, String id) {
        return root.findAccessibilityNodeInfosByViewId(target.packageName + ":id/" + id).stream()
            .anyMatch(node -> matches(node, target.packageName, root.getWindowId()) && node.isVisibleToUser());
    }

    private static void launch(AccessibilityService service, String packageName) throws IOException, InterruptedException {
        var intent = service.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null || intent.getComponent() == null || !packageName.equals(intent.getComponent().getPackageName())) {
            throw new IOException("Selected app launcher is unavailable");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static AccessibilityNodeInfo findTab(AccessibilityNodeInfo root, Target target) throws IOException {
        return uniqueControl(root, target, target.tabId, target.label);
    }

    private static AccessibilityNodeInfo uniqueControl(AccessibilityNodeInfo root, Target target, String viewId, String label)
            throws IOException {
        var nodes = root.findAccessibilityNodeInfosByViewId(target.packageName + ":id/" + viewId).stream()
            .filter(node -> matches(node, target.packageName, root.getWindowId()) && node.isVisibleToUser()
                && node.isEnabled() && (label.contentEquals(String.valueOf(node.getContentDescription()))
                    || label.contentEquals(String.valueOf(node.getText()))))
            .toList();
        if (nodes.size() != 1) throw new IOException("Expected exactly one matching app control");
        return nodes.get(0);
    }

    private static void add(LinkedHashSet<String> labels, CharSequence value) {
        if (value == null || labels.size() >= MAX_LABELS) return;
        String text = value.subSequence(0, Math.min(value.length(), 96)).toString()
            .replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip();
        if (!text.isEmpty()) labels.add(text);
    }

    private static AccessibilityNodeInfo awaitWindow(AccessibilityService service, String pkg, int window, int millis)
            throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + millis;
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var root = service.getRootInActiveWindow(0);
            if (matches(root, pkg, window)) return root;
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("Expected foreground window was not available");
    }

    private static boolean matches(AccessibilityNodeInfo node, String pkg, int window) {
        return node != null && pkg.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
            && (window < 0 || window == node.getWindowId());
    }
}
