# j-langchain 接入国内主流大模型：11 家厂商，改一行代码切换

> **标签**：`Java` `LLM` `j-langchain` `通义` `DeepSeek` `混元` `千帆` `智谱` `豆包` `Kimi` `Chain`  
> **前置阅读**：[文章1：5分钟构建第一个AI应用](01-hello-ai.md)
> **适合人群**：需要接入国内大模型、希望统一链路代码的 Java 开发者

---

## 一、问题：每家厂商都要重新适配吗

用 j-langchain 构建好一条链路之后，如果想换一个大模型供应商，通常要担心两个问题：API 格式不一样怎么办？现有的 Prompt、解析逻辑需要跟着改吗？

答案是不用。j-langchain 对所有模型实现了统一的 `BaseChatModel` 接口，每家厂商对应一个 `Chat*` 类（`ChatAliyun`、`ChatDeepseek`、`ChatHunyuan`……），链路的其他部分——`PromptTemplate`、`StrOutputParser`、各种 `TranslateHandler`——完全不需要动。

切换供应商只需要改**一行代码**，剩下的交给框架。

---

## 二、统一的链路模板

所有厂商共用同一套链路结构：

```java
private void runSimpleDomesticChain(String banner, BaseChatModel llm) {

    // Prompt 模板：所有厂商通用，不需要为每家单独写
    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
        "请用一句话（不超过40字）中文回答：${topic}"
    );

    // 链路结构固定：PromptTemplate → LLM → StrOutputParser
    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)   // ← 这里换成不同厂商的 Chat* 实例，其余完全不变
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "简单自我介绍你的模型身份"));
    System.out.println("=== " + banner + " ===");
    System.out.println(result.getText());
}
```

这个方法只有一个变化点：传入的 `llm` 参数。下面所有厂商的示例都复用这个方法。

---

## 三、11 家厂商接入方式

### 阿里云通义千问

```java
@Test
public void chainAliyun() {
    runSimpleDomesticChain(
        "阿里云通义",
        ChatAliyun.builder().model("qwen-plus").build()
    );
}
```

环境变量：`ALIYUN_KEY`

---

### 豆包（火山方舟）

```java
@Test
public void chainDoubao() {
    runSimpleDomesticChain(
        "豆包 / 火山方舟",
        ChatDoubao.builder().model("doubao-1-5-lite-32k-250115").build()
    );
}
```

环境变量：`DOUBAO_KEY`

---

### Moonshot（Kimi）

```java
@Test
public void chainMoonshot() {
    runSimpleDomesticChain(
        "Moonshot Kimi",
        ChatMoonshot.builder().model("moonshot-v1-8k").build()
    );
}
```

环境变量：`MOONSHOT_KEY`

---

### 扣子（Coze）

扣子的接入方式稍有不同，需要额外提供 `botId`。可以在控制台创建 Bot 后，将 Bot ID 设置为环境变量 `COZE_BOT_ID`。未设置时示例使用占位 ID，调用会失败直至替换为真实值。

代码中使用了 JUnit 的 `Assume` 机制，如果 `COZE_KEY` 未配置，该测试会自动跳过，不影响其他用例运行：

```java
@Test
public void chainCoze() {
    // 未配置 COZE_KEY 时自动跳过，不报错
    Assume.assumeTrue("需要 COZE_KEY", StringUtils.isNotBlank(System.getenv("COZE_KEY")));
    String botId = StringUtils.defaultIfBlank(System.getenv("COZE_BOT_ID"), "751971414224112XXXX");
    runSimpleDomesticChain(
        "扣子 Coze",
        ChatCoze.builder().botId(botId).build()
    );
}
```

环境变量：`COZE_KEY` + `COZE_BOT_ID`

---

### DeepSeek

```java
@Test
public void chainDeepseek() {
    runSimpleDomesticChain(
        "DeepSeek",
        ChatDeepseek.builder().model("deepseek-chat").build()
    );
}
```

环境变量：`DEEPSEEK_KEY`

---

### 腾讯混元

```java
@Test
public void chainHunyuan() {
    runSimpleDomesticChain(
        "腾讯混元",
        ChatHunyuan.builder().model("hunyuan-turbo").build()
    );
}
```

环境变量：`HUNYUAN_KEY`

---

### 百度千帆（文心）

```java
@Test
public void chainQianfan() {
    runSimpleDomesticChain(
        "百度千帆文心",
        ChatQianfan.builder().model("ernie-4.5-8k").build()
    );
}
```

环境变量：`QIANFAN_KEY`

---

### 智谱 GLM

```java
@Test
public void chainZhipu() {
    runSimpleDomesticChain(
        "智谱 GLM",
        ChatZhipu.builder().model("glm-4-flash").build()
    );
}
```

环境变量：`ZHIPU_KEY`

---

### MiniMax

```java
@Test
public void chainMinimax() {
    runSimpleDomesticChain(
        "MiniMax",
        ChatMinimax.builder().model("MiniMax-Text-01").build()
    );
}
```

环境变量：`MINIMAX_KEY`

---

### 零一万物（Yi）

```java
@Test
public void chainLingyi() {
    runSimpleDomesticChain(
        "零一万物 Yi",
        ChatLingyi.builder().model("yi-lightning").build()
    );
}
```

环境变量：`LINGYI_KEY`

---

### 阶跃星辰（Step）

```java
@Test
public void chainStepfun() {
    runSimpleDomesticChain(
        "阶跃星辰 Step",
        ChatStepfun.builder().model("step-2-16k").build()
    );
}
```

环境变量：`STEPFUN_KEY`

---

## 四、厂商速查表

| 厂商 | Chat 类 | 环境变量 | 示例模型 |
|---|---|---|---|
| 阿里云通义 | `ChatAliyun` | `ALIYUN_KEY` | `qwen-plus` |
| 豆包（火山方舟） | `ChatDoubao` | `DOUBAO_KEY` | `doubao-1-5-lite-32k-250115` |
| Moonshot（Kimi） | `ChatMoonshot` | `MOONSHOT_KEY` | `moonshot-v1-8k` |
| 扣子 Coze | `ChatCoze` | `COZE_KEY` + `COZE_BOT_ID` | — |
| DeepSeek | `ChatDeepseek` | `DEEPSEEK_KEY` | `deepseek-chat` |
| 腾讯混元 | `ChatHunyuan` | `HUNYUAN_KEY` | `hunyuan-turbo` |
| 百度千帆 | `ChatQianfan` | `QIANFAN_KEY` | `ernie-4.5-8k` |
| 智谱 GLM | `ChatZhipu` | `ZHIPU_KEY` | `glm-4-flash` |
| MiniMax | `ChatMinimax` | `MINIMAX_KEY` | `MiniMax-Text-01` |
| 零一万物 Yi | `ChatLingyi` | `LINGYI_KEY` | `yi-lightning` |
| 阶跃星辰 Step | `ChatStepfun` | `STEPFUN_KEY` | `step-2-16k` |

模型名可以换成控制台中实际可用的版本，`Chat*` 类和环境变量不变。

---

## 五、运行方式

单独运行某一厂商的测试（需提前 export 对应的 API Key）：

```bash
# 只跑阿里云
mvn test -Dtest=Article17DomesticVendorsChain#chainAliyun

# 只跑 DeepSeek
mvn test -Dtest=Article17DomesticVendorsChain#chainDeepseek
```

运行全部：

```bash
mvn test -Dtest=Article17DomesticVendorsChain
```

未配置 Key 的厂商会抛出认证异常，不影响其他用例。扣子是唯一使用 `Assume` 主动跳过的特例，因为它额外依赖 `botId` 且无法用通用方式感知是否就绪。

---

## 六、在实际项目中如何管理多厂商 Key

测试环境用环境变量即可，生产环境建议统一配置在 `application.yml` 中，通过 Spring 的 `@Value` 或配置类注入，避免 Key 散落在各处：

```yaml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
    model: qwen-plus
  deepseek:
    chat-key: ${DEEPSEEK_KEY}
    model: deepseek-chat
  hunyuan:
    chat-key: ${HUNYUAN_KEY}
    model: hunyuan-turbo
```

这样做有两个额外好处：可以为不同环境（dev/staging/prod）配置不同的模型版本；需要切换主力模型时，只改配置文件，不改代码。

---

## 七、总结

j-langchain 对国内主流大模型的适配思路是**接口统一，实现各异**：所有 `Chat*` 类都实现 `BaseChatModel`，差异封装在类内部，对链路完全透明。

这意味着：一条写好的链路，无论是简单的顺序链、带工具的 ReAct Agent，还是多 Agent 流水线，换模型只需替换 `Chat*` 实例，其余代码完全不用动。在需要对比不同模型效果、或者根据成本/性能做动态切换的场景下，这个设计非常实用。

---

> 📎 相关资源
> - 完整示例：`Article17DomesticVendorsChain.java`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 各厂商 API Key 申请入口详见项目 README