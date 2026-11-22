package dev.nativeagent.probe;

import java.io.IOException;

final class CalculatorInput {
    static void validate(String expression) throws IOException {
        if (expression.length() > 32 || !expression.matches("[0-9]+(\\.[0-9]+)?([+*/-][0-9]+(\\.[0-9]+)?){1,5}")) {
            throw new IOException("Calculator accepts only a short arithmetic expression");
        }
    }

    static String control(char key) {
        return switch (key) {
            case '+' -> "op_add";
            case '-' -> "op_sub";
            case '*' -> "op_mul";
            case '/' -> "op_div";
            case '.' -> "dec_point";
            default -> "digit_" + key;
        };
    }

    static boolean request(String text) {
        return text.startsWith("请用手机计算器") || readRequest(text);
    }

    static boolean readRequest(String text) { return text.startsWith("请读取手机计算器"); }

    static String normalizedFormula(String visible) {
        return visible.replace('×', '*').replace('÷', '/').replace('−', '-')
            .replace(",", "").replace(" ", "").replace("\u00a0", "");
    }
}
