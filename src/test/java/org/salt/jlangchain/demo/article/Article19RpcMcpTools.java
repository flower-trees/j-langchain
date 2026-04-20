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

import lombok.Data;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.salt.jlangchain.rag.tools.annotation.ParamDesc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 文章 19：两行注解把企业 RPC 接口变成 AI 工具。
 *
 * <p>演示两种参数描述方式：
 * <ol>
 *   <li>{@link #dubboAgentDemo()} — Dubbo 场景：第三方 VO + {@code @AgentTool.params} 内联描述</li>
 *   <li>{@link #feignAgentDemo()} — Feign 场景：自有 VO + {@code @Param} 标注字段</li>
 * </ol>
 *
 * <p>示例中的 Mock Facade / Mock FeignClient / 工具包装类通过测试配置显式注册为 Spring Bean，
 * 同一套工具定义既可传给 {@link AgentExecutor}，也可传给
 * {@link org.salt.jlangchain.core.agent.McpAgentExecutor}。
 *
 * <p>真实项目中，Mock 类替换为真实的 {@code @DubboReference} / {@code @Autowired FeignClient}，
 * 工具包装类结构不变。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
@Import(Article19RpcMcpTools.TestConfig.class)
public class Article19RpcMcpTools {

    @Autowired
    private ChainActor chainActor;

    @Autowired
    private EcommerceDubboTools ecommerceDubboTools;

    @Autowired
    private RetailFeignTools retailFeignTools;

    // ══════════════════════════════════════════════════════════════════════════
    //  场景一：Dubbo — 第三方 VO + @AgentTool.params 内联描述
    //
    //  VO 来自二方包，字段上无法加 @Param，改用 @AgentTool.params 内联描述。
    // ══════════════════════════════════════════════════════════════════════════

    // ── 第三方 VO（模拟二方包提供，字段上无 @Param）──────────────────────────────

    @Data
    static class OrderQueryRequest {
        private String orderId;
        private String userId;
        private String queryType;
    }

    @Data
    static class RefundRequest {
        private String orderId;
        private String reason;
        private String amount;
    }

    @Data
    static class LogisticsQueryRequest {
        private String orderId;
        private String trackingNo;
    }

    // ── Mock Dubbo Facade（模拟 @DubboReference 注入的 bean）──────────────────

    static class MockOrderFacade {
        public String queryOrder(OrderQueryRequest request) {
            if ("ORD-2024-001".equals(request.getOrderId())) {
                return "订单 ORD-2024-001：商品「索尼 WH-1000XM5 耳机」，金额 ¥2199，" +
                    "2024-03-10 付款，状态：运输中（已超预计到货时间2天）";
            }
            return "未找到订单：" + request.getOrderId();
        }
    }

    static class MockRefundFacade {
        public String applyRefund(RefundRequest request) {
            if ("ORD-2024-001".equals(request.getOrderId())) {
                String amount = (request.getAmount() == null || request.getAmount().isBlank())
                    ? "¥2199（全额）" : "¥" + request.getAmount();
                return "退款申请已提交：订单 " + request.getOrderId() + "，原因：" + request.getReason() +
                    "，退款金额：" + amount + "，工单号：TKT-20240316-8821，预计1~3个工作日处理";
            }
            return "退款申请失败：订单 " + request.getOrderId() + " 不存在或不符合退款条件";
        }
    }

    static class MockLogisticsFacade {
        public String track(LogisticsQueryRequest request) {
            if ("ORD-2024-001".equals(request.getOrderId())) {
                return "快递单号：SF1234567890（顺丰速运）\n" +
                    "2024-03-10 17:30 已揽件，上海浦东分拨中心\n" +
                    "2024-03-12 09:00 派件中，成都青羊区网点（此后无更新，已超预计到货时间2天）";
            }
            return "未查到物流信息，订单号：" + request.getOrderId();
        }
    }

    // ── EcommerceDubboTools：工具包装类，引用 Mock Facade ─────────────────────
    //
    //  真实项目：字段改为 @DubboReference 注入真实 Facade

    static class EcommerceDubboTools {

        private final MockOrderFacade orderFacade;
        private final MockRefundFacade refundFacade;
        private final MockLogisticsFacade logisticsFacade;

        EcommerceDubboTools(MockOrderFacade orderFacade,
                            MockRefundFacade refundFacade,
                            MockLogisticsFacade logisticsFacade) {
            this.orderFacade = orderFacade;
            this.refundFacade = refundFacade;
            this.logisticsFacade = logisticsFacade;
        }

        @AgentTool(
            value = "查询用户订单信息",
            params = {
                @ParamDesc(name = "orderId",   desc = "订单ID，格式：ORD-2024-XXXXXX，如不知道可留空"),
                @ParamDesc(name = "userId",    desc = "用户ID，格式：USR-XXXXXX，如不知道可留空"),
                @ParamDesc(name = "queryType", desc = "查询类型：LATEST（最近一笔）/ ALL（全部订单），默认 LATEST")
            }
        )
        public String queryOrder(OrderQueryRequest request) {
            return orderFacade.queryOrder(request);
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
            return refundFacade.applyRefund(request);
        }

        @AgentTool(
            value = "查询物流配送状态",
            params = {
                @ParamDesc(name = "orderId",    desc = "订单ID，格式：ORD-2024-XXXXXX"),
                @ParamDesc(name = "trackingNo", desc = "快递单号（可选，不填则通过订单ID自动关联查询）")
            }
        )
        public String trackLogistics(LogisticsQueryRequest request) {
            return logisticsFacade.track(request);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  场景二：Feign — 自有 VO + @Param 标注字段
    //
    //  VO 是自己团队定义的，可以直接在字段上加 @Param。
    // ══════════════════════════════════════════════════════════════════════════

    // ── 自有 VO（字段上加 @Param）──────────────────────────────────────────────

    @Data
    static class ProductDetailRequest {
        @Param("商品ID，格式：PROD-XXXXXX")
        private String productId;

        @Param("商品类目，如：手机/笔记本/耳机，可留空")
        private String category;
    }

    @Data
    static class InventoryQueryRequest {
        @Param("商品SKU，格式：SKU-XXXXXX")
        private String sku;

        @Param("仓库区域：EAST/WEST/SOUTH/NORTH，可留空（查全部仓库）")
        private String region;
    }

    @Data
    static class PriceQueryRequest {
        @Param("商品SKU，格式：SKU-XXXXXX")
        private String sku;

        @Param("用户等级：VIP/NORMAL，影响折扣，默认 NORMAL")
        private String userLevel;
    }

    // ── Mock Feign Client（模拟 @Autowired FeignClient 注入的 bean）──────────

    static class MockProductFeignClient {
        public String getProductDetail(ProductDetailRequest request) {
            if ("PROD-SONY-001".equals(request.getProductId())) {
                return "商品「索尼 WH-1000XM5 无线降噪耳机」：主动降噪旗舰款，30h 续航，" +
                    "支持多设备连接，颜色：黑色/银色，蓝牙 5.2，SKU：SKU-SONY-WH1000XM5";
            }
            return "未找到商品：" + request.getProductId();
        }
    }

    static class MockInventoryFeignClient {
        public String queryInventory(InventoryQueryRequest request) {
            if ("SKU-SONY-WH1000XM5".equals(request.getSku())) {
                String area = (request.getRegion() == null || request.getRegion().isBlank())
                    ? "全部仓库" : request.getRegion() + " 仓库";
                return area + "库存：黑色 47 件 / 银色 12 件，均可当日发货";
            }
            return "SKU " + request.getSku() + " 暂无库存数据";
        }
    }

    static class MockPricingFeignClient {
        public String queryPrice(PriceQueryRequest request) {
            if ("SKU-SONY-WH1000XM5".equals(request.getSku())) {
                boolean isVip = "VIP".equalsIgnoreCase(request.getUserLevel());
                return "商品 " + request.getSku() + " 当前售价：" +
                    (isVip ? "¥1979（9折 VIP 优惠，立省 ¥220）" : "¥2199（原价）");
            }
            return "SKU " + request.getSku() + " 暂无报价";
        }
    }

    // ── RetailFeignTools：工具包装类，引用 Mock FeignClient ───────────────────
    //
    //  真实项目：字段改为 @Autowired 注入真实 FeignClient

    static class RetailFeignTools {

        private final MockProductFeignClient productFeignClient;
        private final MockInventoryFeignClient inventoryFeignClient;
        private final MockPricingFeignClient pricingFeignClient;

        RetailFeignTools(MockProductFeignClient productFeignClient,
                         MockInventoryFeignClient inventoryFeignClient,
                         MockPricingFeignClient pricingFeignClient) {
            this.productFeignClient = productFeignClient;
            this.inventoryFeignClient = inventoryFeignClient;
            this.pricingFeignClient = pricingFeignClient;
        }

        @AgentTool("查询商品详情")
        public String getProductDetail(ProductDetailRequest request) {
            return productFeignClient.getProductDetail(request);
        }

        @AgentTool("查询商品库存")
        public String queryInventory(InventoryQueryRequest request) {
            return inventoryFeignClient.queryInventory(request);
        }

        @AgentTool("查询商品价格")
        public String queryPrice(PriceQueryRequest request) {
            return pricingFeignClient.queryPrice(request);
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        MockOrderFacade mockOrderFacade() {
            return new MockOrderFacade();
        }

        @Bean
        MockRefundFacade mockRefundFacade() {
            return new MockRefundFacade();
        }

        @Bean
        MockLogisticsFacade mockLogisticsFacade() {
            return new MockLogisticsFacade();
        }

        @Bean
        EcommerceDubboTools ecommerceDubboTools(MockOrderFacade mockOrderFacade,
                                                MockRefundFacade mockRefundFacade,
                                                MockLogisticsFacade mockLogisticsFacade) {
            return new EcommerceDubboTools(mockOrderFacade, mockRefundFacade, mockLogisticsFacade);
        }

        @Bean
        MockProductFeignClient mockProductFeignClient() {
            return new MockProductFeignClient();
        }

        @Bean
        MockInventoryFeignClient mockInventoryFeignClient() {
            return new MockInventoryFeignClient();
        }

        @Bean
        MockPricingFeignClient mockPricingFeignClient() {
            return new MockPricingFeignClient();
        }

        @Bean
        RetailFeignTools retailFeignTools(MockProductFeignClient mockProductFeignClient,
                                          MockInventoryFeignClient mockInventoryFeignClient,
                                          MockPricingFeignClient mockPricingFeignClient) {
            return new RetailFeignTools(
                    mockProductFeignClient,
                    mockInventoryFeignClient,
                    mockPricingFeignClient
            );
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  测试1：Dubbo Agent — 第三方 VO + @AgentTool.params
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    public void dubboAgentDemo() {
        AgentExecutor agent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(ecommerceDubboTools)
            .maxIterations(8)
            .onThought(t -> System.out.println("[思考] " + t))
            .onObservation(obs -> System.out.println("[服务返回] " + obs))
            .build();

        String userQuestion =
            "我的订单 ORD-2024-001 付款已经好几天了，显示运输中但迟迟没有送达，" +
            "帮我查一下：1) 这个订单的基本信息；2) 目前物流到哪了；" +
            "3) 如果快递确实异常，帮我提交一个退款申请，原因是物流超时。";

        System.out.println("========== 用户咨询（Dubbo 场景） ==========");
        System.out.println(userQuestion);
        System.out.println("\n--- Agent 开始处理 ---\n");

        ChatGeneration result = agent.invoke(userQuestion);

        System.out.println("\n========== 客服回复 ==========");
        System.out.println(result.getText());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  测试2：Feign Agent — 自有 VO + @Param on field
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    public void feignAgentDemo() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(retailFeignTools)
            .maxIterations(8)
            .onToolCall(t -> System.out.println("[思考] " + t))
            .onObservation(obs -> System.out.println("[服务返回] " + obs))
            .build();

        String userQuestion =
            "我想买索尼 WH-1000XM5 耳机（商品ID：PROD-SONY-001，SKU：SKU-SONY-WH1000XM5），" +
            "帮我查一下：1) 这款产品的详情；2) 华东仓库是否有库存；" +
            "3) 我是 VIP 用户，购买价格是多少？";

        System.out.println("========== 用户咨询（Feign 场景） ==========");
        System.out.println(userQuestion);
        System.out.println("\n--- Agent 开始处理 ---\n");

        ChatGeneration result = agent.invoke(userQuestion);

        System.out.println("\n========== 导购回复 ==========");
        System.out.println(result.getText());
    }
}
