/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.jlangchain.demo.article;

import org.junit.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.config.JLangchainConfigTest;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.tools.*;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

/**
 * 文章 20：双 Agent 自我纠错代码生成——Write Agent + Test Agent 驱动 loop() 循环。
 *
 * <p>Write Agent（McpAgentExecutor）通过 MCP filesystem 工具写入 Java 实现；
 * Test Agent（McpAgentExecutor）通过 MCP filesystem 写入 JUnit 测试，再调用
 * compile_and_run 工具完成真实编译与执行；loop() 节点在测试失败时触发 Write Agent
 * 修复代码，直至测试通过或达到最大轮次。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article20TwoAgentSelfCorrect {

    // mcp.server.config.json 中 filesystem 服务器的允许目录需与 GEN_DIR 的根路径一致：
    //   macOS: /private/tmp（/tmp 是符号链接，MCP 解析后得到 /private/tmp）
    //   Linux: /tmp
    @Autowired
    private McpClient mcpClient;

    @Autowired
    private ChainActor chainActor;

    // 对 /tmp 做符号链接解析，得到与 MCP 服务器一致的真实路径。
    // 注意：不能用 java.io.tmpdir，macOS 上它返回 /var/folders/... 而非 /tmp。
    static final String GEN_DIR;
    static final String IMPL_FILE;
    static final String TEST_FILE;
    static {
        try {
            String tmp = new java.io.File("/tmp").getCanonicalPath(); // macOS→/private/tmp，Linux→/tmp
            GEN_DIR   = tmp + java.io.File.separator + "gen";
            IMPL_FILE = GEN_DIR + java.io.File.separator + "Palindrome.java";
            TEST_FILE = GEN_DIR + java.io.File.separator + "PalindromeTest.java";
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ── compile_and_run 工具：进程内编译 + JUnit 执行 ────────────────────────
    static class JavaTestRunner {

        @AgentTool("编译Java实现类和测试类，用JUnit 4运行所有测试，返回PASS/FAIL及详情")
        public String compileAndRun(
            @Param("实现类Java文件绝对路径，如 /tmp/gen/Palindrome.java") String implFile,
            @Param("测试类Java文件绝对路径，如 /tmp/gen/PalindromeTest.java") String testFile
        ) {
            String result = doCompileAndRun(implFile, testFile);
            // 写入共享状态，loop condition 直接读取，无需解析 LLM 的自然语言输出
            ContextBus.get().putTransmit("test_result", result);
            return result;
        }

        private String doCompileAndRun(String implFile, String testFile) {
            try {
                // 1. 校验文件是否存在
                if (!new File(implFile).exists()) return "FAIL: Implementation file not found: " + implFile;
                if (!new File(testFile).exists())  return "FAIL: Test file not found: " + testFile;

                // 2. 编译：通过 javax.tools.JavaCompiler 进程内编译，classpath 含 JUnit JAR
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    return "FAIL: javax.tools.JavaCompiler not available. Ensure this runs with a JDK (not JRE).";
                }

                File outputDir = new File(GEN_DIR);
                outputDir.mkdirs();
                String classpath = System.getProperty("java.class.path");

                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
                    Iterable<? extends JavaFileObject> sources = fm.getJavaFileObjects(implFile, testFile);
                    List<String> options = Arrays.asList("-cp", classpath, "-d", GEN_DIR);
                    boolean compiled = compiler.getTask(null, fm, diagnostics, options, null, sources).call();
                    if (!compiled) {
                        StringBuilder sb = new StringBuilder("FAIL: Compilation errors\n");
                        diagnostics.getDiagnostics().stream()
                            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                            .forEach(d -> sb.append("  [").append(new File(d.getSource().toUri()).getName())
                                .append(" line ").append(d.getLineNumber()).append("] ")
                                .append(d.getMessage(null)).append("\n"));
                        return sb.toString();
                    }
                }

                // 3. 每次新建 URLClassLoader 加载最新 .class，避免 JVM 缓存旧版本
                try (URLClassLoader loader = new URLClassLoader(
                        new URL[]{outputDir.toURI().toURL()},
                        Thread.currentThread().getContextClassLoader())) {

                    String testClassName = new File(testFile).getName().replace(".java", "");
                    Class<?> testClass = loader.loadClass(testClassName);

                    Result result = new JUnitCore().run(testClass);

                    if (result.wasSuccessful()) {
                        return "PASS: All " + result.getRunCount() + " tests passed ✅";
                    }
                    StringBuilder sb = new StringBuilder(
                        "FAIL: " + result.getFailureCount() + "/" + result.getRunCount() + " tests failed\n");
                    for (Failure f : result.getFailures()) {
                        sb.append("  ❌ ").append(f.getTestHeader()).append(": ")
                          .append(f.getMessage()).append("\n");
                    }
                    return sb.toString();
                }
            } catch (Exception e) {
                return "FAIL: Unexpected error — " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    @Test
    public void twoAgentSelfCorrect() {

        // 提前创建输出目录，MCP filesystem 写文件前目录必须存在
        new File(GEN_DIR).mkdirs();

        // ── Write Agent：通过 MCP filesystem 工具写入或修复 Java 实现 ──
        McpAgentExecutor writeAgent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(mcpClient, "filesystem")
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
            .onToolCall(tc -> System.out.println("[WriteAgent] tool call: " + tc))
            .onObservation(obs -> System.out.println("[WriteAgent] observation: " + obs))
            .build();

        // 共享 runner 实例：第 1 轮由 Test Agent 调用，第 2 轮起直接调用跳过 LLM
        JavaTestRunner runner = new JavaTestRunner();

        // ── Test Agent：第 1 轮负责读取实现、编写 JUnit 测试、编译并执行 ──
        McpAgentExecutor testAgent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(mcpClient, "filesystem")
            .tools(runner)
            .systemPrompt(
                "You are a Java testing expert. You will be given EXACTLY three tasks to execute in sequence.\n" +
                "CRITICAL: Call each tool exactly once, in this order, then stop:\n" +
                "  CALL 1 — read_file: read the implementation file.\n" +
                "  CALL 2 — write_file: write a JUnit 4 test class to the test file path.\n" +
                "            Rules: no package declaration; import org.junit.Test; import static org.junit.Assert.*;\n" +
                "            Annotate each test method with @Test; cover normal and edge cases.\n" +
                "  CALL 3 — compile_and_run: pass the implementation file path and test file path.\n" +
                "After compile_and_run returns, output its result verbatim and make NO further tool calls."
            )
            .maxIterations(12)
            .onToolCall(tc -> System.out.println("[TestAgent] tool call: " + tc))
            .onObservation(obs -> System.out.println("[TestAgent] observation: " + obs))
            .build();

        // ── 流水线：前置处理 → loop(写→测) → 格式化输出 ──────────────────────
        FlowInstance flow = chainActor.builder()

            // 把原始需求存入 transmitMap，每轮 Agent 都可以直接读取
            .next(new TranslateHandler<>(input -> {
                System.out.println("\n========== 双Agent自我纠错代码生成 ==========");
                System.out.println("需求：" + input);
                ContextBus.get().putTransmit("requirement", input.toString());
                return input;
            }))

            // loop(condition, writeNode, testNode)，最多 3 轮
            .loop(
                i -> {
                    String testResult = ContextBus.get().getTransmit("test_result");
                    boolean failed = testResult != null && testResult.startsWith("FAIL");
                    boolean cont = (i == 0 || failed) && i < 3;
                    if (i > 0) {
                        System.out.printf("%n--- Loop 条件检查：第%d轮，failed=%b，继续=%b ---%n",
                            i + 1, failed, cont);
                    }
                    return cont;
                },

                // 节点A ── Write Agent：第 1 轮写初始实现，第 2 轮起根据失败信息修复
                (Object input) -> {
                    String req        = ContextBus.get().getTransmit("requirement");
                    String testResult = ContextBus.get().getTransmit("test_result");

                    String prompt;
                    if (testResult == null) {
                        System.out.println("\n--- 步骤1：Write Agent 编写初始实现 ---");
                        prompt = String.format(
                            "Write a Java class named 'Palindrome' with the method " +
                            "'public boolean isPalindrome(String s)' that satisfies: %s%n" +
                            "Save it to: %s", req, IMPL_FILE);
                    } else {
                        System.out.println("\n--- Write Agent 修复实现 ---");
                        prompt = String.format(
                            "The implementation at %s has test failures. Fix it.%n" +
                            "Test failures:%n%s%n" +
                            "Requirement: %s%n" +
                            "Overwrite %s with the corrected implementation.",
                            IMPL_FILE, testResult, req, IMPL_FILE);
                    }

                    ChatGeneration result = writeAgent.invoke(prompt);
                    String summary = result.getText();
                    System.out.println("[WriteAgent 完成] " +
                        summary.substring(0, Math.min(120, summary.length())));
                    return result;
                },

                // 节点B ── 第 1 轮：Test Agent 编写测试并执行；第 2 轮起直接调用 runner 跳过 LLM
                (Object writeResult) -> {
                    String req          = ContextBus.get().getTransmit("requirement");
                    String testsWritten = ContextBus.get().getTransmit("tests_written");

                    if (testsWritten == null) {
                        // 第 1 轮：Agent 读取实现 → 编写 JUnit 测试 → 编译并执行
                        System.out.println("\n--- 步骤2：Test Agent 编写测试并执行 ---");
                        ContextBus.get().putTransmit("tests_written", "true");
                        String prompt = String.format(
                            "TASK — execute these three tool calls in order:%n" +
                            "  1. read_file path=\"%s\"%n" +
                            "  2. write_file path=\"%s\" content=<JUnit4 tests for: %s>%n" +
                            "  3. compile_and_run implFile=\"%s\" testFile=\"%s\"%n" +
                            "Output the compile_and_run result verbatim.",
                            IMPL_FILE, TEST_FILE, req, IMPL_FILE, TEST_FILE);
                        ChatGeneration result = testAgent.invoke(prompt);
                        System.out.println("[TestAgent 完成]\n" + result.getText());
                        return result;
                    } else {
                        // 第 2 轮起：跳过 LLM，直接编译并执行已有的测试文件
                        System.out.println("\n--- 直接重新编译并执行测试 ---");
                        String result = runner.compileAndRun(IMPL_FILE, TEST_FILE);
                        System.out.println("[直接执行结果]\n" + result);
                        return result;
                    }
                }
            )

            // 格式化最终输出
            .next(new TranslateHandler<>(output -> {
                String testResult = ContextBus.get().getTransmit("test_result");
                String finalText = output instanceof ChatGeneration g ? g.getText() : output.toString();
                return "\n========== 最终结果 ==========\n" + finalText +
                    "\n\n测试状态：" + testResult +
                    "\n生成文件：" + IMPL_FILE + "  " + TEST_FILE +
                    "\n================================";
            }))

            .build();

        String result = chainActor.invoke(flow, "判断字符串是否为回文，忽略大小写和非字母数字字符");
        System.out.println(result);
    }
}
