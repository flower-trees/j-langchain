# MCP + Function Calling：让模型自主驱动工具链完成多步推理

> **标签**：`Java` `MCP` `Function Calling` `ReAct` `j-langchain` `ToolCall` `Agent`  
> **前置阅读**：[Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md) → [Java Agent 集成 MCP 工具协议：让 AI 真正驱动企业系统](08-mcp.md)  
> **适合人群**：已了解 MCP 基础用法，希望让模型原生驱动工具调用的 Java 开发者

---

## 一、前两篇做了什么，这篇做什么

**文章 08** 介绍了 MCP 的基础接入方式：`McpManager` 注册 HTTP 工具，手动调用 `run()` 执行，然后把结果拼进 Prompt。这个方式适合验证工具是否可用，但"调哪个工具、传什么参数"是开发者决定的，模型并不参与。

**文章 09/10** 介绍了 `AgentExecutor`，让模型通过 ReAct 格式的文本输出（`Action: xxx / Action Input: yyy`）来驱动工具调用，适用于任意模型，但本质上是在解析模型的文本内容来判断意图。

**本篇**做的是第三种方式：把 MCP 工具清单直接注册给支持 **Function Calling** 的模型，模型不再输出文本格式的 Action，而是输出结构化的 **ToolCall**（函数名 + JSON 参数）。我们只负责执行这个 ToolCall，把结果写回 Prompt，然后让模型继续推理。

三种方式的核心区别一句话概括：

| 方式 | 谁决定调哪个工具 | 工具参数格式 | 模型要求 |
|---|---|---|---|
| 手动调用 MCP | 开发者 | 任意 | 无 |
| ReAct 文本驱动 | 模型（文本输出） | 字符串 / JSON 文本 | 任意 |
| Function Calling | 模型（结构化输出） | 标准 JSON Schema | 需支持 Function Calling |

---

## 二、场景：三步连贯推理，全程无人工介入

本篇的场景是自动检测当前环境的公网 IP、定位城市、查询天气：

1. `get_export_ip`：获取本机公网出口 IP
2. `get_ip_location`：根据 IP 查询城市、经纬度、ISP 信息
3. `get_weather_open_meteo`：根据经纬度查询实时天气

三个工具需要**按顺序调用，且上一步的返回值是下一步的输入参数**。用户只需说一句话，Agent 自主完成全部推理和工具调用，最终给出"位置 + 天气"的总结。

三个工具均在 `mcp.config.json` 的 `default` 分组中声明，无需改动任何代码就能被框架加载。

---

## 三、核心代码逐步拆解

### 第一步：构建 Prompt 并注入工具清单

```java
// Prompt 模板：系统指令明确调用顺序和输出格式
BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
    List.of(
        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
            """
            你是一名能够调用 MCP HTTP 工具的智能体，需要按以下顺序完成任务：
            1) 调用 get_export_ip 获取公网 IP；
            2) 将该 IP 传给 get_ip_location，获取城市、经纬度以及网络信息；
            3) 使用经纬度调用 get_weather_open_meteo，并设置 current_weather=true；
            4) 总结位置与天气（只输出结论，不暴露工具名称）。
            工具只在必要时调用，每个工具最多执行一次。
            """),
        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "用户问题：${input}")
    )
);

// manifestForInput() 把 mcp.config.json 转成模型所需的 JSON Schema 格式
List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");

// 把工具清单注册给 LLM，模型推理时会自动决定何时调用哪个工具
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen3.6-plus")
    .temperature(0f)
    .tools(tools)
    .build();
```

System Prompt 明确了调用顺序，这样做有两个好处：减少模型漏掉关键步骤的概率，也能让调试时更容易判断是哪一步出了问题。

### 第二步：循环条件——有 ToolCall 就继续

```java
int maxIterations = 5;

Function<Integer, Boolean> shouldContinue = round -> {
    if (round >= maxIterations) {
        return false;  // 防止死循环
    }
    if (round == 0) {
        return true;   // 第一轮必须执行
    }
    // 检查上一轮 LLM 输出是否包含 ToolCall
    AIMessage lastAi = ContextBus.get().getResult(llm.getNodeId());
    return lastAi instanceof ToolMessage toolMessage
        && CollectionUtils.isNotEmpty(toolMessage.getToolCalls());
};
```

退出条件很清晰：模型不再输出 ToolCall，说明它已经拿到足够信息，准备给出最终回答了。

### 第三步：核心处理器——执行 ToolCall 并写回 Observation

这是整个链路中最重要的一段代码：

```java
TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
    ChatPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());

    // 不是 ToolCall，说明模型已在生成最终回答，直接透传
    if (!(msg instanceof ToolMessage toolMessage)) {
        return msg;
    }
    if (CollectionUtils.isEmpty(toolMessage.getToolCalls())) {
        return toolMessage;
    }

    // 1. 把模型的 ToolCall 请求记入对话历史（模型下一轮推理时需要看到这条记录）
    promptValue.getMessages().add(toolMessage);

    // 2. 解析 ToolCall：拿到工具名和参数
    AiChatOutput.ToolCall call = toolMessage.getToolCalls().get(0);
    Map<String, Object> args = parseArgs(call.getFunction().getArguments());
    String toolName = call.getFunction().getName();

    // 3. 用 McpManager 执行真实 HTTP 请求
    System.out.println("[ToolCall] " + toolName + " params -> " + JsonUtil.toJson(args));
    Object result = mcpManager.runForInput("default", toolName, args);
    String observation = result != null ? result.toString() : "工具无返回内容";
    System.out.println("[Observation] " + observation);

    // 4. 把执行结果以 ToolMessage 形式追加到对话历史，供模型下一轮参考
    appendToolMessage(prompt, call, observation);

    return ContextBus.get().getResult(prompt.getNodeId());
});
```

`appendToolMessage` 的作用是把 Observation 以标准的 `ToolMessage` 格式写回 Prompt，这样模型在下一轮推理时能看到完整的"我调了什么工具、得到了什么结果"的上下文。

### 第四步：组装完整链路

```java
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .loop(
        shouldContinue,    // 循环条件：有 ToolCall 就继续
        llm,               // 每轮调用 LLM
        chainActor.builder()
            .next(
                Info.c(needsToolExecution, executeMcpTool),            // 有 ToolCall → 执行工具
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId())) // 无 ToolCall → 直接透传
            )
            .build()
    )
    .next(new StrOutputParser())
    .build();

ChatGeneration finalAnswer = chainActor.invoke(chain, Map.of(
    "input", "不要询问额外信息，自动检测我的公网 IP，推断所在城市并告知当前天气后统一回复。"
));

System.out.println(finalAnswer.getText());
```

整个链路的结构非常清晰：

```
Prompt → loop( LLM → [有ToolCall? 执行MCP : 透传] ) → 输出最终回答
```

---

## 四、完整推理过程

运行后控制台会打印完整的推理轨迹：

```
> MCP Function-Calling ReAct 链开始执行...

[ToolCall] get_export_ip params -> {}
[Observation] 123.117.177.40

[ToolCall] get_ip_location params -> {"ip": "123.117.177.40"}
[Observation] {"country_name":"China","region_name":"Beijing Shi","city":"Dongcheng Qu",
               "latitude":39.9117,"longitude":116.4097,"org":"AS4134 Chinanet"}

[ToolCall] get_weather_open_meteo params -> {"latitude":39.9117,"longitude":116.4097,"current_weather":"true"}
[Observation] {"current_weather":{"temperature":18.3,"windspeed":6.1,"weathercode":1}}

> 链执行完成。

=== 最终回答 ===
检测到你的公网 IP 位于中国北京市东城区，当前温度约 18°C，天气晴朗，风速 6.1 km/h。
```

模型精确执行了三次 ToolCall，没有遗漏任何一步，也没有多余的调用。每次 Observation 被写回 Prompt 后，模型在下一轮推理中就能自动提取所需字段（如 IP → 经纬度 → 天气），不需要开发者做任何字段映射。

---

## 五、和 ReAct 文本驱动的本质区别

很多开发者会疑问：`AgentExecutor` 也能完成多步工具调用，这两种方式有什么实质区别？

**ReAct 文本驱动**（AgentExecutor）：模型输出纯文本，例如：
```
Action: get_ip_location
Action Input: {"ip": "123.117.177.40"}
```
框架通过字符串解析提取工具名和参数，本质是在"读模型写的文章"。

**Function Calling**（本篇）：模型输出结构化 JSON，例如：
```json
{"toolCalls": [{"function": {"name": "get_ip_location", "arguments": "{\"ip\":\"123.117.177.40\"}"}}]}
```
框架直接解析 JSON，工具名和参数都是强类型字段，不存在格式歧义。

两种方式的适用场景：

| 场景 | 推荐方式 | 原因 |
|---|---|---|
| 模型不支持 Function Calling | ReAct 文本驱动 | 唯一可用选项 |
| 参数复杂（嵌套 JSON、数组） | Function Calling | 结构化输出更可靠 |
| 需要精确控制推理文本 | ReAct 文本驱动 | Thought 内容完全可读 |
| 对接标准 MCP 工具生态 | Function Calling | 工具 Schema 天然匹配 |
| 模型稳定性要求高 | Function Calling | 减少格式解析失败 |

---

## 六、工具调用失败怎么处理

生产环境中，HTTP 工具调用可能因网络超时、参数错误等原因失败。框架的处理方式是：将错误信息同样以 ToolMessage 形式写回 Prompt，让模型感知到"这次工具调用失败了"，然后由模型决定是重试、跳过还是向用户说明原因：

```java
try {
    Object result = mcpManager.runForInput("default", toolName, args);
    String observation = result != null ? result.toString() : "工具无返回内容";
    appendToolMessage(prompt, call, observation);
} catch (Exception e) {
    log.error("调用 MCP 工具 {} 失败: {}", toolName, e.getMessage(), e);
    // 把错误信息写回 Prompt，模型会在下一轮 Thought 中处理
    appendToolMessage(prompt, call, "调用失败：" + e.getMessage());
}
```

这比直接抛出异常要友好得多——模型可以在最终回答中说明"天气数据获取失败，已为您提供位置信息"，而不是让整个链路崩溃。

---

## 七、总结

本篇展示了一个完整的"MCP + Function Calling"多步推理链路。与文章 08 的手动调用相比，这里的工具执行顺序和参数完全由模型决定；与文章 09/10 的 ReAct 文本驱动相比，这里用的是模型原生的 ToolCall 输出，参数解析更可靠。

核心思路只有一句话：**把 MCP 工具清单交给模型，让模型决定调什么，开发者只负责执行和回写结果**。

如果你觉得这段循环代码仍然繁琐，下一篇文章会介绍 `McpAgentExecutor`，用一行代码封装整个流程。

---

> 📎 相关资源
> - 完整示例：[Article11McpReactAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article11McpReactAgent.java)，对应方法 `mcpFunctionCallingLoop()`
> - MCP 工具配置：`src/test/resources/mcp.config.json`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key，示例模型 `qwen3.6-plus`