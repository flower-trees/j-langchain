# Proposer-Critic 多轮辩论：两个 LLM Agent 用 loop() 逼近共识

> **标签**：`Java` `loop` `Proposer-Critic` `多轮辩论` `共识` `纯LLM` `无工具`  
> **前置阅读**：[双 Agent 自我纠错代码生成：Write Agent + Test Agent 驱动 loop() 循环](20-two-agent-self-correct.md)  
> **适合人群**：已掌握 `loop()` 基础，希望用最轻量的方式构建多 Agent 辩论流程的 Java 开发者

---

## 一、问题：为什么需要辩论模式

单个 LLM 生成方案时存在一个典型缺陷：**它不会主动否定自己**。一旦 LLM 产出了答案，它倾向于自我肯定而非自我审视。

Proposer-Critic 模式通过**角色分离**打破这一局限：

- **Proposer**（提案者）：专注创造，提出方案或根据批评修改
- **Critic**（评审者）：专注挑错，发现缺陷或认可最终方案
- **loop()**：协调两者反复迭代，直至 Critic 满意或达到最大轮次

与前一篇双 Agent 纠错不同，这里两个 Agent **都不需要工具**——纯 LLM 调用，配置最简单，是 loop() 多 Agent 模式的最轻量形态。

---

## 二、整体架构

```
用户议题（"设计一个分布式任务调度系统..."）
     ↓
TranslateHandler（保存议题到 ContextBus transmitMap）
     ↓
┌──────────────────────────────────────────────────────────┐
│  loop(condition, proposerNode, criticNode)   最多 5 轮    │
│                                                          │
│  proposerNode (Proposer Agent)                           │
│    纯 LLM 调用（PromptTemplate + ChatAliyun）             │
│    第 1 轮：生成初始方案 → 写入 transmitMap              │
│    第 2 轮起：读取上轮 critique → 修改方案 → 写入        │
│                                                          │
│  criticNode (Critic Agent)                               │
│    纯 LLM 调用（PromptTemplate + ChatAliyun）             │
│    每轮：读取当前方案 → 输出 [CRITIQUE] 或 [APPROVED]    │
│    [APPROVED] 时写入 consensus=true                      │
└──────────────────────────────────────────────────────────┘
     ↓
TranslateHandler（格式化最终方案）
     ↓
最终方案 + 状态（✅ 达成共识 / ⚠️ 已达最大轮次）
```

共享状态只有三个 key，职责清晰：

| key | 写入方 | 读取方 | 用途 |
|-----|--------|--------|------|
| `topic` | 前置 TranslateHandler | Proposer / Critic（每轮） | 保存原始议题 |
| `proposal` | proposerNode lambda | criticNode lambda | 当前方案全文 |
| `critique` | criticNode lambda | proposerNode lambda（下一轮） | 上轮批评意见 |
| `consensus` | criticNode lambda | loop condition | `"true"` 时终止循环 |

---

## 三、两个 Agent 的配置

两个 Agent 都是普通的 LLM 调用链，不需要 `McpAgentExecutor`，也不需要任何工具。

### Proposer

```java
FlowInstance proposerFlow = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${prompt}"))
    .next(ChatAliyun.builder().model("deepseek-v4-flash").temperature(0.7f).build())
    .next(new StrOutputParser())
    .build();
```

较高的 `temperature(0.7f)` 鼓励 Proposer 输出更有创意的方案。末尾的 `StrOutputParser` 将 LLM 返回的 `AIMessage` 包装为 `ChatGeneration`，方便 lambda 直接调用 `.getText()`。

### Critic

```java
FlowInstance criticFlow = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${prompt}"))
    .next(ChatAliyun.builder().model("deepseek-v4-flash").temperature(0.3f).build())
    .next(new StrOutputParser())
    .build();
```

较低的 `temperature(0.3f)` 让 Critic 的评审更稳定、更严格、不随机游走。

> 两个 Agent 也可以使用不同的模型：用性价比高的模型做 Proposer，用推理能力更强的模型做 Critic。

---

## 四、共识信号设计

Critic 的 System Prompt 要求它只输出两种格式之一：

```
[APPROVED] <认可理由>
[CRITIQUE] <问题列表>
```

criticNode lambda 用 `startsWith` 解析，写入结构化状态——**loop condition 不解析自然语言，只读一个布尔标志**：

```java
if (text.startsWith("[APPROVED]")) {
    ContextBus.get().putTransmit("consensus", "true");
} else {
    ContextBus.get().putTransmit("critique", text);
}
```

这一设计的好处：Critic 换成更啰嗦的模型也不影响循环逻辑，结构化前缀是唯一判断依据。

---

## 五、loop 驱动逻辑

```java
.loop(
    // condition：未达共识且未超轮次则继续
    i -> {
        boolean approved = "true".equals(ContextBus.get().getTransmit("consensus"));
        boolean cont = !approved && i < 5;
        if (i > 0) {
            System.out.printf("%n--- 第%d轮条件检查：approved=%b，继续=%b ---%n",
                i + 1, approved, cont);
        }
        return cont;
    },

    // 节点A ── Proposer：第 1 轮提初始方案，后续轮次根据批评修改
    (Object input) -> {
        String topic    = ContextBus.get().getTransmit("topic");
        String critique = ContextBus.get().getTransmit("critique");
        String proposal = ContextBus.get().getTransmit("proposal");

        String prompt = (critique == null)
            ? "你是一位资深架构师。请针对以下议题提出一个技术方案（300字以内）：\n" + topic
            : "你是一位资深架构师。你之前的方案：\n" + proposal +
              "\n\n评审意见：\n" + critique +
              "\n\n请根据评审意见修改并改进方案（300字以内）。只输出修改后的方案正文。";

        ChatGeneration result = chainActor.invoke(proposerFlow, Map.of("prompt", prompt));
        ContextBus.get().putTransmit("proposal", result.getText());
        return result;
    },

    // 节点B ── Critic：评审方案，输出 [APPROVED] 或 [CRITIQUE]
    (Object ignored) -> {
        String topic    = ContextBus.get().getTransmit("topic");
        String proposal = ContextBus.get().getTransmit("proposal");

        String prompt = "你是一位严格的技术评审专家。议题：" + topic +
                        "\n\n当前方案：\n" + proposal +
                        "\n\n- 若方案足够完善，回复以 [APPROVED] 开头，说明认可理由（100字以内）。" +
                        "\n- 若存在明显缺陷，回复以 [CRITIQUE] 开头，列出问题（100字以内），不给修改建议。";

        ChatGeneration result = chainActor.invoke(criticFlow, Map.of("prompt", prompt));
        String text = result.getText();
        if (text.startsWith("[APPROVED]")) {
            ContextBus.get().putTransmit("consensus", "true");
        } else {
            ContextBus.get().putTransmit("critique", text);
        }
        return result;
    }
)
```

---

## 六、执行流程示例

```
========== Proposer-Critic 多轮辩论 ==========
议题：设计一个支持高并发的分布式任务调度系统，要求支持百万级任务、秒级延迟、故障自动恢复

--- 轮次1：Proposer 提出初始方案 ---
[Proposer]
采用 Master-Worker 架构：Master 节点负责任务分发和状态管理，Worker 节点池化处理任务。
任务存入 Redis Sorted Set（按执行时间排序），Master 轮询拉取到期任务分发给空闲 Worker。
使用 ZooKeeper 做 Master 选主，主节点宕机时备节点 10 秒内接管。Worker 心跳 5 秒上报，
超时自动剔除，任务重新入队。水平扩展 Worker 数量应对高并发。

[Critic]
[CRITIQUE]
1. Redis 单点轮询存在性能瓶颈，百万级任务场景下 Master 将成为吞吐瓶颈。
2. 任务重新入队未说明去重机制，Worker 故障可能导致任务重复执行。
3. ZooKeeper 选主切换 10 秒对"秒级延迟"要求过长。

--- 第2轮条件检查：approved=false，继续=true ---

--- Proposer 修改方案 ---
[Proposer]
采用分片 Master + Worker 架构：按 taskId 哈希将任务分配到多个 Master 分片，消除单点瓶颈。
任务存入分片 Redis Cluster，每个 Master 分片独立轮询，峰值吞吐线性扩展。
引入幂等键（taskId + 执行版本号），Worker 执行前 CAS 抢占，防止重复执行。
Master 高可用改用 Raft 协议（etcd），选主 < 1 秒，满足秒级延迟要求。
Worker 心跳 3 秒，失联任务由其他 Worker 通过幂等 CAS 安全重试。

[Critic - 达成共识]
[APPROVED] 方案已充分解决三个核心问题：分片架构消除了 Master 瓶颈，幂等 CAS 机制保证任务精确一次执行，
etcd Raft 选主满足秒级切换要求。整体设计具备生产可行性。

--- 第3轮条件检查：approved=true，继续=false ---

========== 最终方案 ==========
状态：✅ 达成共识

采用分片 Master + Worker 架构：按 taskId 哈希将任务分配到多个 Master 分片...
================================
```

两轮完成：第 1 轮方案有三个缺陷，Proposer 在第 2 轮针对性修复，Critic 批准。如果第 2 轮仍有问题，loop 继续，最多 5 轮后强制结束。

---

## 七、与双 Agent 纠错的对比

| 维度 | 双 Agent 纠错（第 20 篇） | Proposer-Critic 辩论（本篇） |
|------|--------------------------|------------------------------|
| Agent 工具 | Write Agent 用 MCP filesystem，Test Agent 用 compile_and_run | 两个 Agent 均无工具 |
| 终止信号 | `test_result` startsWith("PASS") | `consensus` == "true" |
| 信号来源 | 工具函数直接写入 | Critic LLM 输出 [APPROVED] 前缀 |
| 适用场景 | 需要真实执行验证的任务 | 纯文本生成与评审任务 |
| 配置复杂度 | 较高（MCP + 自定义工具） | 最低（两个普通 LLM Flow） |

---

## 八、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `deepseek-v4-flash`
2. 无需 Node.js，无需 JDK 工具链，无需 MCP 配置

---

## 九、总结

本篇展示了 j-langchain 中最轻量的多 Agent 辩论模式：

- **无工具、纯 LLM**：两个 Agent 都只是 `PromptTemplate + ChatAliyun`，没有任何工具依赖
- **结构化共识信号**：Critic 用 `[APPROVED]`/`[CRITIQUE]` 前缀输出，loop condition 只读布尔标志，不解析自然语言
- **temperature 差异**：Proposer 用高 temperature 保持创造性，Critic 用低 temperature 保持严格性
- **loop() 协调**：两个节点的轮流执行、共享状态读写、终止条件均由 loop() 统一管理

这种模式可推广到任何"生成 → 评审 → 改进"场景：文案优化、方案评审、代码审查（不需要真实编译时）、需求澄清等。

---

> 📎 相关资源
> - 完整代码：[Article21ProposerCriticDebate.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article21ProposerCriticDebate.java)，方法 `proposerCriticDebate()`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`deepseek-v4-flash`）
