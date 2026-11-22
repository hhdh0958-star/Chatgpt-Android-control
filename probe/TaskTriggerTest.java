package dev.nativeagent.probe;

public final class TaskTriggerTest {
    public static void main(String[] args) {
        expect("创建 test.py", "/agent 创建 test.py --go");
        expect("请在手机工作区创建 test.py。", "请在手机工作区创建 test.py。");
        expect(null, "请解释手机上怎样运行 Python。");
        String acceptance = "检查我手机指定目录里的代码，发现问题就修改，然后运行测试。";
        expect(acceptance, acceptance);
        expect(acceptance, "「" + acceptance + "」");
        expect(acceptance, acceptance + "」");
        expect(null, "这里是例子：「" + acceptance + "」");
        expect("运行测试", "「/agent 运行测试 --go」");
        expect(null, "请在手机工作区创建");
        expect(null, "在文档里看到：请在手机工作区创建文件。");
        expect(null, "/agent \n test --go");
        expect(null, "/agent-stop");
        expect("请在应用私有工作区整理文件。", "请在应用私有工作区整理文件。");
        System.out.println("PASS explicit and natural language task entry, ordinary chat, incomplete draft, quoted text");
    }

    private static void expect(String expected, String text) {
        if (!java.util.Objects.equals(expected, TaskTrigger.request(text))) throw new AssertionError(text);
    }
}
