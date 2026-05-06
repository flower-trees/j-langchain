# History 与 Agent Memory 设计

## 1. 设计背景与目标

LLM 应用面临两个本质性的记忆挑战：

**对话记忆（Conversation Memory）**：多轮对话中，如何让模型记住过去的交互？原始方式是把所有历史消息拼入每次请求，但随着对话轮数增加，上下文窗口很快耗尽，成本也急剧攀升。

**Agent 执行记忆（Agent Task Memory）**：Agent 在单次任务中会多次调用工具，每次调用都需要把之前的思考链（thought/action/observation）带入下一轮。任务越复杂、工具调用越多，积累的中间状态就越大，同样面临窗口溢出问题。

j-langchain 的设计目标是：**将存储后端与记忆策略彻底解耦，并为两类记忆场景分别提供可插拔的策略体系**，使开发者可以在不改动业务逻辑的情况下自由组合存储和压缩策略。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                   j-langchain Memory 体系                    │
│                                                             │
│  ┌─────────────────────────┐  ┌──────────────────────────┐  │
│  │   Conversation Memory   │  │    Agent Task Memory     │  │
│  │   （多轮对话记忆）        │  │    （单次任务执行记忆）    │  │
│  │                         │  │                          │  │
│  │  MemoryStrategy         │  │  AgentContext (factory)  │  │
│  │  ├─ Buffer              │  │  ├─ FullContext           │  │
│  │  ├─ BufferWindow        │  │  └─ SlidingWindowContext  │  │
│  │  ├─ Summary             │  │                          │  │
│  │  └─ SummaryBuffer       │  │  AgentTaskContext (session│  │
│  │                         │  │  per invocation)         │  │
│  │  ConversationStorage    │  │  AgentTaskStorage        │  │
│  │  ├─ InMemory            │  │  ├─ InMemory             │  │
│  │  └─ (MySQL/Redis/...)   │  │  └─ (MySQL/Redis/...)    │  │
│  └─────────────────────────┘  └──────────────────────────┘  │
│                                                             │
│                  HistoryInfos (统一数据模型)                  │
└─────────────────────────────────────────────────────────────┘
```

两个子系统共享同一个数据模型 `HistoryInfos`，并通过 `parentId` 构成逻辑上的父子关系：ConversationStorage 中的一条对话记录可以作为 AgentTaskStorage 中多个步骤的父节点。

---

## 3. 统一数据模型：HistoryInfos

`HistoryInfos` 是贯穿整个记忆体系的核心数据结构：

```java
public class HistoryInfos {

    public enum Type {
        NORMAL,        // 普通 Human+AI 对话轮次
        SUMMARY,       // 对话摘要（替代早期轮次）
        AGENT_STEP,    // Agent 工具调用步骤
        TASK_SUMMARY   // Agent 步骤的压缩摘要
    }

    private String id;          // UUID，自动生成
    private String parentId;    // 父记录 id（null 表示顶层）
    private long   createdAt;   // 创建时间戳（epoch ms）
    private Type   type;
    private List<BaseMessage> messages;
}
```

**设计要点：**

- `id` + `parentId` 构成树形结构：ConversationStorage 记录（根节点）→ AgentTaskStorage 步骤（子节点）→ 嵌套 Agent 步骤（孙节点）。
- `type` 枚举区分四种记录语义，让存储层和读取层都能根据类型做不同处理，而无需额外的表或集合。
- `messages` 统一存储 `BaseMessage` 列表。`BaseMessage` 通过 Jackson `@JsonTypeInfo(EXISTING_PROPERTY)` 利用已有的 `role` 字段实现多态反序列化，支持持久化到 MySQL/Redis 等后端。

**树形存储示意：**

```
ConversationStorage
  └─ HistoryInfos { id="task-001", parentId=null, type=NORMAL }   ← 最终对话轮次

AgentTaskStorage
  └─ HistoryInfos { id="step-1", parentId="task-001", type=AGENT_STEP }   ← 工具调用步骤 1
  └─ HistoryInfos { id="step-2", parentId="task-001", type=AGENT_STEP }   ← 工具调用步骤 2
  └─ HistoryInfos { id="summary-1", parentId="task-001", type=TASK_SUMMARY } ← 压缩后替换旧步骤
```

---

## 4. Conversation Memory（对话记忆）

### 4.1 分层设计

对话记忆采用**两层分离**的设计：

```
存储层（Storage）          记忆策略层（Memory Strategy）
──────────────────         ──────────────────────────────
ConversationStorage   ←→   ConversationMemoryReaderBase
  ├─ loadAll()             ConversationMemoryStorerBase
  ├─ append()                ├─ Buffer
  ├─ replace()               ├─ BufferWindow
  └─ clear()                 ├─ Summary
                             └─ SummaryBuffer
```

- **存储层**只负责原始 CRUD，对记忆策略完全无知。
- **记忆策略层**只负责压缩/截断逻辑，通过存储接口读写数据，不关心底层是内存还是数据库。

这样的好处是：替换存储后端（内存→MySQL→Redis）不影响任何策略代码；增加新的记忆策略不影响任何存储代码。

### 4.2 存储接口

```java
public interface ConversationStorage {
    List<HistoryInfos> loadAll(Long appId, Long userId, Long sessionId);
    void append(Long appId, Long userId, Long sessionId, HistoryInfos turn);
    void replace(Long appId, Long userId, Long sessionId, List<HistoryInfos> compacted);
    void clear(Long appId, Long userId, Long sessionId);
}
```

`replace()` 是关键接口：Summary 类策略在压缩后用 `replace()` 原子性地用压缩结果替换旧数据，避免并发读写不一致。

### 4.3 四种记忆策略

| 策略 | 存储内容 | 适用场景 |
|------|---------|---------|
| **Buffer** | 保留所有轮次 | 短对话，上下文可控 |
| **BufferWindow** | 只保留最近 N 轮 | 长对话，关注近期上下文 |
| **Summary** | 每轮后生成摘要替换全部历史 | 极长对话，重要信息提炼 |
| **SummaryBuffer** | 保留最近 N 轮原文 + 早期摘要 | 平衡精度与窗口大小 |

#### Buffer（全量缓冲）

最简单的策略，每轮追加，从不压缩。适用于对话轮数可控的场景。

```
[NORMAL: H1+A1] → [NORMAL: H1+A1] → [NORMAL: H2+A2]
                                     每轮 append，全量返回
```

#### BufferWindow（滑动窗口）

超出 `maxSize` 时，移除最旧的轮次。简单高效，但丢失的早期信息无法恢复。

```
窗口=2:
  [H1+A1, H2+A2] → 新轮 → [H2+A2, H3+A3]  （H1+A1 被丢弃）
```

#### Summary（全量摘要）

每次存储后，调用 LLM 把所有历史压缩成一条 `SUMMARY` 记录。下次读取时将摘要注入 system prompt。信息损失可控，但每轮都有 LLM 摘要开销。

```
存储后: [SUMMARY: "用户问了天气和时间..."] （原始消息被替换）
读取时: SystemMessage("Here is the summary: ...")
```

#### SummaryBuffer（摘要+缓冲混合）

保留最近 N 轮的原始消息，同时保留一条早期摘要。兼顾近期精度和远期记忆。

```
窗口=2, 历史=[S, H1+A1, H2+A2]:
  新轮 H3+A3 到来 →
  H1+A1 被压入摘要 S' →
  存储: [S', H2+A2, H3+A3]
```

### 4.4 Reader/Storer 分离

每种策略都拆分为独立的 `Reader` 和 `Storer`：

- **Reader** 作为链式节点在 LLM 调用前执行，从存储中加载历史并注入当前消息列表。
- **Storer** 作为链式节点在 LLM 响应后执行，将本轮 Human+AI 消息写入存储（并按策略压缩）。

两者独立配置，允许"只读不写"（只读历史）或"只写不读"（记录但不回放）的场景。

---

## 5. Agent Task Memory（Agent 执行记忆）

### 5.1 核心挑战

Agent 的工具调用循环每轮都会产生新的 AI 消息和工具结果消息。这些消息必须完整传递给下一轮 LLM 调用，否则模型失去上下文。当工具调用轮次较多时，积累的消息量会超出上下文窗口。

### 5.2 设计理念：Strategy + ContextBus

Agent 执行记忆的设计围绕两个核心理念：

**策略模式（Strategy Pattern）**：通过 `AgentContext` 工厂接口，将"使用什么压缩策略"从 Agent 执行器中抽离出来。AgentExecutor 和 McpAgentExecutor 只依赖接口，不感知具体策略。

**ContextBus 传递**：每次 `invoke()` 调用创建的 `AgentTaskContext` 存入 ContextBus，链式节点通过 ContextBus 读取，而不是通过构造参数或 ThreadLocal 传递。这让链节点保持无状态，天然线程安全。

```
invoke() 开始
   │
   ▼
initContext（链节点）
   │  AgentContext.create(question, systemPrompt)
   │  → AgentTaskContext ctx
   │  → ContextBus.putTransmit(AGENT_TASK_CTX, ctx)
   │  → ContextBus.putTransmit(QUESTION, question)
   │
   ▼
LLM 调用（第一轮）
   │
   ▼
executeTool（链节点）
   │  ctx = ContextBus.getTransmit(AGENT_TASK_CTX)
   │  执行工具 → toolResults
   │  ctx.addStep(AgentStep.ofFunctionCall(aiMsg, toolResults))
   │  return ctx.buildChatPromptValue()   ← 构建下一轮消息
   │
   ▼
LLM 调用（第二轮）... （循环直到 Final Answer）
   │
   ▼
conversationStorer（可选，链节点）
   │  从 ContextBus 读取 QUESTION
   │  写入 ConversationStorage
```

### 5.3 核心接口

#### AgentContext（策略工厂）

```java
public interface AgentContext {
    AgentTaskContext create(String question, String systemPrompt);
}
```

每次 `invoke()` 调用一次 `create()`，返回一个隔离的 `AgentTaskContext` 实例。多并发请求之间完全隔离。

#### AgentTaskContext（单次任务的工作记忆）

```java
public interface AgentTaskContext {
    void addStep(AgentStep step);
    List<BaseMessage> buildMessages();                   // function-calling 模式
    default ChatPromptValue buildChatPromptValue() { ... }
    void initReactBasePromptText(String text);           // ReAct 文本模式
    String buildReactPromptText();
    String getTaskId();
}
```

两种 Agent 模式（function-calling 和 ReAct 文本）共用同一接口，由实现类分别处理：
- function-calling：`addStep` 收集 `AIMessage + ToolMessages`，`buildMessages()` 重建完整消息列表。
- ReAct 文本：`addStep` 收集 scratchpad 文本块，`buildReactPromptText()` 拼接完整 prompt 文本。

#### AgentStep（单个执行步骤）

```java
public class AgentStep {
    private AIMessage aiMessage;          // function-calling 模式
    private List<BaseMessage> toolResults;
    private String scratchpadText;        // ReAct 文本模式

    public static AgentStep ofFunctionCall(AIMessage aiMsg, List<BaseMessage> results) { ... }
    public static AgentStep ofReAct(String scratchpadText) { ... }
    public List<BaseMessage> toMessages() { ... }
}
```

工厂方法明确区分两种模式，避免混用。

### 5.4 两种 AgentContext 实现

#### FullContext（全量，默认策略）

不做任何压缩，所有步骤原样保留。当不调用 `.context()` 配置时，这是默认行为。

```java
public class FullContext implements AgentContext {
    public static FullContext build() { return new FullContext(); }
}
```

```
步骤 1 → [step1]
步骤 2 → [step1, step2]
步骤 3 → [step1, step2, step3]  （无界增长）
```

#### SlidingWindowContext（滑动窗口 + 可选摘要）

维护一个固定大小的步骤窗口。当步骤数超过 `windowSize` 时，将最旧的步骤从窗口中移除，并：
- 若配置了 `summarizer` LLM：调用 LLM 生成/更新 `earlyStepsSummary`。
- 若未配置 `summarizer`：简单文本拼接到 `earlyStepsSummary`。

`earlyStepsSummary` 在 function-calling 模式中注入 system prompt，在 ReAct 模式中作为 scratchpad 前缀。

```
windowSize=2:
步骤 1 → recentSteps=[step1]
步骤 2 → recentSteps=[step1, step2]
步骤 3 → 触发压缩:
         earlyStepsSummary = summarize(step1)
         recentSteps=[step2, step3]
步骤 4 → 触发压缩:
         earlyStepsSummary = summarize(earlyStepsSummary + step2)
         recentSteps=[step3, step4]
```

构建消息时的效果：

```
[SystemMessage: originalSystemPrompt + "\n\n" + earlyStepsSummary]
[HumanMessage: originalTask]
[步骤 N-1 的消息...]
[步骤 N 的消息...]
```

**builder 配置：**

```java
SlidingWindowContext.builder()
    .windowSize(5)                  // 保留最近 5 个步骤
    .summarizer(summarizerLlm)      // 可选 LLM 摘要器
    .taskStorage(inMemoryStorage)   // 可选持久化后端
    .build()
```

### 5.5 AgentTaskStorage（步骤持久化）

`AgentTaskStorage` 是可选的步骤持久化后端，与 `ConversationStorage` 对称设计：

```java
public interface AgentTaskStorage {
    void append(String taskId, HistoryInfos step);
    List<HistoryInfos> loadByTaskId(String taskId);
    void replace(String taskId, List<HistoryInfos> compacted);
    void clear(String taskId);
}
```

`SlidingWindowContext` 在每次 `addStep()` 时调用 `append()`，在压缩时调用 `replace()`（用 `TASK_SUMMARY` 记录替换旧的 `AGENT_STEP` 记录）。这为调试、审计和离线分析提供了完整的执行轨迹。

---

## 6. ConversationStorer 与 Agent 的集成

Agent 的最终 Human+AI 对话轮次通过 `conversationStorer` 写入 `ConversationStorage`，实现"任务执行内部记忆"与"跨会话对话记忆"的衔接。

```java
McpAgentExecutor.builder(chainActor)
    .llm(llm)
    .tools(tools)
    .context(SlidingWindowContext.builder().windowSize(3).taskStorage(taskStorage).build())
    .conversationStorer(ConversationBufferMemoryStorer.builder()
        .storage(conversationStorage)
        .appId(appId).userId(userId).sessionId(sessionId)
        .build())
    .build();
```

`conversationStorer` 以链节点的形式挂在执行链尾部，复用已有的 `ConversationMemoryStorerBase` 基础设施，无需 Agent 层感知任何存储细节。`QUESTION` 由 `initContext` 节点提前写入 ContextBus，storer 直接读取即可。

---

## 7. 包结构

```
core/
  history/
    HistoryInfos.java                    # 统一数据模型
    HistoryBase.java                     # appId/userId/sessionId 基类
    HistoryReaderBase.java               # Reader 链节点基类
    HistoryStorerBase.java               # Storer 链节点基类
    storage/
      ConversationStorage.java           # 存储接口
      InMemoryConversationStorage.java   # 内存实现
    memory/
      ConversationMemoryReaderBase.java  # 记忆 Reader 基类
      ConversationMemoryStorerBase.java  # 记忆 Storer 基类
      buffer/                            # Buffer 策略
      bufferwindow/                      # BufferWindow 策略
      summary/                           # Summary 策略
      summarybuffer/                     # SummaryBuffer 策略

  agent/
    storage/
      AgentTaskStorage.java              # Agent 步骤存储接口
      InMemoryAgentTaskStorage.java      # 内存实现
    memory/
      AgentContext.java                  # 策略工厂接口
      AgentTaskContext.java              # 单次任务工作记忆接口
      AgentStep.java                     # 单个执行步骤
      FullContext.java                   # 全量策略实现
      SlidingWindowContext.java          # 滑动窗口策略实现
    AgentExecutor.java                   # ReAct 文本 Agent
    McpAgentExecutor.java                # Function-calling Agent
```

---

## 8. 扩展指引

### 8.1 添加新的存储后端

实现 `ConversationStorage` 或 `AgentTaskStorage` 接口，注入到策略类即可，无需修改任何现有代码：

```java
@Component
public class RedisConversationStorage implements ConversationStorage {
    // 实现 loadAll / append / replace / clear
}
```

`BaseMessage` 已配置 Jackson 多态注解，直接支持 JSON 序列化/反序列化，可安全存入 Redis 或 MySQL。

### 8.2 添加新的对话记忆策略

继承 `ConversationMemoryReaderBase` 和 `ConversationMemoryStorerBase`，实现各自的抽象方法：

```java
public class MyCustomMemoryStorer extends ConversationMemoryStorerBase {
    @Override
    protected void storeHistory(HistoryInfos turn) {
        // 自定义压缩/存储逻辑
    }
}
```

### 8.3 添加新的 Agent 执行记忆策略

实现 `AgentContext` 接口，内部 `Session` 实现 `AgentTaskContext`：

```java
public class TokenBudgetContext implements AgentContext {
    @Override
    public AgentTaskContext create(String question, String systemPrompt) {
        return new Session(question, systemPrompt, tokenBudget);
    }
    // 按 token 数量而非步骤数量压缩的 Session 实现
}
```

---

## 9. 设计原则总结

| 原则 | 体现 |
|------|-----|
| **关注点分离** | 存储层与策略层完全独立，各司其职 |
| **接口驱动** | ConversationStorage / AgentContext / AgentTaskContext 均为接口，易于替换和测试 |
| **策略模式** | 四种对话记忆策略、两种 Agent 记忆策略，通过接口统一，运行时注入 |
| **无状态节点** | 链节点通过 ContextBus 传递状态，不持有实例级状态，天然线程安全 |
| **统一数据模型** | HistoryInfos 贯穿两个子系统，parentId 构建跨系统树形关系 |
| **渐进式配置** | 所有高级特性（窗口、摘要、持久化）均为可选，默认行为开箱即用 |
