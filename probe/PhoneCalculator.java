package dev.nativeagent.probe;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Fixed target, fixed controls, no coordinate or arbitrary intent input. */
final class PhoneCalculator {
    private static final String PACKAGE = "com.coloros.calculator";
    private static final String CHAT = "com.openai.chatgpt";
    private static final ComponentName APP = new ComponentName(PACKAGE, "com.android.calculator2.Calculator");
    private final AccessibilityService service;

    PhoneCalculator(AccessibilityService service) { this.service = service; }

    JSONObject calculate(String expression, boolean screenshot) throws IOException, InterruptedException, JSONException {
        CalculatorInput.validate(expression);
        launch();
        int window = -1;
        try {
            window = awaitCalculator();
            clearFormula(window);
            for (int i = 0; i < expression.length(); i++) {
                press(window, CalculatorInput.control(expression.charAt(i)));
            }
            if (!awaitFormula(window, expression, 1000)) throw new IOException("Displayed formula does not match the approved expression");
            press(window, "eq");
            String displayed = readResult(window);
            var reply = new JSONObject().put("app", "calculator").put("expression", expression).put("display", displayed);
            if (screenshot) {
                int captureWindow = window;
                try {
                    reply.put("capture", PhoneCapture.saveWindow(service, window, () -> {
                        if (unique(captureWindow, "result") == null) throw new IOException("Calculator result unavailable");
                    }));
                } catch (IOException failure) {
                    reply.put("captureError", "Calculator screenshot could not be saved");
                }
            }
            return reply;
        } finally {
            returnToChat(window);
        }
    }

    JSONObject read() throws IOException, InterruptedException, JSONException {
        launch();
        int window = -1;
        try {
            window = awaitCalculator();
            return new JSONObject().put("app", "calculator").put("expression", formula(window))
                .put("display", readResult(window)).put("source", "accessibility_nodes");
        } finally { returnToChat(window); }
    }

    private void launch() throws IOException, InterruptedException {
        awaitChat();
        service.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(APP)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void returnToChat(int window) {
        var root = service.getRootInActiveWindow();
        if (belongs(root, PACKAGE) && root.getWindowId() == window && !Thread.currentThread().isInterrupted()) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }
    }

    private void clearFormula(int window) throws IOException, InterruptedException {
        if (isCleared(formula(window))) return;
        press(window, "clr");
        if (awaitFormula(window, "", 700)) return;
        // Some calculator states keep the formula visible after the clear control.
        // Remove observed digits only, and stop if the display fails to change.
        for (int count = 0; count < 40; count++) {
            String before = formula(window);
            if (isCleared(before)) return;
            press(window, "del");
            String after = formula(window);
            if (isCleared(after)) return;
            if (before.equals(after)) break;
        }
        throw new IOException("Calculator formula could not be cleared");
    }

    private boolean awaitFormula(int window, String expected, int millis) throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + millis;
        do {
            String visible = formula(window);
            if (expected.isEmpty() ? isCleared(visible) : expected.equals(visible)) return true;
            Thread.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private String formula(int window) throws IOException, InterruptedException {
        var node = unique(window, "formula");
        if (node == null) throw new IOException("Calculator formula is unavailable");
        return node.getText() == null ? "" : CalculatorInput.normalizedFormula(node.getText().toString());
    }

    private static boolean isCleared(String expression) {
        return expression.isEmpty() || expression.equals("0");
    }

    private void awaitChat() throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 3000;
        do {
            if (belongs(service.getRootInActiveWindow(), CHAT)) return;
            Thread.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("Return to the original ChatGPT conversation");
    }

    private int awaitCalculator() throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 4000;
        do {
            service.clearCache();
            var root = service.getRootInActiveWindow();
            if (belongs(root, PACKAGE)) return root.getWindowId();
            Thread.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("System calculator did not open");
    }

    private void press(int window, String control) throws IOException, InterruptedException {
        var node = unique(window, control);
        if (node == null || !node.isClickable() || !node.isEnabled()
            || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            throw new IOException("Expected calculator control is unavailable");
        }
        Thread.sleep(115);
    }

    private String readResult(int window) throws IOException, InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 1600;
        do {
            var result = unique(window, "result");
            String text = result == null || result.getText() == null ? "" : result.getText().toString().strip();
            if (!text.isEmpty() && text.length() <= 64 && text.matches("[0-9., +−eE-]+")) return text;
            Thread.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("Calculator result unavailable");
    }

    private AccessibilityNodeInfo unique(int window, String control) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        service.clearCache();
        var root = service.getRootInActiveWindow();
        if (!belongs(root, PACKAGE) || root.getWindowId() != window) throw new IOException("Calculator left the foreground");
        AccessibilityNodeInfo selected = null;
        for (var node : root.findAccessibilityNodeInfosByViewId(PACKAGE + ":id/" + control)) {
            if (!belongs(node, PACKAGE) || !node.isVisibleToUser() || node.getWindowId() != window) continue;
            if (selected != null) throw new IOException("Ambiguous calculator control");
            selected = node;
        }
        return selected;
    }

    private static boolean belongs(AccessibilityNodeInfo node, String expected) {
        return node != null && expected.contentEquals(String.valueOf(node.getPackageName()));
    }
}
