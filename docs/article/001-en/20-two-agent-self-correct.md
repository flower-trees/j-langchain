# Dual-Agent Self-Correcting Code Generation: Write Agent + Test Agent Driving `loop()`

> **Tags**: Java, loop, McpAgentExecutor, MCP filesystem, self-correcting, code generation, JUnit, real compilation  
> **Prerequisite**: [Parallel Agent Research: Fan-out / Fan-in with `concurrent`](18-parallel-agent-concurrent.md)  
> **Audience**: Developers who know `loop()` and `McpAgentExecutor` basics and want a production-ready dual-agent correction pipeline

---

## 1. The Problem: Single-Agent Write → Test → Fix Limitations

Letting one `AgentExecutor` write code and auto-fix it is a natural idea, but the entire cycle is hidden inside the ReAct black box:

- Can't distinguish "is this round writing or fixing code"
- Can't attach monitoring or callbacks to the "run tests" step specifically
- Can't use different models for writing vs. testing
- Can't swap "run tests" for a real CI environment

`loop()` lets you **draw this cycle explicitly in the flow graph**. This article goes further: encapsulating **write** and **test** as two independent `McpAgentExecutor` instances, using MCP filesystem for real file I/O, and `javax.tools.JavaCompiler` for real compilation and JUnit execution.

---

## 2. Architecture

```
User requirement ("implement isPalindrome, ignore case and non-alphanumeric chars")
     ↓
TranslateHandler (save requirement to ContextBus transmitMap)
     ↓
┌───────────────────────────────────────────────────────────────────┐
│  loop(condition, writeNode, testNode)   max 3 rounds               │
│                                                                   │
│  writeNode (Write Agent)                                          │
│    McpAgentExecutor + MCP filesystem                              │
│    Round 1: LLM writes Palindrome.java → write_file → /tmp/gen/  │
│    Round 2+: LLM reads old code → fixes it → write_file overwrites│
│                                                                   │
│  testNode (Test Agent)                                            │
│    McpAgentExecutor + MCP filesystem + compile_and_run tool       │
│    Round 1: read_file → understand impl → write_file tests → run  │
│    Round 2+: call compile_and_run directly to re-run same tests   │
└───────────────────────────────────────────────────────────────────┘
     ↓
TranslateHandler (format final output)
     ↓
Implementation file + test file + PASS/FAIL status
```

The two agents have distinct roles: **Write Agent only writes code**, **Test Agent only validates correctness**. `loop()` is the coordinator, and `ContextBus.transmitMap` is their shared blackboard.

---

## 3. The `compile_and_run` Tool: Real Compilation and Execution

The Test Agent gets, in addition to MCP filesystem tools, a custom `compile_and_run` tool. It does three things:

```java
static class JavaTestRunner {

    @AgentTool("编译Java实现类和测试类，用JUnit 4运行所有测试，返回PASS/FAIL及详情")
    public String compileAndRun(
        @Param("实现类Java文件绝对路径，如 /tmp/gen/Palindrome.java") String implFile,
        @Param("测试类Java文件绝对路径，如 /tmp/gen/PalindromeTest.java") String testFile
    ) {
        String result = doCompileAndRun(implFile, testFile);
        ContextBus.get().putTransmit("test_result", result);  // write shared state
        return result;
    }

    private String doCompileAndRun(String implFile, String testFile) {
        // 1. Compile using javax.tools.JavaCompiler (in-process, classpath includes JUnit JAR)
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String classpath = System.getProperty("java.class.path");
        // ... compile to GEN_DIR ...

        // 2. New URLClassLoader each round to bypass JVM class cache
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{new File(GEN_DIR).toURI().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            Class<?> testClass = loader.loadClass("PalindromeTest");
            Result result = new JUnitCore().run(testClass);
            // 3. Return PASS/FAIL details
            return result.wasSuccessful()
                ? "PASS: All " + result.getRunCount() + " tests passed ✅"
                : buildFailReport(result);
        }
    }
}
```

Key design points:

| Mechanism | Why |
|-----------|-----|
| `javax.tools.JavaCompiler` | In-process JDK compiler — no `Runtime.exec`, no shell quoting issues |
| `System.getProperty("java.class.path")` | Passes the current classpath (including JUnit JAR) to `javac -cp` so test annotations resolve |
| `URLClassLoader` (new each round) | Avoids JVM class cache; always loads the latest `.class` after each fix |
| `ContextBus.get().putTransmit("test_result", result)` | Tool writes shared state directly; loop condition reads a clean PASS/FAIL string without parsing LLM prose |

---

## 4. The Two Agents

### Write Agent

```java
McpAgentExecutor writeAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")          // read_file / write_file / ...
    .systemPrompt(
        "You are a Java implementation expert.\n" +
        "Task: write or fix a Java class and save it to the specified file path.\n" +
        "Rules:\n" +
        "  1. Do NOT include any package declaration.\n" +
        "  2. The class must be public and have a public method matching the required signature.\n" +
        "  3. Use the write_file tool to save the file.\n" +
        "  4. Reply only with confirmation that the file was written."
    )
    .maxIterations(5)
    .build();
```

Write Agent has only MCP filesystem tools, single responsibility: **write code to a file**.

### Test Agent

```java
McpAgentExecutor testAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")          // read_file / write_file / ...
    .tools(runner)                           // compile_and_run tool
    .systemPrompt(
        "You are a Java testing expert. You will be given EXACTLY three tasks to execute in sequence.\n" +
        "CRITICAL: Call each tool exactly once, in this order, then stop:\n" +
        "  CALL 1 — read_file: read the implementation file.\n" +
        "  CALL 2 — write_file: write a JUnit 4 test class to the test file path.\n" +
        "  CALL 3 — compile_and_run: pass the implementation file path and test file path.\n" +
        "After compile_and_run returns, output its result verbatim and make NO further tool calls."
    )
    .maxIterations(12)
    .build();
```

Test Agent has MCP filesystem + `compile_and_run`, single responsibility: **write tests, compile, run, report results**.

> `McpAgentExecutor.Builder` supports chaining multiple `.tools()` calls — MCP tools and custom tools coexist naturally.

---

## 5. The Loop Logic

```java
.loop(
    // condition: continue if test_result is FAIL, max 3 rounds
    i -> {
        String testResult = ContextBus.get().getTransmit("test_result");
        boolean failed = testResult != null && testResult.startsWith("FAIL");
        return (i == 0 || failed) && i < 3;
    },

    // Node A: Write Agent — writes initial impl or fixes based on failures
    (Object input) -> {
        String req        = ContextBus.get().getTransmit("requirement");
        String testResult = ContextBus.get().getTransmit("test_result");
        String prompt = testResult == null
            ? "Write Palindrome.java ... save to " + IMPL_FILE
            : "Fix " + IMPL_FILE + " to pass failures: " + testResult;
        return writeAgent.invoke(prompt);
    },

    // Node B: round 1 — Test Agent writes tests and runs; round 2+ — call runner directly
    (Object writeResult) -> {
        String testsWritten = ContextBus.get().getTransmit("tests_written");
        if (testsWritten == null) {
            ContextBus.get().putTransmit("tests_written", "true");
            return testAgent.invoke("read_file, write tests to " + TEST_FILE + ", compile_and_run");
        } else {
            return runner.compileAndRun(IMPL_FILE, TEST_FILE);  // bypass LLM
        }
    }
)
```

### Shared State in `transmitMap`

| Key | Written by | Read by | Purpose |
|-----|-----------|---------|---------|
| `requirement` | Pre-loop TranslateHandler | Write Agent / Test Agent (every round) | Preserve the original requirement across rounds |
| `test_result` | `compile_and_run` tool | loop condition | `startsWith("FAIL")` determines whether to continue |
| `tests_written` | testNode lambda | testNode lambda (next round) | Flag so tests are only written once; subsequent rounds just re-run |

### "Write Tests Once" Design

The Test Agent writes the test file only in round 1. Round 2+ calls `runner.compileAndRun()` directly, bypassing the LLM entirely. This keeps test cases stable across rounds — what changes is the implementation, not the tests.

---

## 6. Execution Trace

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

If round 1 tests fail, Write Agent reads the failure message in round 2 and fixes the implementation; Test Agent re-runs the same test suite until it passes.

---

## 7. Prerequisites

1. **JDK (not JRE)**: `compile_and_run` uses `javax.tools.JavaCompiler`, which is only in JDK
2. **Node.js**: MCP filesystem server is started via `npx`
3. **`mcp.server.config.json`**: `filesystem` server `args` configured with `/private/tmp` (macOS) or `/tmp` (Linux)
4. **`ALIYUN_KEY`** environment variable: example uses `qwen3.6-plus`

---

## 8. Summary

This article demonstrated the complete dual-agent self-correcting pattern in j-langchain:

- **Separation of concerns**: Write Agent only writes code; Test Agent only validates — each `McpAgentExecutor` is exposed only the tools relevant to its role
- **Real end-to-end loop**: LLM generates → MCP writes files → `javac` compiles → JUnit runs — no mocks in the production path
- **Transparent state**: `compile_and_run` writes to ContextBus directly; the loop condition reads a clean PASS/FAIL string without parsing LLM prose
- **Stable tests**: the `tests_written` flag ensures test cases are written once; subsequent rounds validate the implementation, not the tests

This "two specialist agents + `loop()` coordinator" pattern generalizes to any generate → validate → fix cycle: SQL generation + execution validation, config generation + syntax check, API code generation + integration tests, and more.

---

> 📎 Resources
> - Full example: [Article20TwoAgentSelfCorrect.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article20TwoAgentSelfCorrect.java), method `twoAgentSelfCorrect()`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime requirements: JDK 17+, Node.js, `ALIYUN_KEY` (`qwen3.6-plus`)
