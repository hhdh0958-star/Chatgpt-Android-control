package dev.nativeagent.probe;

final class TaskTrigger {
    private static final String PREFIX = "/agent ";
    private static final String SUFFIX = " --go";

    /** Called only on a foreground user-editable composer. The user sends the prepared draft. */
    static String request(String draft) {
        String command = draft.strip();
        // Messaging apps often copy Chinese quotation marks with the selected sentence.
        if (command.startsWith("「") || command.startsWith("“") || command.startsWith("『")) {
            command = command.substring(1).strip();
        }
        if (command.endsWith("」") || command.endsWith("”") || command.endsWith("』")) {
            command = command.substring(0, command.length() - 1).strip();
        }
        if (command.length() > 500 || command.contains("\n") || command.contains("\r")) return null;
        if (command.startsWith(PREFIX) && command.endsWith(SUFFIX)
            && command.length() > PREFIX.length() + SUFFIX.length()) {
            String task = command.substring(PREFIX.length(), command.length() - SUFFIX.length()).strip();
            return task.isEmpty() ? null : task;
        }
        if (PhoneScreen.Target.requested(command) != null) return command;
        boolean addressed = command.startsWith("请在手机工作区") || command.startsWith("帮我在手机工作区")
            || privateWorkspace(command)
            || CalculatorInput.request(command)
            || TimerInput.request(command)
            || LogDiagnostics.request(command)
            || command.startsWith("检查我手机指定目录里的代码")
            || command.startsWith("在手机工作区中") || command.startsWith("In my phone workspace, ");
        if (addressed && (command.endsWith("。") || command.endsWith("."))) return command;
        return null;
    }

    static boolean privateWorkspace(String request) {
        return request.startsWith("请在应用私有工作区");
    }
}
