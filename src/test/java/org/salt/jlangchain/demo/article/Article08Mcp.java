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
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.config.JLangchainConfigTest;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.salt.jlangchain.rag.tools.mcp.server.McpServerConnection;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章8：在 Java AI 应用中集成 MCP 工具协议
 *
 * MCP（Model Context Protocol）是 Anthropic 主导的 AI 工具标准协议（2024年发布）。
 *
 * 演示内容：
 * 1. mcpManagerManifest     - 列出所有已注册的 MCP 工具
 * 2. mcpManagerRun          - 通过 McpManager 调用工具
 * 3. mcpClientListTools     - 通过 McpClient 列出 NPX MCP 服务器的工具
 * 4. mcpMemoryServerConnect - 连接 MCP 服务器并调用工具（Memory Server）
 * 5. mcpPostgresConnect     - 连接 PostgreSQL MCP 服务器
 * 6. dualAgentChain         - AgentExecutor + McpAgentExecutor 双 Agent 串联
 *
 * <p>McpAgentExecutor 单源/混合示例已拆至文章 11～13：
 * {@link Article11McpManagerAgent}、{@link Article12McpClientAgent}、{@link Article13McpMixedAgent}。
 *
 * 配置文件：
 * - mcp.config.json          : HTTP API 工具配置
 * - mcp.server.config.json   : NPX MCP 服务器配置
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article08Mcp {

    @Autowired
    private McpManager mcpManager;

    @Autowired
    private McpClient mcpClient;

    @Autowired
    private ChainActor chainActor;

    /**
     * 查看所有已注册的 MCP 工具清单
     * McpManager 从 mcp.config.json 加载 HTTP API 工具
     */
    @Test
    public void mcpManagerManifest() {
        System.out.println("=== MCP 工具清单 ===");
        System.out.println(JsonUtil.toJson(mcpManager.manifest()));
        System.out.println();
        System.out.println("=== MCP 工具（带输入格式）===");
        System.out.println(JsonUtil.toJson(mcpManager.manifestForInput()));
    }

    /**
     * 通过 McpManager 调用 HTTP API 工具
     * 工具定义来自 mcp.config.json
     *
     * mcp.config.json 示例：
     * {
     *   "default": [
     *     {
     *       "name": "get_export_ip",
     *       "description": "Get my network export IP",
     *       "url": "http://ipinfo.io/ip",
     *       "method": "GET"
     *     }
     *   ]
     * }
     */
    @Test
    public void mcpManagerRun() throws Exception {
        System.out.println("=== 调用 HTTP API 工具：get_export_ip ===");

        // run：直接调用并返回结果
        Object result = mcpManager.run("default", "get_export_ip", Map.of());
        System.out.println("结果：" + JsonUtil.toJson(result));

        // runForInput：返回 LLM 格式化输入（用于注入 Agent Prompt）
        Object inputResult = mcpManager.runForInput("default", "get_export_ip", Map.of());
        System.out.println("LLM 格式化输入：" + JsonUtil.toJson(inputResult));
    }

    /**
     * 通过 McpClient 列出 NPX MCP 服务器上的所有工具
     * McpClient 从 mcp.server.config.json 加载 NPX MCP 服务器配置
     *
     * mcp.server.config.json 示例：
     * {
     *   "mcpServers": {
     *     "filesystem": {
     *       "command": "npx",
     *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
     *     },
     *     "memory": {
     *       "command": "npx",
     *       "args": ["-y", "@modelcontextprotocol/server-memory"]
     *     }
     *   }
     * }
     */
    @Test
    public void mcpClientListTools() {
        System.out.println("=== NPX MCP 服务器工具列表 ===");
        System.out.println(JsonUtil.toJson(mcpClient.listAllTools()));
    }

    /**
     * 直接连接 Memory MCP 服务器并调用工具
     * Memory Server 提供持久化键值存储，适合 Agent 记忆场景
     */
    @Test
    public void mcpMemoryServerConnect() throws Exception {
        // 配置 Memory MCP 服务器
        ServerConfig config = new ServerConfig();
        config.command = "npx";
        config.args = List.of("-y", "@modelcontextprotocol/server-memory");
        config.env = new HashMap<>();

        McpServerConnection connection = new McpServerConnection("memory-server", config);
        connection.connect();

        System.out.println("=== Memory MCP 服务器 ===");
        System.out.println("服务器名称：" + connection.getServerName());
        System.out.println("连接状态：" + connection.isConnected());
        System.out.println("工具列表：" + JsonUtil.toJson(connection.listTools()));

        // 调用工具
        System.out.println("调用 search_nodes：" +
            JsonUtil.toJson(connection.callTool("search_nodes", new HashMap<>())));
    }

    /**
     * 连接 PostgreSQL MCP 服务器
     * 让 Agent 能够直接查询数据库，无需手写 SQL 查询代码
     */
    @Test
    public void mcpPostgresConnect() throws Exception {
        ServerConfig config = new ServerConfig();
        config.command = "npx";
        config.args = List.of(
            "-y",
            "@modelcontextprotocol/server-postgres",
            "postgresql://myuser:123456@localhost:5432/mydb"  // 替换为实际连接串
        );
        config.env = new HashMap<>();

        McpServerConnection connection = new McpServerConnection("postgres-server", config);
        connection.connect();

        System.out.println("=== PostgreSQL MCP 服务器 ===");
        System.out.println("连接状态：" + connection.isConnected());
        System.out.println("可用工具：" + JsonUtil.toJson(connection.listTools()));

        // 有了 MCP，Agent 可以直接执行 SQL，无需你写任何 JDBC 代码
        // connection.callTool("query", Map.of("sql", "SELECT * FROM users LIMIT 5"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 客服工单处理工具（给 Agent1 使用）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 客服工单查询工具类（ReAct Agent 使用）
     *
     * 提供订单查询、物流状态、退款规则等工具，
     * 供 Agent1 分析投诉、判断是否符合退款条件。
     */
    static class OrderTools {

        @AgentTool("查询订单详情")
        public String getOrderDetail(@Param("订单号，格式如 ORD-2024-001") String orderId) {
            return switch (orderId) {
                case "ORD-2024-001" -> "订单 ORD-2024-001：iPhone 15 Pro，¥8999，2024-03-10下单，已签收";
                case "ORD-2024-002" -> "订单 ORD-2024-002：笔记本电脑，¥5999，2024-03-12下单，运输中";
                default -> "订单 " + orderId + " 不存在";
            };
        }

        @AgentTool("查询订单物流状态")
        public String getLogisticsStatus(@Param("订单号，格式如 ORD-2024-001") String orderId) {
            return switch (orderId) {
                case "ORD-2024-001" -> "物流状态：已于2024-03-13签收，签收人：本人，快递员：张师傅";
                case "ORD-2024-002" -> "物流状态：2024-03-14 到达上海转运中心，预计明日送达";
                default -> "未找到物流记录";
            };
        }

        @AgentTool("查询退款政策")
        public String getRefundPolicy(@Param("商品类别，如：手机、电脑") String category) {
            return switch (category) {
                case "手机" -> "手机退款政策：7天无理由退货，15天质量问题换货，需保持原包装完整";
                case "电脑" -> "电脑退款政策：7天无理由退货，30天内质量问题免费维修或换货";
                default -> category + "退款政策：7天无理由退货，需保持商品完好";
            };
        }

        @AgentTool("判断投诉是否符合退款条件")
        public String checkRefundEligibility(
                @Param("订单号") String orderId,
                @Param("投诉原因") String reason,
                @Param("距签收天数") String daysSinceReceived) {
            int days = Integer.parseInt(daysSinceReceived.replaceAll("[^0-9]", ""));
            if (days <= 7) {
                return String.format("订单 %s 符合退款条件：%s，距签收 %d 天，在7天无理由退货期内。建议全额退款 ¥8999。", orderId, reason, days);
            } else if (days <= 15 && reason.contains("质量")) {
                return String.format("订单 %s 符合换货条件：%s，距签收 %d 天，在15天质量问题换货期内。建议安排换货。", orderId, reason, days);
            } else {
                return String.format("订单 %s 不符合退款条件：距签收已 %d 天，超出退货期限。建议转人工处理。", orderId, days);
            }
        }
    }

    /**
     * 双 Agent 串联 chain：客服工单自动处理
     *
     * 场景：用户提交投诉工单，系统自动完成从"分析投诉"到"记录处理结果"的全流程。
     *
     * chain 结构：
     *   TranslateHandler（工单格式化）
     *     → Agent1: AgentExecutor（ReAct）
     *         工具：查订单、查物流、查退款政策、判断退款资格
     *         输出：退款分析报告（是否批准、金额、原因）
     *     → TranslateHandler（提取分析结论，拼装执行指令）
     *     → Agent2: McpAgentExecutor（Function Calling）
     *         工具：filesystem MCP（写入处理记录、读取确认）
     *         输出：操作完成确认
     *     → TranslateHandler（生成最终回复）
     *
     * 体现两类 Agent 的分工：
     *   - AgentExecutor 适合需要多步推理、条件判断的分析类任务（ReAct 文本解析）
     *   - McpAgentExecutor 适合执行类任务，直接操作外部系统（Function Calling 结构化调用）
     */
    @Test
    public void dualAgentChain() {

        // ── Agent1：投诉分析 Agent（ReAct 模式，擅长多步推理） ──
        AgentExecutor analysisAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(new OrderTools())
            .maxIterations(10)
            .onThought(t -> System.out.println("[分析] " + t))
            .onObservation(obs -> System.out.println("[查询结果] " + obs))
            .build();

        // ── Agent2：执行 Agent（Function Calling 模式，擅长操作外部系统） ──
        McpAgentExecutor executionAgent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(mcpClient, "filesystem")   // NPX filesystem MCP：写文件记录处理结果
            .systemPrompt(
                "你是一个工单处理系统，负责将处理结论写入文件并读取确认。" +
                "完成所有文件操作后，输出操作完成的确认信息，不要再调用任何工具。")
            .maxIterations(6)
            .onToolCall(tc -> System.out.println("[执行] " + tc))
            .onObservation(obs -> System.out.println("[执行结果] " + obs))
            .build();

        // ── 组装双 Agent chain ──
        FlowInstance workflowChain = chainActor.builder()
            // Step1：格式化用户投诉为结构化工单
            .next(new TranslateHandler<>(complaint -> {
                System.out.println("\n========== 收到投诉工单 ==========");
                System.out.println(complaint);
                System.out.println("\n--- Agent1：开始分析投诉 ---");
                return "用户投诉内容：" + complaint +
                    "\n请你：1)查询订单详情和物流状态；2)查询适用的退款政策；" +
                    "3)判断是否符合退款条件；4)给出明确的处理建议（批准退款/换货/拒绝）和理由。";
            }))
            // Step2：Agent1 分析投诉，调用业务工具，输出处理建议
            .next(analysisAgent)
            // Step3：提取 Agent1 结论，拼装成 Agent2 的执行指令
            .next(new TranslateHandler<String, ChatGeneration>(generation -> {
                String analysis = generation.getText();
                System.out.println("\n--- Agent1 分析完成，移交 Agent2 执行 ---");
                System.out.println("分析结论：" + analysis);
                System.out.println("\n--- Agent2：开始执行处理操作 ---");
                return "请将以下处理记录写入文件 /private/tmp/ticket_result.txt，" +
                    "然后读取文件内容确认写入成功：\n\n" +
                    "=== 工单处理记录 ===\n" +
                    "处理时间：" + java.time.LocalDateTime.now() + "\n" +
                    "处理结论：\n" + analysis;
            }))
            // Step4：Agent2 将处理结论写入文件系统（MCP filesystem 工具）
            .next(executionAgent)
            // Step5：生成最终客服回复
            .next(new TranslateHandler<String, ChatGeneration>( generation -> {
                System.out.println("\n--- 生成最终客服回复 ---");
                return "\n========== 工单处理完成 ==========\n" +
                    "处理记录已保存至 /private/tmp/ticket_result.txt\n" +
                    "客服系统处理结果：" + generation.getText() +
                    "\n===================================";
            }))
            .build();

        // ── 执行：模拟用户提交投诉 ──
        String finalReply = chainActor.invoke(workflowChain, Map.of(
            "input",
            "我在3月10日购买了 iPhone 15 Pro（订单号 ORD-2024-001），" +
            "收到后发现屏幕有亮点，距签收才3天，要求退款。"
        ));

        System.out.println(finalReply);
    }
}
