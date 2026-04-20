# Two Annotations to Turn Enterprise RPC into AI Tools

> **Tags**: `Java` `Agent` `j-langchain` `RPC` `Dubbo` `Feign` `@AgentTool` `@Param` `@ParamDesc`  
> **Prerequisite**: [AgentExecutor: Start a ReAct Agent with One Line](09-agent-executor.md)  
> **Audience**: Java developers who want to connect existing Dubbo / Feign / gRPC services to an AI Agent with minimal effort

---

## 1. Background

Enterprise systems carry large numbers of existing RPC services. The natural expectation when wiring them into an AI Agent is:

- **No changes** to existing RPC interface definitions
- Minimal "configuration", not a rewritten tool layer
- A consistent approach regardless of whether the underlying framework is Dubbo or Feign

j-langchain's `@AgentTool` + `@Param` / `@ParamDesc` system is designed for exactly this. The same tool wrapper works with both `AgentExecutor` (ReAct) and `McpAgentExecutor` (Function Calling) without any modification.

---

## 2. Three Ways to Describe Parameters

The tool parameter schema is what the LLM reads. The framework resolves it using the following priority:

| Priority | Mechanism | When to use |
|---|---|---|
| 1 | `@AgentTool.params` with inline `@ParamDesc` | VO is from a third-party package and cannot be modified |
| 2 | `@Param` on VO fields | You own the VO class |
| 3 | `@Param` on method parameters | Simple primitive / String parameters |

All three are complementary and backward-compatible — choose based on your situation.

---

## 3. Scenario A: Dubbo — Third-Party VO + Inline `@AgentTool.params`

When the VO is from a partner's SDK and you cannot modify its fields, use `@AgentTool.params` with `@ParamDesc` instead. The generated schema is identical to field-level `@Param`.

### Tool wrapper class

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
        value = "Query user order information",
        params = {
            @ParamDesc(name = "orderId",   desc = "Order ID, e.g. ORD-2024-XXXXXX; leave blank if unknown"),
            @ParamDesc(name = "userId",    desc = "User ID, e.g. USR-XXXXXX; leave blank if unknown"),
            @ParamDesc(name = "queryType", desc = "Query type: LATEST (most recent) / ALL (all orders), default LATEST")
        }
    )
    public String queryOrder(OrderQueryRequest request) {
        return orderFacade.queryOrder(request).toString();
    }

    @AgentTool(
        value = "Submit a refund request",
        params = {
            @ParamDesc(name = "orderId", desc = "Order ID, e.g. ORD-2024-XXXXXX"),
            @ParamDesc(name = "reason",  desc = "Reason: quality issue / logistics delay / unwanted / wrong item received"),
            @ParamDesc(name = "amount",  desc = "Refund amount in CNY; leave blank for full refund")
        }
    )
    public String applyRefund(RefundRequest request) {
        return refundFacade.applyRefund(request).toString();
    }

    @AgentTool(
        value = "Track shipment status",
        params = {
            @ParamDesc(name = "orderId",    desc = "Order ID, e.g. ORD-2024-XXXXXX"),
            @ParamDesc(name = "trackingNo", desc = "Tracking number (optional; auto-looked-up from order ID if omitted)")
        }
    )
    public String trackLogistics(LogisticsQueryRequest request) {
        return logisticsFacade.track(request).toString();
    }
}
```

The framework reads `@AgentTool.params` and auto-generates:

```
Query user order information
  Input JSON keys (OrderQueryRequest):
    - orderId (String): Order ID, e.g. ORD-2024-XXXXXX; leave blank if unknown
    - userId (String): User ID, e.g. USR-XXXXXX; leave blank if unknown
    - queryType (String): Query type: LATEST / ALL, default LATEST
  Action Input format: JSON, e.g. {"orderId": ..., "userId": ..., "queryType": ...}
```

### Sample test (using `AgentExecutor` — ReAct)

See [`dubboAgentDemo()`](../../../src/test/java/org/salt/jlangchain/demo/article/Article19RpcMcpTools.java):

```java
@Test
public void dubboAgentDemo() {
    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(ecommerceDubboTools)
        .maxIterations(8)
        .build();

    agent.invoke("My order ORD-2024-001 has been in transit for days with no delivery. " +
        "Please: 1) check the order details; 2) check where the shipment is; " +
        "3) if there is an anomaly, submit a refund request with reason: logistics delay.");
}
```

> The same `ecommerceDubboTools` can be passed directly to `McpAgentExecutor` without any changes.

### Execution trace

```
[Thought] Query order details first.
Action: query_order  {"orderId": "ORD-2024-001"}
[Service response] Order ORD-2024-001: Sony WH-1000XM5, ¥2199, in transit (2 days past ETA)

[Thought] Order is delayed. Check logistics.
Action: track_logistics  {"orderId": "ORD-2024-001"}
[Service response] SF1234567890, last update 2024-03-12 09:00, no further movement

[Thought] Shipment anomaly confirmed. Submit refund.
Action: apply_refund  {"orderId": "ORD-2024-001", "reason": "logistics delay"}
[Service response] Refund submitted, ¥2199 full refund, ticket TKT-20240316-8821

Final Answer: Here is a summary of what was done...
```

---

## 4. Scenario B: Feign — Own VO + `@Param` on Fields

When the VO belongs to your team, annotate its fields with `@Param` directly. The tool method itself stays clean.

### VO definitions

```java
@Data
public class ProductDetailRequest {
    @Param("Product ID, e.g. PROD-XXXXXX")
    private String productId;

    @Param("Category, e.g. phone/laptop/headphones; optional")
    private String category;
}

@Data
public class InventoryQueryRequest {
    @Param("Product SKU, e.g. SKU-XXXXXX")
    private String sku;

    @Param("Warehouse region: EAST/WEST/SOUTH/NORTH; leave blank to query all")
    private String region;
}

@Data
public class PriceQueryRequest {
    @Param("Product SKU, e.g. SKU-XXXXXX")
    private String sku;

    @Param("User level: VIP/NORMAL, affects discount; default NORMAL")
    private String userLevel;
}
```

### Tool wrapper class

```java
@Component
public class RetailFeignTools {

    @Autowired
    private ProductFeignClient productFeignClient;

    @Autowired
    private InventoryFeignClient inventoryFeignClient;

    @Autowired
    private PricingFeignClient pricingFeignClient;

    @AgentTool("Get product details")
    public String getProductDetail(ProductDetailRequest request) {
        return productFeignClient.getProductDetail(request).toString();
    }

    @AgentTool("Check product inventory")
    public String queryInventory(InventoryQueryRequest request) {
        return inventoryFeignClient.queryInventory(request).toString();
    }

    @AgentTool("Query product price")
    public String queryPrice(PriceQueryRequest request) {
        return pricingFeignClient.queryPrice(request).toString();
    }
}
```

The framework scans VO fields for `@Param` and auto-generates:

```
Check product inventory
  Input JSON keys (InventoryQueryRequest):
    - sku (String): Product SKU, e.g. SKU-XXXXXX
    - region (String): Warehouse region: EAST/WEST/SOUTH/NORTH; leave blank to query all
  Action Input format: JSON, e.g. {"sku": ..., "region": ...}
```

### Sample test (using `McpAgentExecutor` — Function Calling)

See [`feignAgentDemo()`](../../../src/test/java/org/salt/jlangchain/demo/article/Article19RpcMcpTools.java):

```java
@Test
public void feignAgentDemo() {
    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(retailFeignTools)
        .maxIterations(8)
        .build();

    agent.invoke("I want to buy Sony WH-1000XM5 headphones (ID: PROD-SONY-001, SKU: SKU-SONY-WH1000XM5). " +
        "Please: 1) get product details; 2) check EAST warehouse inventory; " +
        "3) I am a VIP user — what is the price?");
}
```

> The same `retailFeignTools` can be passed directly to `AgentExecutor` without any changes.

### Execution trace

```
[Thought] Fetch product details first.
Action: get_product_detail  {"productId": "PROD-SONY-001"}
[Service response] Sony WH-1000XM5, flagship ANC, 30h battery, black/silver

[Thought] Check EAST warehouse inventory.
Action: query_inventory  {"sku": "SKU-SONY-WH1000XM5", "region": "EAST"}
[Service response] EAST warehouse: Black 47, Silver 12 — same-day shipping

[Thought] Query VIP price.
Action: query_price  {"sku": "SKU-SONY-WH1000XM5", "userLevel": "VIP"}
[Service response] ¥1979 (10% VIP discount, save ¥220)

Final Answer: Here is the information for the Sony WH-1000XM5...
```

---

## 5. Scenario Comparison

| Dimension | Dubbo (`@AgentTool.params`) | Feign (`@Param` on field) |
|---|---|---|
| VO modifiable | No (from partner SDK) | Yes (owned by your team) |
| Description location | Tool method annotation | VO field annotation |
| LLM-visible schema | Identical | Identical |
| Works with `AgentExecutor` | ✓ | ✓ |
| Works with `McpAgentExecutor` | ✓ | ✓ |

Both approaches produce exactly the same schema for the LLM. The difference is only where the descriptions are written.

---

## 6. Summary

- **Own VO**: `@Param` on fields — descriptions live in the data model layer
- **Third-party VO**: `@AgentTool.params` with `@ParamDesc` — descriptions live in the tool method
- **Works with both executors**: the same tool definitions work for `AgentExecutor` and `McpAgentExecutor` — no need to maintain two versions
- **Framework transparency**: schema generation, JSON deserialization, and method invocation are all automatic

---

> Full sample: [Article19RpcMcpTools.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article19RpcMcpTools.java)
