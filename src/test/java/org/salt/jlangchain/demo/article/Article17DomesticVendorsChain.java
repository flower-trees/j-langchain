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

import com.coze.openapi.client.auth.OAuthToken;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.ai.vendor.doubao.coze.auth.CozeOAuthHelper;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.deepseek.ChatDeepseek;
import org.salt.jlangchain.core.llm.doubao.ChatCoze;
import org.salt.jlangchain.core.llm.doubao.ChatDoubao;
import org.salt.jlangchain.core.llm.hunyuan.ChatHunyuan;
import org.salt.jlangchain.core.llm.lingyi.ChatLingyi;
import org.salt.jlangchain.core.llm.minimax.ChatMinimax;
import org.salt.jlangchain.core.llm.moonshot.ChatMoonshot;
import org.salt.jlangchain.core.llm.qianfan.ChatQianfan;
import org.salt.jlangchain.core.llm.stepfun.ChatStepfun;
import org.salt.jlangchain.core.llm.zhipu.ChatZhipu;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

/**
 * 文章 17：国内主流厂商顺序链实例。
 *
 * <p>与 {@link Article02ChainPatterns#simpleChain()} 相同形态：PromptTemplate → Chat* → StrOutputParser，
 * 便于对照各厂商 {@link BaseChatModel} 在链中的用法。
 *
 * <p>运行前请配置对应环境变量（见 {@code docs/article/001/17-domestic-vendors-chain.md}）。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article17DomesticVendorsChain {

    @Autowired
    ChainActor chainActor;

    @Autowired
    CozeOAuthHelper cozeOAuthHelper;

    private void runSimpleDomesticChain(String banner, BaseChatModel llm) {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
            "请用一句话（不超过40字）中文回答：${topic}"
        );
        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();
        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "简单自我介绍你的模型身份"));
        System.out.println("=== " + banner + " ===");
        System.out.println(result.getText());
    }

    @Test
    public void chainAliyun() {
        runSimpleDomesticChain("阿里云通义（ALIYUN_KEY）", ChatAliyun.builder().model("qwen-plus").build());
    }

    @Test
    public void chainDoubao() {
        runSimpleDomesticChain(
            "豆包 / 火山方舟（DOUBAO_KEY）",
            ChatDoubao.builder().model("doubao-1-5-lite-32k-250115").build()
        );
    }

    @Test
    public void chainMoonshot() {
        runSimpleDomesticChain("Moonshot Kimi（MOONSHOT_KEY）", ChatMoonshot.builder().model("moonshot-v1-8k").build());
    }

    /**
     * 需要 {@code COZE_KEY}；{@code COZE_BOT_ID} 可选，未设置时使用占位，请替换为控制台真实 Bot。
     */
    @Test
    public void chainCoze() {
        Assume.assumeTrue("需要 COZE_KEY", StringUtils.isNotBlank(System.getenv("COZE_KEY")));
        OAuthToken oAuthToken = cozeOAuthHelper.getAccessToken();
        String botId = StringUtils.defaultIfBlank(System.getenv("COZE_BOT_ID"), "751971414224112XXXX");
        runSimpleDomesticChain("扣子 Coze（COZE_KEY + 有效 COZE_BOT_ID）", ChatCoze.builder().botId(botId).key(oAuthToken.getAccessToken()).build());
    }

    @Test
    public void chainDeepseek() {
        runSimpleDomesticChain("DeepSeek（DEEPSEEK_KEY）", ChatDeepseek.builder().model("deepseek-chat").build());
    }

    @Test
    public void chainHunyuan() {
        runSimpleDomesticChain("腾讯混元（HUNYUAN_KEY）", ChatHunyuan.builder().model("hunyuan-turbo").build());
    }

    @Test
    public void chainQianfan() {
        runSimpleDomesticChain("百度千帆文心（QIANFAN_KEY）", ChatQianfan.builder().model("ernie-4.5-21b-a3b").build());
    }

    @Test
    public void chainZhipu() {
        runSimpleDomesticChain("智谱 GLM（ZHIPU_KEY）", ChatZhipu.builder().model("glm-4-flash").build());
    }

    @Test
    public void chainMinimax() {
        runSimpleDomesticChain("MiniMax（MINIMAX_KEY）", ChatMinimax.builder().model("MiniMax-Text-01").build());
    }

    @Test
    public void chainLingyi() {
        runSimpleDomesticChain("零一万物 Yi（LINGYI_KEY）", ChatLingyi.builder().model("yi-lightning").build());
    }

    @Test
    public void chainStepfun() {
        runSimpleDomesticChain("阶跃星辰 Step（STEPFUN_KEY）", ChatStepfun.builder().model("step-2-16k").build());
    }
}
