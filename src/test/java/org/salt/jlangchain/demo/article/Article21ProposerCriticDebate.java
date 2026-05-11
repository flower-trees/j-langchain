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
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.config.JLangchainConfigTest;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

/**
 * 文章 21：Proposer-Critic 多轮辩论——两个 LLM Agent 用 loop() 逼近共识。
 *
 * <p>Proposer Agent 提出或修改方案；Critic Agent 评审并给出 [CRITIQUE] 或 [APPROVED]；
 * loop() 节点检查 consensus 标志，驱动辩论直至达成共识或到达最大轮次。
 * 两个 Agent 均为纯 LLM 调用，无需任何工具。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article21ProposerCriticDebate {

    @Autowired
    private ChainActor chainActor;

    @Test
    public void proposerCriticDebate() {

        // ── Proposer：较高 temperature，鼓励创造性方案 ──────────────────────────
        FlowInstance proposerFlow = chainActor.builder()
            .next(PromptTemplate.fromTemplate("${prompt}"))
            .next(ChatAliyun.builder().model("deepseek-v4-flash").temperature(0.7f).build())
            .next(new StrOutputParser())
            .build();

        // ── Critic：较低 temperature，保持评审的稳定性与严格性 ──────────────────
        FlowInstance criticFlow = chainActor.builder()
            .next(PromptTemplate.fromTemplate("${prompt}"))
            .next(ChatAliyun.builder().model("deepseek-v4-flash").temperature(0.3f).build())
            .next(new StrOutputParser())
            .build();

        // ── 流水线：前置处理 → loop(提案→评审) → 格式化输出 ──────────────────────
        FlowInstance flow = chainActor.builder()

            // 把议题存入 transmitMap，每轮均可读取
            .next(new TranslateHandler<>(input -> {
                System.out.println("\n========== Proposer-Critic 多轮辩论 ==========");
                System.out.println("议题：" + input);
                ContextBus.get().putTransmit("topic", input.toString());
                return input;
            }))

            // loop(condition, proposerNode, criticNode)，最多 5 轮
            .loop(
                i -> {
                    boolean approved = "true".equals(ContextBus.get().getTransmit("consensus"));
                    boolean cont = !approved && i < 5;
                    if (i > 0) {
                        System.out.printf("%n--- 第%d轮条件检查：approved=%b，继续=%b ---%n",
                            i + 1, approved, cont);
                    }
                    return cont;
                },

                // 节点A ── Proposer：第 1 轮提出初始方案，后续轮次根据批评修改
                (Object input) -> {
                    String topic    = ContextBus.get().getTransmit("topic");
                    String critique = ContextBus.get().getTransmit("critique");
                    String proposal = ContextBus.get().getTransmit("proposal");

                    String prompt;
                    if (critique == null) {
                        System.out.println("\n--- 轮次1：Proposer 提出初始方案 ---");
                        prompt = "你是一位资深架构师。请针对以下议题提出一个技术方案（300字以内）：\n" + topic;
                    } else {
                        System.out.println("\n--- Proposer 修改方案 ---");
                        prompt = "你是一位资深架构师。你之前的方案：\n" + proposal +
                                 "\n\n评审意见：\n" + critique +
                                 "\n\n请根据评审意见修改并改进方案（300字以内）。只输出修改后的方案正文，不要解释修改了什么。";
                    }

                    ChatGeneration result = chainActor.invoke(proposerFlow, Map.of("prompt", prompt));
                    ContextBus.get().putTransmit("proposal", result.getText());
                    System.out.println("[Proposer]\n" + result.getText());
                    return result;
                },

                // 节点B ── Critic：评审当前方案，输出 [APPROVED] 或 [CRITIQUE]
                (Object ignored) -> {
                    String topic    = ContextBus.get().getTransmit("topic");
                    String proposal = ContextBus.get().getTransmit("proposal");

                    String prompt = "你是一位严格的技术评审专家。议题：" + topic +
                                    "\n\n当前方案：\n" + proposal +
                                    "\n\n请评审此方案：" +
                                    "\n- 若方案足够完善，回复以 [APPROVED] 开头，说明认可理由（100字以内）。" +
                                    "\n- 若存在明显缺陷，回复以 [CRITIQUE] 开头，列出问题（100字以内），不给修改建议。";

                    ChatGeneration result = chainActor.invoke(criticFlow, Map.of("prompt", prompt));
                    String text = result.getText();

                    if (text.startsWith("[APPROVED]")) {
                        ContextBus.get().putTransmit("consensus", "true");
                        System.out.println("[Critic - 达成共识]\n" + text);
                    } else {
                        ContextBus.get().putTransmit("critique", text);
                        System.out.println("[Critic]\n" + text);
                    }
                    return result;
                }
            )

            // 格式化最终输出
            .next(new TranslateHandler<>(output -> {
                String proposal  = ContextBus.get().getTransmit("proposal");
                String consensus = ContextBus.get().getTransmit("consensus");
                String status    = "true".equals(consensus) ? "✅ 达成共识" : "⚠️ 已达最大轮次";
                return "\n========== 最终方案 ==========\n状态：" + status +
                       "\n\n" + proposal +
                       "\n================================";
            }))

            .build();

        String result = chainActor.invoke(flow,
            "设计一个支持高并发的分布式任务调度系统，要求支持百万级任务、秒级延迟、故障自动恢复");
        System.out.println(result);
    }
}
