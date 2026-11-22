package dev.nativeagent.probe;

import java.io.IOException;

final class TimerInput {
    static boolean request(String text) {
        return text.startsWith("请用手机时钟倒计时");
    }

    static int seconds(String text) throws IOException {
        if (!text.matches("[1-9][0-9]{0,3}")) throw new IOException("Expected whole seconds");
        int duration = Integer.parseInt(text);
        if (duration < 10 || duration > 3600) throw new IOException("Timer must be between 10 and 3600 seconds");
        return duration;
    }

    static int displayedSeconds(String value) {
        if (value == null) return -1;
        var localized = java.util.regex.Pattern.compile(
            "(?:(\\d{1,2})\\s*(?:小时|hours?)\\s*)?(?:(\\d{1,2})\\s*(?:分钟|minutes?)\\s*)?(\\d{1,2})\\s*(?:秒|seconds?)")
            .matcher(value);
        if (localized.matches()) {
            return (localized.group(1) == null ? 0 : Integer.parseInt(localized.group(1)) * 3600)
                + (localized.group(2) == null ? 0 : Integer.parseInt(localized.group(2)) * 60)
                + Integer.parseInt(localized.group(3));
        }
        if (!value.matches("(?:[0-9]{1,2}:)?[0-9]{1,2}:[0-5][0-9]")) return -1;
        int seconds = 0;
        for (String part : value.split(":")) seconds = seconds * 60 + Integer.parseInt(part);
        return seconds;
    }
}
