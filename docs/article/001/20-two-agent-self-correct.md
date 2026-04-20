# 双 Agent 自我纠错代码生成：Write Agent + Test Agent 驱动 loop() 循环

> **标签**：`Java` `loop` `McpAgentExecutor` `MCP filesystem` `自我纠错` `代码生成` `JUnit` `实时编译`  
> **前置阅读**：[三 Agent 并行调研：concurrent 节点构建并发-汇聚式旅游规划助手](18-parallel-agent-concurrent.md)  
> **适合人群**：已掌握 `McpAgentExecutor` 与 `loop()` 基础，希望构建真实双 Agent 纠错流程的 Java 开发者

---

## 一、问题：单 Agent 写→测→改的局限

用 `AgentExecutor` 让 LLM 写代码并自动修复是自然的想法，但整个循环隐藏在 ReAct 黑盒里：

- 无法区分"这一轮是写代码还是修复代码"
- 无法在"执行测试"这一步单独挂监控或回调
- 无法用不同模型分别负责写和测
- 无法把"执行测试"替换成真实的 CI 环境

`loop()` 节点让你把这个循环**显式画在 flow 图里**，本篇更进一步：把**写**和**测**分别封装成两个独立的 `McpAgentExecutor`，通过 MCP filesystem 完成真实文件读写，通过 `javax.tools.JavaCompiler` 完成真实编译和 JUnit 执行。

---

## 二、整体架构

```
用户需求（"实现 isPalindrome，忽略大小写和非字母数字字符"）
     ↓
TranslateHandler（保存需求到 ContextBus transmitMap）
     ↓
┌─────────────────────────────────────────────────────────────────┐
│  loop(condition, writeNode, testNode)   最多 3 轮                │
│                                                                 │
│  writeNode (Write Agent)                                        │
│    McpAgentExecutor + MCP filesystem                            │
│    第 1 轮：LLM 编写 Palindrome.java → write_file → /tmp/gen/   │
│    第 2 轮起：LLM 读取旧代码 → 修复 → write_file 覆写            │
│                                                                 │
│  testNode (Test Agent)                                          │
│    McpAgentExecutor + MCP filesystem + compile_and_run 工具     │
│    第 1 轮：read_file → 理解实现 → write_file 写测试 → 编译+运行 │
│    第 2 轮起：直接调 compile_and_run 重跑同一套测试              │
└─────────────────────────────────────────────────────────────────┘
     ↓
TranslateHandler（格式化最终输出）
     ↓
实现文件 + 测试文件 + PASS/FAIL 状态
```

两个 Agent 各司其职：**Write Agent 只负责写代码**，**Test Agent 只负责验证正确性**。`loop()` 是协调者，`ContextBus.transmitMap` 是它们的共享黑板。

---

## 三、compile_and_run 工具：真实编译与执行

Test Agent 除了 MCP filesystem 工具，还额外注入了一个 `compile_and_run` 工具。它做三件事：

```java
static class JavaTestRunner {

    @AgentTool("编译Java实现类和测试类，用JUnit 4运行所有测试，返回PASS/FAIL及详情")
    public String compileAndRun(
        @Param("实现类Java文件绝对路径") String implFile,
        @Param("测试类Java文件绝对路径") String testFile
    ) {
        String result = doCompileAndRun(implFile, testFile);
        ContextBus.get().putTransmit("test_result", result);  // 写入共享状态
        return result;
    }

    private String doCompileAndRun(String implFile, String testFile) {
        // 1. 调用 javax.tools.JavaCompiler 进程内编译，classpath 含 JUnit JAR
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String classpath = System.getProperty("java.class.path");
        // ... 编译到 GEN_DIR ...

        // 2. 每次新建 URLClassLoader，避免 JVM 缓存旧版 .class
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{new File(GEN_DIR).toURI().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            Class<?> testClass = loader.loadClass("PalindromeTest");
            Result result = new JUnitCore().run(testClass);
            // 3. 返回 PASS/FAIL 详情
            return result.wasSuccessful()
                ? "PASS: All " + result.getRunCount() + " tests passed ✅"
                : buildFailReport(result);
        }
    }
}
```

关键设计：

| 机制 | 说明 |
|------|------|
| `javax.tools.JavaCompiler` | JDK 内置编译器，进程内完成，无需 `Runtime.exec` |
| `System.getProperty("java.class.path")` | 把当前 classpath（含 JUnit JAR）传给 `javac -cp` |
| `URLClassLoader`（每次新建） | 多轮修复后总能加载最新 `.class`，避免缓存旧版本 |
| `ContextBus.get().putTransmit("test_result", result)` | 工具直接写共享状态，loop condition 无需解析 LLM 的自然语言输出 |

---

## 四、两个 Agent 的配置

### Write Agent

```java
McpAgentExecutor writeAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")          // read_file / write_file / ...
    .systemPrompt(
        "You are a Java implementation expert.\n" +
        "Task: write or fix a Java class and save it to the specified file path.\n" +
        "Follow these steps exactly, in order:\n" +
        "  1. Do NOT include any package declaration.\n" +
        "  2. The class must be public and have a public method matching the required signature.\n" +
        "  3. Call write_file ONCE to save the file.\n" +
        "  4. After write_file succeeds, call list_directory on the parent directory to confirm the file is listed.\n" +
        "  5. Once you see the file in the listing, output one confirmation line and stop. Make NO further tool calls."
    )
    .maxIterations(5)
    .build();
```

Write Agent 只有 MCP filesystem 工具，职责单一：**把代码写到文件**。写完后调用 `list_directory` 确认文件存在，给 LLM 一个明确的收尾动作，避免反复重写。

### Test Agent

```java
McpAgentExecutor testAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")          // read_file / write_file / ...
    .tools(runner)                           // compile_and_run 工具
    .systemPrompt(
        "You are a Java testing expert. You will be given EXACTLY three tasks to execute in sequence.\n" +
        "CRITICAL: Call each tool exactly once, in this order, then stop:\n" +
        "  CALL 1 — read_file: read the implementation file. Pass ONLY the 'path' parameter, no other parameters.\n" +
        "  CALL 2 — write_file: write a JUnit 4 test class to the test file path.\n" +
        "  CALL 3 — compile_and_run: pass the implementation file path and test file path.\n" +
        "After compile_and_run returns, output its result verbatim and make NO further tool calls."
    )
    .maxIterations(12)
    .build();
```

Test Agent 有 MCP filesystem + `compile_and_run` 两类工具，职责：**写测试、编译、运行、汇报结果**。

> `McpAgentExecutor.Builder` 支持链式调用多次 `.tools()`，MCP 工具和自定义工具可以共存。

---

## 五、loop 驱动逻辑

```java
.loop(
    // condition：test_result 为 FAIL 则继续，最多 3 轮
    i -> {
        String testResult = ContextBus.get().getTransmit("test_result");
        boolean failed = testResult != null && testResult.startsWith("FAIL");
        return (i == 0 || failed) && i < 3;
    },

    // 节点A：Write Agent 写/修复实现
    (Object input) -> {
        String req        = ContextBus.get().getTransmit("requirement");
        String testResult = ContextBus.get().getTransmit("test_result");
        String prompt = testResult == null
            ? "Write Palindrome.java ... save to " + IMPL_FILE
            : "Fix " + IMPL_FILE + " to pass failures: " + testResult;
        return writeAgent.invoke(prompt);
    },

    // 节点B：第 1 轮 Test Agent 写测试+执行；第 2 轮起直接调 runner 跳过 LLM
    (Object writeResult) -> {
        String testsWritten = ContextBus.get().getTransmit("tests_written");
        if (testsWritten == null) {
            ContextBus.get().putTransmit("tests_written", "true");
            return testAgent.invoke("read_file, write tests to " + TEST_FILE + ", compile_and_run");
        } else {
            return runner.compileAndRun(IMPL_FILE, TEST_FILE);  // 直接执行，跳过 LLM
        }
    }
)
```

### transmitMap 中的共享状态

| key | 写入方 | 读取方 | 用途 |
|-----|--------|--------|------|
| `requirement` | 前置 TranslateHandler | Write Agent / Test Agent（每轮） | 保持原始需求，多轮均可访问 |
| `test_result` | `compile_and_run` 工具 | loop condition | `startsWith("FAIL")` 决定是否继续 |
| `tests_written` | testNode lambda | testNode lambda（下一轮） | 标记测试已写，后续轮次仅重跑不重写 |

### "测试只写一次"设计

Test Agent 第 1 轮写测试文件；第 2 轮起直接调用 `runner.compileAndRun()` 跳过 LLM，避免测试用例随轮次漂移。

---

## 六、执行流程示例

```
========== 双Agent自我纠错代码生成 ==========
需求：判断字符串是否为回文，忽略大小写和非字母数字字符

--- 步骤1：Write Agent 编写初始实现 ---
[WriteAgent] tool call: write_file {"path":"/private/tmp/gen/Palindrome.java", ...}
[WriteAgent 完成] I have written the Palindrome class to /private/tmp/gen/Palindrome.java...

--- 步骤2：Test Agent 编写测试并执行 ---
[TestAgent] tool call: read_file {"path":"/private/tmp/gen/Palindrome.java"}
[TestAgent] tool call: write_file {"path":"/private/tmp/gen/PalindromeTest.java", ...}
[TestAgent] tool call: compile_and_run {"implFile":"/private/tmp/gen/Palindrome.java","testFile":"/private/tmp/gen/PalindromeTest.java"}
[TestAgent] observation: PASS: All 4 tests passed ✅
[TestAgent 完成] PASS: All 4 tests passed ✅

--- Loop 条件检查：第2轮，failed=false，继续=false ---

========== 最终结果 ==========
PASS: All 4 tests passed ✅

测试状态：PASS: All 4 tests passed ✅
生成文件：/private/tmp/gen/Palindrome.java  /private/tmp/gen/PalindromeTest.java
================================
```

如果第 1 轮测试失败，Write Agent 在第 2 轮读取失败信息并修复实现，Test Agent 直接重跑同一套测试直至通过。

---

## 七、运行前置条件

1. **JDK（非 JRE）**：`compile_and_run` 使用 `javax.tools.JavaCompiler`，JRE 中不含编译器
2. **Node.js**：MCP filesystem 服务器通过 `npx` 启动
3. **`mcp.server.config.json`**：`filesystem` 服务器 args 配置为 `/private/tmp`（macOS）或 `/tmp`（Linux）
4. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen3.6-plus`

---

## 八、总结

本篇展示了 j-langchain 中双 Agent 自我纠错的完整模式：

- **职责分离**：Write Agent 只写代码，Test Agent 只验证——每个 `McpAgentExecutor` 只暴露与职责匹配的工具
- **真实闭环**：LLM 生成 → MCP 写文件 → `javac` 编译 → JUnit 执行，全链路无 mock
- **状态透明**：`compile_and_run` 工具直接写 ContextBus，loop condition 读状态，两个 Agent 无需互相感知
- **测试稳定**：`tests_written` 标志保证测试用例只写一次，多轮验证的是实现本身

这种"两个专家 Agent + loop 协调"的模式可推广到任何生成→验证→修复场景：SQL 生成+执行验证、配置文件生成+语法检查、接口代码生成+集成测试。

---

> 📎 相关资源
> - 完整代码：[Article20TwoAgentSelfCorrect.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article20TwoAgentSelfCorrect.java)，方法 `twoAgentSelfCorrect()`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：JDK 17+、Node.js、`ALIYUN_KEY`（`qwen3.6-plus`）
