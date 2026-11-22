package dev.nativeagent.probe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONException;
import org.json.JSONObject;

/** Starts one explicitly approved timer through the system Clock's public intent. */
final class PhoneTimer {
    private static final String PACKAGE = "com.coloros.alarmclock";
    private static final String CHAT = "com.openai.chatgpt";
    private static final ComponentName HANDLER = new ComponentName(PACKAGE, "com.oplus.alarmclock.cts.HandleApiActivity");
    private final AccessibilityService service;

    PhoneTimer(AccessibilityService service) { this.service = service; }

    JSONObject start(int seconds, String taskId) throws IOException, InterruptedException, JSONException {
        long ready = SystemClock.uptimeMillis() + 3000;
        while (!belongs(service.getRootInActiveWindow(), CHAT)) {
            if (SystemClock.uptimeMillis() >= ready) throw new IOException("Original chat must be in the foreground");
            Thread.sleep(75);
        }
        String label = "NA-" + taskId.substring(0, 8);
        int window = -1;
        try {
            service.startActivity(new Intent(AlarmClock.ACTION_SHOW_TIMERS).setComponent(HANDLER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            window = awaitTimerSetup();
            setWheel(window, 0, seconds / 3600, 24);
            setWheel(window, 1, (seconds / 60) % 60, 60);
            setWheel(window, 2, seconds % 60, 60);
            var wheels = wheels(window);
            if (value(wheels.get(0)) * 3600 + value(wheels.get(1)) * 60 + value(wheels.get(2)) != seconds) {
                throw new IOException("Clock duration did not match the approved request");
            }
            var start = visibleNode(clockWindow(window), "coui_floating_button_main_fab");
            if (start == null || !start.isClickable() || !start.isEnabled()
                || !("开始".contentEquals(start.getContentDescription() == null ? "" : start.getContentDescription())
                    || "Start".contentEquals(start.getContentDescription() == null ? "" : start.getContentDescription()))
                || !start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                throw new IOException("Clock start control unavailable");
            }
            long requestedAt = SystemClock.uptimeMillis();
            long until = requestedAt + 7000;
            int before = -1;
            do {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                service.clearCache();
                var root = service.getRootInActiveWindow();
                if (belongs(root, PACKAGE)) {
                    window = root.getWindowId();
                    var timer = visible(root, "timer_text");
                    int current = TimerInput.displayedSeconds(timer);
                    long elapsed = (SystemClock.uptimeMillis() - requestedAt) / 1000;
                    boolean consistent = current > 0 && current <= seconds && current >= seconds - elapsed - 2;
                    if (consistent && before > current) {
                        return new JSONObject().put("app", "clock").put("timer", label)
                            .put("seconds", seconds).put("remainingSeconds", current)
                            .put("verifiedRunning", true).put("source", "accessibility_nodes");
                    }
                    if (consistent) before = current;
                } else if (root != null && !belongs(root, CHAT)) break;
                Thread.sleep(160);
            } while (SystemClock.uptimeMillis() < until);
            android.util.Log.i("NativeAgentTimer", "Unverified timer " + label + " countdownSeen=" + (before > 0));
            return new JSONObject().put("app", "clock").put("timer", label).put("seconds", seconds)
                .put("verifiedRunning", false);
        } catch (IOException failure) {
            android.util.Log.i("NativeAgentTimer", "Clock UI rejected timer " + label + " reason=" + failure.getMessage());
            throw failure;
        } finally {
            var active = service.getRootInActiveWindow();
            if (belongs(active, PACKAGE) && active.getWindowId() == window && !Thread.currentThread().isInterrupted()) {
                returnToOriginalTask();
            }
        }
    }

    private void returnToOriginalTask() throws InterruptedException {
        // Leaving the Clock by Back preserves the current Android user and existing chat task.
        // Launching ChatGPT by intent can prompt for the ColorOS app clone.
        for (int step = 0; step < 2; step++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            service.clearCache();
            if (!belongs(service.getRootInActiveWindow(), PACKAGE)) return;
            if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) return;
            Thread.sleep(450);
        }
    }

    private int awaitTimerSetup() throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 4000;
        do {
            service.clearCache();
            var root = service.getRootInActiveWindow();
            if (belongs(root, PACKAGE) && visible(root, "timer_label") != null
                && visible(root, "timer_text") == null && visible(root, "timer_multiple_item_rl") == null) {
                return root.getWindowId();
            }
            Thread.sleep(120);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("Clock timer setup is unavailable or there is already a timer");
    }

    private AccessibilityNodeInfo clockWindow(int window) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        service.clearCache();
        var root = service.getRootInActiveWindow();
        if (!belongs(root, PACKAGE) || root.getWindowId() != window) throw new IOException("Clock left the foreground");
        return root;
    }

    private ArrayList<AccessibilityNodeInfo> wheels(int window) throws IOException, InterruptedException {
        var picker = visibleNode(clockWindow(window), "time_pickers");
        var result = new ArrayList<AccessibilityNodeInfo>();
        if (picker != null) {
            for (int i = 0; i < picker.getChildCount(); i++) {
                var node = picker.getChild(i);
                if (belongs(node, PACKAGE) && node.isVisibleToUser() && node.getWindowId() == window
                    && "com.coui.appcompat.picker.COUINumberPicker".contentEquals(String.valueOf(node.getClassName()))) {
                    result.add(node);
                }
            }
        }
        if (result.size() != 3) throw new IOException("Expected three Clock picker wheels");
        return result;
    }

    private static int value(AccessibilityNodeInfo wheel) throws IOException {
        String text = wheel.getText() == null ? "" : wheel.getText().toString().strip();
        if (!text.matches("[0-9]{1,2}")) throw new IOException("Clock picker value unavailable");
        return Integer.parseInt(text);
    }

    private void setWheel(int window, int index, int target, int modulus) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= modulus / 2 + 4; attempt++) {
            var wheel = wheels(window).get(index);
            int current = value(wheel);
            if (current == target) return;
            if (current >= modulus) throw new IOException("Unexpected Clock picker value");
            boolean increment = Math.floorMod(target - current, modulus) <= modulus / 2;
            int action = increment ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if (wheel.performAction(action)) {
                Thread.sleep(280);
                if (value(wheels(window).get(index)) != current) continue;
            }
            swipeWheel(window, wheels(window).get(index), increment);
            if (value(wheels(window).get(index)) == current) throw new IOException("Clock picker did not change");
        }
        throw new IOException("Clock picker did not reach the requested value");
    }

    private void swipeWheel(int window, AccessibilityNodeInfo wheel, boolean increment)
            throws IOException, InterruptedException {
        clockWindow(window);
        var bounds = new Rect();
        wheel.getBoundsInScreen(bounds);
        var screen = service.getResources().getDisplayMetrics();
        if (bounds.width() < 60 || bounds.height() < 180 || bounds.left < 0 || bounds.top < 0
            || bounds.right > screen.widthPixels || bounds.bottom > screen.heightPixels) {
            throw new IOException("Clock picker is outside the visible display");
        }
        float delta = bounds.height() * 0.17f;
        float x = bounds.exactCenterX(), y = bounds.exactCenterY();
        var path = new Path();
        path.moveTo(x, y + (increment ? delta : -delta));
        path.lineTo(x, y + (increment ? -delta : delta));
        var done = new CompletableFuture<Boolean>();
        var gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 370)).build();
        if (!service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gesture) { done.complete(true); }
            @Override public void onCancelled(GestureDescription gesture) { done.complete(false); }
        }, null)) throw new IOException("Clock picker gesture rejected");
        try {
            if (!done.get(2, TimeUnit.SECONDS)) throw new IOException("Clock picker gesture cancelled");
            Thread.sleep(290);
        } catch (TimeoutException | ExecutionException failure) {
            throw new IOException("Clock picker gesture did not finish", failure);
        }
    }

    private static String visible(AccessibilityNodeInfo root, String id) {
        var node = visibleNode(root, id);
        if (node == null) return null;
        var text = node.getText();
        if ((text == null || text.length() == 0) && id.equals("timer_text")) text = node.getContentDescription();
        return text == null ? "" : text.toString().strip();
    }

    private static AccessibilityNodeInfo visibleNode(AccessibilityNodeInfo root, String id) {
        for (var node : root.findAccessibilityNodeInfosByViewId(PACKAGE + ":id/" + id)) {
            if (belongs(node, PACKAGE) && node.isVisibleToUser() && node.getWindowId() == root.getWindowId()) {
                return node;
            }
        }
        return null;
    }

    private static boolean belongs(AccessibilityNodeInfo node, String name) {
        return node != null && name.equals(String.valueOf(node.getPackageName()));
    }
}
