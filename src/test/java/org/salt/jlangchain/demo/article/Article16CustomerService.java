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
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutor;
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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文章 16：双 Agent 串联的客服工单处理链。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article16CustomerService {

    @Autowired
    private ChainActor chainActor;

    @Autowired
    private McpClient mcpClient;

    /**
     * 客服工单查询工具类（ReAct Agent 使用）
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

    @Test
    public void dualAgentChain() {
        AgentExecutor analysisAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(new OrderTools())
            .maxIterations(10)
            .onThought(t -> System.out.println("[分析] " + t))
            .onObservation(obs -> System.out.println("[查询结果] " + obs))
            .build();

        McpAgentExecutor executionAgent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(mcpClient, "filesystem")
            .systemPrompt(
                "你是一个工单处理系统，负责将处理结论写入文件并读取确认。" +
                "完成所有文件操作后，输出操作完成的确认信息，不要再调用任何工具。")
            .maxIterations(6)
            .onToolCall(tc -> System.out.println("[执行] " + tc))
            .onObservation(obs -> System.out.println("[执行结果] " + obs))
            .build();

        FlowInstance workflowChain = chainActor.builder()
            .next(new TranslateHandler<>(complaint -> {
                System.out.println("\n========== 收到投诉工单 ==========");
                System.out.println(complaint);
                System.out.println("\n--- Agent1：开始分析投诉 ---");
                return "用户投诉内容：" + complaint +
                    "\n请你：1)查询订单详情和物流状态；2)查询适用的退款政策；" +
                    "3)判断是否符合退款条件；4)给出明确的处理建议（批准退款/换货/拒绝）和理由。";
            }))
            .next(analysisAgent)
            .next(new TranslateHandler<String, ChatGeneration>(generation -> {
                String analysis = generation.getText();
                System.out.println("\n--- Agent1 分析完成，移交 Agent2 执行 ---");
                System.out.println("分析结论：" + analysis);
                System.out.println("\n--- Agent2：开始执行处理操作 ---");
                return "请将以下处理记录写入文件 /private/tmp/ticket_result.txt，" +
                    "然后读取文件内容确认写入成功：\n\n" +
                    "=== 工单处理记录 ===\n" +
                    "处理时间：" + LocalDateTime.now() + "\n" +
                    "处理结论：\n" + analysis;
            }))
            .next(executionAgent)
            .next(new TranslateHandler<String, ChatGeneration>(generation -> {
                System.out.println("\n--- 生成最终客服回复 ---");
                return "\n========== 工单处理完成 ==========\n" +
                    "处理记录已保存至 /private/tmp/ticket_result.txt\n" +
                    "客服系统处理结果：" + generation.getText() +
                    "\n===================================";
            }))
            .build();

        String finalReply = chainActor.invoke(workflowChain, Map.of(
            "input",
            "我在3月10日购买了 iPhone 15 Pro（订单号 ORD-2024-001），" +
            "收到后发现屏幕有亮点，距签收才3天，要求退款。"
        ));

        System.out.println(finalReply);
    }
}
