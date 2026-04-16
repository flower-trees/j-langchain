# 两行注解把企业 RPC 接口变成 AI 工具：Dubbo 与 Feign 实战接入

> **标签**：`Java` `Agent` `ReAct` `j-langchain` `@AgentTool` `@Param` `@ParamDesc` `VO` `Dubbo` `Feign` `RPC` `工具注册`  
> **前置阅读**：[AgentExecutor：用一行代码启动 ReAct Agent](09-agent-executor.md)  
> **适合人群**：希望把已有 Dubbo / Feign / gRPC 等 RPC 服务快速接入 AI Agent 的 Java 开发者

---

## 一、背景

企业里有大量存量 RPC 服务，想让 AI Agent 调用这些服务，最自然的期望是：

- **不改动**已有 RPC 接口定义
- 只做最少的"配置"，而不是"重写一套工具层"
- 不管底层是 Dubbo 还是 Feign，接入方式应该一致

j-langchain 的 `@AgentTool` + `@Param` / `@ParamDesc` 体系正好满足这个需求。

---

## 二、参数描述的三种方式

工具的参数 Schema 是给 LLM 看的，框架按以下优先级生成：

| 优先级 | 方式 | 适用场景 |
|---|---|---|
| 1 | `@AgentTool.params` 内联 `@ParamDesc` | VO 来自第三方包，无法修改字段 |
| 2 | VO 字段上的 `@Param` | 自己定义的 VO |
| 3 | 方法参数上的 `@Param` | 简单基础类型参数 |

三种方式互为补充，向下兼容，按实际情况选择即可。

---

## 三、通用接入步骤

**Step 1：创建工具包装类，加 `@Component`**

工具包装类必须是 Spring Bean，`@DubboReference` / `@Autowired` 字段才能被容器注入。

```java
@Component
public class EcommerceDubboTools {

    @DubboReference
    private OrderFacade orderFacade;

    @AgentTool("查询用户订单信息")
    public String queryOrder(OrderQueryRequest request) {
        return orderFacade.queryOrder(request).toString();
    }
}
```

**Step 2：在调用方注入工具 Bean，传给 AgentExecutor**

```java
@Service
public class CustomerServiceAgent {

    @Autowired
    private ChainActor chainActor;

    @Autowired
    private EcommerceDubboTools ecommerceDubboTools;  // Spring 管理，@DubboReference 已注入

    public String handle(String userQuestion) {
        AgentExecutor agent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(ecommerceDubboTools)   // 传 bean 引用，不是 new
            .build();
        return agent.invoke(userQuestion).getText();
    }
}
```

---

## 四、场景一：Dubbo — 第三方 VO + @AgentTool.params 内联描述

VO 来自合作方三方包，字段上无法加 `@Param`。改用 `@AgentTool.params` 内联描述，效果完全相同。

### 第三方 VO（来自三方包，不可修改）

```java
// 来自二方包，无法修改
public class OrderQueryRequest {
    private String orderId;
    private String userId;
    private String queryType;
}

public class RefundRequest {
    private String orderId;
    private String reason;
    private String amount;
}

public class LogisticsQueryRequest {
    private String orderId;
    private String trackingNo;
}
```

### 工具包装类

```java
@Component
public class EcommerceDubboTools {

    @DubboReference
    private OrderFacade orderFacade;

    @DubboReference
    private RefundFacade refundFacade;

    @DubboReference
    private LogisticsFacade logisticsFacade;

    @AgentTool(
        value = "查询用户订单信息",
        params = {
            @ParamDesc(name = "orderId",   desc = "订单ID，格式：ORD-2024-XXXXXX，如不知道可留空"),
            @ParamDesc(name = "userId",    desc = "用户ID，格式：USR-XXXXXX，如不知道可留空"),
            @ParamDesc(name = "queryType", desc = "查询类型：LATEST（最近一笔）/ ALL（全部订单），默认 LATEST")
        }
    )
    public String queryOrder(OrderQueryRequest request) {
        return orderFacade.queryOrder(request).toString();
    }

    @AgentTool(
        value = "提交退款申请",
        params = {
            @ParamDesc(name = "orderId", desc = "订单ID，格式：ORD-2024-XXXXXX"),
            @ParamDesc(name = "reason",  desc = "退款原因，如：质量问题 / 物流超时 / 不想要了 / 收到错误商品"),
            @ParamDesc(name = "amount",  desc = "退款金额（元），不填则申请全额退款")
        }
    )
    public String applyRefund(RefundRequest request) {
        return refundFacade.applyRefund(request).toString();
    }

    @AgentTool(
        value = "查询物流配送状态",
        params = {
            @ParamDesc(name = "orderId",    desc = "订单ID，格式：ORD-2024-XXXXXX"),
            @ParamDesc(name = "trackingNo", desc = "快递单号（可选，不填则通过订单ID自动关联查询）")
        }
    )
    public String trackLogistics(LogisticsQueryRequest request) {
        return logisticsFacade.track(request).toString();
    }
}
```

框架读取 `@AgentTool.params`，自动生成如下 Schema：

```
查询用户订单信息
  Input JSON keys (OrderQueryRequest):
    - orderId (String): 订单ID，格式：ORD-2024-XXXXXX，如不知道可留空
    - userId (String): 用户ID，格式：USR-XXXXXX，如不知道可留空
    - queryType (String): 查询类型：LATEST（最近一笔）/ ALL（全部订单），默认 LATEST
  Action Input format: JSON, e.g. {"orderId": ..., "userId": ..., "queryType": ...}
```

### 执行过程

```
[思考] 先查询订单基本信息。
Action: query_order  {"orderId": "ORD-2024-001"}
[服务返回] 订单 ORD-2024-001：「索尼 WH-1000XM5 耳机」¥2199，运输中（已超时2天）

[思考] 订单确实超时，查询物流详情。
Action: track_logistics  {"orderId": "ORD-2024-001"}
[服务返回] SF1234567890，2024-03-12 09:00 在成都青羊区网点，此后无更新

[思考] 物流异常，提交退款。
Action: apply_refund  {"orderId": "ORD-2024-001", "reason": "物流超时"}
[服务返回] 退款申请已提交，¥2199（全额），工单号 TKT-20240316-8821

Final Answer: 已为您处理完毕...
```

---

## 五、场景二：Feign — 自有 VO + @Param 标注字段

VO 是自己团队定义的，可以直接在字段上加 `@Param`。

### VO 定义

```java
@Data
public class ProductDetailRequest {
    @Param("商品ID，格式：PROD-XXXXXX")
    private String productId;

    @Param("商品类目，如：手机/笔记本/耳机，可留空")
    private String category;
}

@Data
public class InventoryQueryRequest {
    @Param("商品SKU，格式：SKU-XXXXXX")
    private String sku;

    @Param("仓库区域：EAST/WEST/SOUTH/NORTH，可留空（查全部仓库）")
    private String region;
}

@Data
public class PriceQueryRequest {
    @Param("商品SKU，格式：SKU-XXXXXX")
    private String sku;

    @Param("用户等级：VIP/NORMAL，影响折扣，默认 NORMAL")
    private String userLevel;
}
```

### 工具包装类

```java
@Component
public class RetailFeignTools {

    @Autowired
    private ProductFeignClient productFeignClient;

    @Autowired
    private InventoryFeignClient inventoryFeignClient;

    @Autowired
    private PricingFeignClient pricingFeignClient;

    @AgentTool("查询商品详情")
    public String getProductDetail(ProductDetailRequest request) {
        return productFeignClient.getProductDetail(request).toString();
    }

    @AgentTool("查询商品库存")
    public String queryInventory(InventoryQueryRequest request) {
        return inventoryFeignClient.queryInventory(request).toString();
    }

    @AgentTool("查询商品价格")
    public String queryPrice(PriceQueryRequest request) {
        return pricingFeignClient.queryPrice(request).toString();
    }
}
```

框架扫描 VO 字段上的 `@Param`，自动生成如下 Schema：

```
查询商品库存
  Input JSON keys (InventoryQueryRequest):
    - sku (String): 商品SKU，格式：SKU-XXXXXX
    - region (String): 仓库区域：EAST/WEST/SOUTH/NORTH，可留空（查全部仓库）
  Action Input format: JSON, e.g. {"sku": ..., "region": ...}
```

### 执行过程

```
[思考] 先查询商品详情。
Action: get_product_detail  {"productId": "PROD-SONY-001"}
[服务返回] 索尼 WH-1000XM5，主动降噪，30h 续航，黑色/银色

[思考] 查华东仓库库存。
Action: query_inventory  {"sku": "SKU-SONY-WH1000XM5", "region": "EAST"}
[服务返回] EAST 仓库：黑色 47 件，银色 12 件，均可当日发货

[思考] 查 VIP 价格。
Action: query_price  {"sku": "SKU-SONY-WH1000XM5", "userLevel": "VIP"}
[服务返回] ¥1979（9折 VIP 优惠，立省 ¥220）

Final Answer: 索尼 WH-1000XM5 详情、库存、VIP 价格已查完...
```

---

## 六、两种场景对比

| 维度 | Dubbo（@AgentTool.params） | Feign（@Param on field） |
|---|---|---|
| VO 是否可修改 | 否（二方包提供） | 是（自有定义） |
| 参数描述位置 | 工具方法注解 | VO 字段注解 |
| LLM 看到的 Schema | 完全相同 | 完全相同 |
| 工具包装类 | `@Component` + `@AgentTool` | `@Component` + `@AgentTool` |

两种方式生成的 Schema 对 LLM 完全透明，差别仅在于描述写在哪里。

---

## 七、适用范围

任何 `Response method(Request req)` 形态的服务都适用：

| 框架 | 注入方式 |
|---|---|
| Apache Dubbo | `@DubboReference` |
| Spring Cloud Feign | `@Autowired` FeignClient |
| gRPC | `@Autowired` Stub |
| Spring MVC 内部 HTTP | `@Autowired` RestTemplate |
| MyBatis Mapper | `@Autowired` Mapper |

---

## 八、总结

- **工具包装类加 `@Component`**：Spring 管理，`@DubboReference` / `@Autowired` 正常注入；调用方 `@Autowired` 注入后传给 `tools()`，不要 `new`
- **自有 VO**：`@Param` 标注在字段上，描述集中在数据模型层
- **第三方 VO**：`@AgentTool.params` 内联 `@ParamDesc`，描述集中在工具方法上
- **框架透明**：Schema 生成、JSON 反序列化、方法调用全部自动处理；Spring Bean 代理自动穿透

---

> 📎 相关资源
> - 完整示例：[Article19DubboMcpTools.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article19DubboMcpTools.java)
>   - `dubboAgentDemo()` — Dubbo 场景：第三方 VO + `@AgentTool.params` 内联描述
>   - `feignAgentDemo()` — Feign 场景：自有 VO + `@Param` 标注字段
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key（`ALIYUN_KEY`），示例模型 `qwen-plus`
