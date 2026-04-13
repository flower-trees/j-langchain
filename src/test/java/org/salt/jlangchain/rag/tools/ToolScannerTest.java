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

package org.salt.jlangchain.rag.tools;

import lombok.Data;
import org.junit.Assert;
import org.junit.Test;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;

import java.util.List;

/**
 * Unit tests for {@link ToolScanner} – no Spring context needed.
 *
 * Covers:
 *   1. Single String parameter (existing behavior)
 *   2. Multi-parameter (existing behavior)
 *   3. Single complex-type parameter with @Param on fields (new behavior)
 */
public class ToolScannerTest {

    // ── fixture: parameter object ──────────────────────────────────────────

    @Data
    public static class BookFlightRequest {
        @Param("出发城市")
        private String fromCity;

        @Param("目的城市")
        private String toCity;

        @Param("日期，格式 YYYY-MM-DD")
        private String date;
    }

    // ── fixture: tool provider ─────────────────────────────────────────────

    public static class FlightTools {

        @AgentTool("获取城市天气")
        public String getWeather(@Param("城市名称") String city) {
            return city + " 天气晴，25°C";
        }

        @AgentTool("订机票，需要出发城市、目的城市和日期")
        public String bookFlight(BookFlightRequest req) {
            return String.format("已成功预订 %s → %s，日期 %s 的机票",
                    req.getFromCity(), req.getToCity(), req.getDate());
        }

        @AgentTool("多参数示例")
        public String multiParam(
                @Param("出发城市") String from,
                @Param("目的城市") String to) {
            return from + " → " + to;
        }
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    public void scanProducesCorrectNumberOfTools() {
        List<Tool> tools = ToolScanner.scan(new FlightTools());
        System.out.println("=== 扫描到的工具列表 ===");
        tools.forEach(t -> System.out.printf("  [%s]  params: %s%n  description: %s%n%n",
                t.getName(), t.getParams(), t.getDescription()));
        Assert.assertEquals(3, tools.size());
    }

    @Test
    public void singleStringParam_passesRawString() {
        List<Tool> tools = ToolScanner.scan(new FlightTools());
        Tool weather = tools.stream()
                .filter(t -> t.getName().equals("get_weather"))
                .findFirst()
                .orElseThrow();

        System.out.println("=== 单字符串参数工具 ===");
        System.out.println("name       : " + weather.getName());
        System.out.println("description: " + weather.getDescription());
        String result = (String) weather.getFunc().apply("上海");
        System.out.println("result     : " + result);
        Assert.assertEquals("上海 天气晴，25°C", result);
    }

    @Test
    public void complexObjectParam_deserializesFromJson() {
        List<Tool> tools = ToolScanner.scan(new FlightTools());
        Tool book = tools.stream()
                .filter(t -> t.getName().equals("book_flight"))
                .findFirst()
                .orElseThrow();

        System.out.println("=== 对象类型参数工具（schema）===");
        System.out.println("name       : " + book.getName());
        System.out.println("description:\n" + book.getDescription());

        // Verify description contains field schema
        Assert.assertTrue(book.getDescription().contains("fromCity"));
        Assert.assertTrue(book.getDescription().contains("出发城市"));
        Assert.assertTrue(book.getDescription().contains("toCity"));
        Assert.assertTrue(book.getDescription().contains("date"));

        // Verify JSON deserialization and method invocation
        String json = "{\"fromCity\":\"北京\",\"toCity\":\"上海\",\"date\":\"2026-05-01\"}";
        System.out.println("input JSON : " + json);
        String result = (String) book.getFunc().apply(json);
        System.out.println("result     : " + result);
        Assert.assertEquals("已成功预订 北京 → 上海，日期 2026-05-01 的机票", result);
    }

    @Test
    public void complexObjectParam_schemaContainsJsonExample() {
        List<Tool> tools = ToolScanner.scan(new FlightTools());
        Tool book = tools.stream()
                .filter(t -> t.getName().equals("book_flight"))
                .findFirst()
                .orElseThrow();

        System.out.println("=== 对象类型参数工具（JSON example）===");
        System.out.println("description:\n" + book.getDescription());
        Assert.assertTrue(book.getDescription().contains("Action Input format: JSON"));
        Assert.assertTrue(book.getDescription().contains("\"fromCity\""));
    }

    @Test
    public void multiParam_jsonInputDispatchedCorrectly() {
        List<Tool> tools = ToolScanner.scan(new FlightTools());
        Tool multi = tools.stream()
                .filter(t -> t.getName().equals("multi_param"))
                .findFirst()
                .orElseThrow();

        System.out.println("=== 多参数工具 ===");
        System.out.println("name       : " + multi.getName());
        System.out.println("description:\n" + multi.getDescription());
        String json = "{\"from\":\"广州\",\"to\":\"深圳\"}";
        System.out.println("input JSON : " + json);
        String result = (String) multi.getFunc().apply(json);
        System.out.println("result     : " + result);
        Assert.assertEquals("广州 → 深圳", result);
    }
}
