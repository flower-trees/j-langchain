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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.ClasspathSkillConfigLoader;
import org.salt.jlangchain.rag.embedding.AliyunEmbeddings;
import org.salt.jlangchain.rag.embedding.Embeddings;
import org.salt.jlangchain.rag.embedding.OllamaEmbeddings;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.rag.vector.InMemoryVectorStore;
import org.salt.jlangchain.rag.vector.Milvus;
import org.salt.jlangchain.rag.vector.VectorStore;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Article 25：扫描版 PDF 文档问答 Agent Demo。
 *
 * <p>演示流程：
 * <ol>
 *   <li>Flow 节点 1：McpAgentExecutor 调用 scanned_pdf_parser skill，完成 PDF 类型判断、OCR 和解析落盘。</li>
 *   <li>Flow 节点 2：Java 节点读取 artifact，按页构建可检索 Document，默认写入内存向量库。</li>
 *   <li>Flow 节点 3：参考 Article03 的 RAG Flow，检索证据、生成答案，并追加本地自检结果。</li>
 * </ol>
 *
 * <p>运行前置：
 * <pre>
 * export ALIYUN_KEY=...
 * export TESSDATA_PREFIX=/opt/homebrew/Cellar/tesseract/5.5.0_1/share/tessdata
 * mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
 *   -Ddocqa.pdf="files/pdf/cn/GBT_1568-2008_键_技术条件.pdf"
 *
 * # 可选：使用 Milvus 后端
 * mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
 *   -Ddocqa.vector=milvus \
 *   -Drag.vector.milvus.use=true
 *
 * # 可选：使用本地 Ollama embedding
 * mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
 *   -Ddocqa.embedding=ollama
 * </pre>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article25DocumentQaAgent {

    private static final String DEFAULT_PDF =
            "files/pdf/cn/GBT_1568-2008_键_技术条件.pdf";
    private static final String SKILL_DIR = "skills/scanned-pdf-parser";
    private static final String COLLECTION_PREFIX = "DocQa_GBT1568_Demo_v1_";
    private static final String DOCQA_EVIDENCE = "DOCQA_EVIDENCE";

    @Autowired
    private ChainActor chainActor;

    private VectorStore vectorStore;
    private String activeDocId;
    private List<Document> storedChunks = List.of();

    @Test
    public void fullDocumentQaDemo() throws Exception {
        String pdfPath = System.getProperty("docqa.pdf", DEFAULT_PDF);

        System.out.println("\n========== Step 1. Parse PDF By Skill Agent ==========");
        Map<String, Object> parseSummary = parsePdfBySkillAgent(pdfPath);
        System.out.println(JsonUtil.toJson(parseSummary));

        System.out.println("\n========== Step 2. Chunk And Store ==========");
        StoreResult storeResult = chunkAndStore(parseSummary);
        this.vectorStore = storeResult.vectorStore();
        this.activeDocId = storeResult.docId();
        this.storedChunks = storeResult.chunks();
        System.out.println(JsonUtil.toJson(storeResult.summary()));

        System.out.println("\n========== Step 3. Ask Questions ==========");
        FlowInstance qaChain = buildQaRagChain();
        List<String> questions = List.of(
                "这个标准的标准号、发布日期和实施日期是什么？",
                "这份标准主要适用于什么范围？",
                "文档中的表格对键的技术条件或尺寸有什么规定？",
                "如果 OCR 里把键写成健，仍然请说明文档对键的要求。",
                "这份标准是否规定了新能源汽车电池接口要求？"
        );

        int round = 0;
        for (String question : questions) {
            if (++round > 5) break;
            System.out.println("\n--- Round " + round + " ---");
            System.out.println("Q: " + question);
            ChatGeneration answer = chainActor.invoke(qaChain, question);
            System.out.println("A: " + answer.getText());
        }
    }

    private Map<String, Object> parsePdfBySkillAgent(String pdfPath) {
        SkillConfig config = ClasspathSkillConfigLoader.fromClasspath(SKILL_DIR);
        BaseChatModel llm = buildLlm();
        Skill skill = Skill.from(config, chainActor)
                .llm(llm)
                .verbose(true)
                .build();

        McpAgentExecutor parserAgent = McpAgentExecutor.builder(chainActor)
                .llm(buildLlm())
                .skill(skill)
                .systemPrompt("""
                        你是 PDF 处理编排 Agent。
                        用户给出 PDF 文件路径时，必须调用 scanned_pdf_parser 技能。
                        最终只输出技能返回的 JSON，不要解释，不要输出 PDF 全文。
                        """)
                .maxIterations(4)
                .onToolCall(tc -> System.out.println("[parser ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[parser Observation] " + abbreviate(obs, 800)))
                .build();

        ChatGeneration result = parserAgent.invoke("请解析这个 PDF，并返回 JSON 摘要：" + pdfPath);
        String json = extractJson(result.getText());
        Map<String, Object> summary = JsonUtil.fromJson(json, Map.class);
        if (summary == null || Boolean.FALSE.equals(summary.get("ok"))) {
            throw new IllegalStateException("PDF parse failed: " + result.getText());
        }
        return summary;
    }

    private StoreResult chunkAndStore(Map<String, Object> parseSummary) throws Exception {
        String artifactPath = String.valueOf(parseSummary.get("artifactPath"));
        JsonNode artifact = JsonUtil.objectMapper.readTree(Files.readString(Path.of(artifactPath)));
        String docId = artifact.path("doc_id").asText();
        String sourcePath = artifact.path("source_path").asText();

        List<Document> chunks = new ArrayList<>();
        for (JsonNode pageNode : artifact.path("pages")) {
            // This demo indexes one page as one chunk. It keeps cover-page facts and table context together.
            int page = pageNode.path("page").asInt();
            String pageText = pageNode.path("ocr_text").asText("").trim();
            ArrayNode blocks = (ArrayNode) pageNode.path("blocks");
            List<String> tableTexts = new ArrayList<>();
            boolean hasTable = false;
            for (JsonNode block : pageNode.path("blocks")) {
                String chunkType = block.path("type").asText("text");
                if ("table".equals(chunkType)) {
                    hasTable = true;
                    String tableText = block.path("text").asText("").trim();
                    if (!tableText.isEmpty()) {
                        tableTexts.add(tableText);
                    }
                }
            }
            if (pageText.isEmpty()) {
                List<String> blockTexts = new ArrayList<>();
                for (JsonNode block : pageNode.path("blocks")) {
                    String blockText = block.path("text").asText("").trim();
                    if (!blockText.isEmpty()) {
                        blockTexts.add(blockText);
                    }
                }
                pageText = String.join("\n\n", blockTexts);
            }
            if (pageText.isEmpty()) {
                continue;
            }

            StringBuilder pageContent = new StringBuilder();
            pageContent.append("[page=").append(page).append("][type=page][hasTable=").append(hasTable).append("]\n");
            pageContent.append(pageText);
            if (!tableTexts.isEmpty()) {
                pageContent.append("\n\n[table_blocks]\n").append(String.join("\n\n", tableTexts));
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("docId", docId);
            metadata.put("sourcePath", sourcePath);
            metadata.put("page", page);
            metadata.put("chunkId", "p" + page);
            metadata.put("chunkType", "page");
            metadata.put("hasTable", hasTable);
            metadata.put("blockCount", blocks.size());

            chunks.add(Document.builder()
                    .pageContent(pageContent.toString())
                    .metadata(metadata)
                    .build());
        }

        if (chunks.isEmpty()) {
            throw new IllegalStateException("No chunks generated from artifact: " + artifactPath);
        }

        String vectorBackend = System.getProperty("docqa.vector", "memory");
        String collectionName = COLLECTION_PREFIX + sanitize(docId);
        VectorStore store = buildVectorStore(vectorBackend, chunks, collectionName);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("docId", docId);
        summary.put("vectorBackend", vectorBackend);
        if ("milvus".equalsIgnoreCase(vectorBackend)) {
            summary.put("collectionName", collectionName);
        }
        summary.put("chunkCount", chunks.size());
        summary.put("sourcePath", sourcePath);
        return new StoreResult(docId, store, chunks, summary);
    }

    private VectorStore buildVectorStore(String vectorBackend, List<Document> chunks, String collectionName) {
        Embeddings embeddings = buildEmbeddings();
        if ("milvus".equalsIgnoreCase(vectorBackend)) {
            return Milvus.fromDocuments(chunks, embeddings, collectionName, 0L);
        }
        return InMemoryVectorStore.fromDocuments(chunks, embeddings, 0L);
    }

    private Embeddings buildEmbeddings() {
        String provider = System.getProperty("docqa.embedding", "aliyun");
        int dimension = Integer.parseInt(System.getProperty("docqa.embedding.dim", "768"));
        if ("ollama".equalsIgnoreCase(provider)) {
            return OllamaEmbeddings.builder()
                    .model(System.getProperty("docqa.embedding.model", "nomic-embed-text"))
                    .vectorSize(dimension)
                    .build();
        }
        return AliyunEmbeddings.builder()
                .model(System.getProperty("docqa.embedding.model", "text-embedding-v4"))
                .vectorSize(dimension)
                .build();
    }

    private FlowInstance buildQaRagChain() {
        // Same shape as Article03: retrieve evidence -> fill prompt -> LLM -> parse -> append self-check.
        PromptTemplate prompt = PromptTemplate.fromTemplate("""
                请只根据下面 evidence 回答用户问题。
                如果 evidence 中没有相关信息，请回答“文档中未找到相关信息”。
                回答必须包含来源页码和 chunkId，例如“来源：第 2 页，chunk=p2_b3”。
                对表格问题，优先使用 chunkType=table 的证据。

                用户问题：
                ${question}

                evidence：
                ${context}
                """);

        return chainActor.builder()
                .next(input -> {
                    String question = input.toString();
                    String evidence = searchDocument(Map.of("question", question, "topK", 4));
                    ContextBus.get().putTransmit(DOCQA_EVIDENCE, evidence);
                    System.out.println("[qa Evidence] " + abbreviate(evidence, 1800));
                    return evidence;
                })
                .next(input -> Map.of(
                        "context", input,
                        "question", ContextBus.get().getFlowParam()
                ))
                .next(prompt)
                .next(buildLlm())
                .next(new StrOutputParser())
                .next(input -> {
                    ChatGeneration generation = (ChatGeneration) input;
                    String question = ContextBus.get().getFlowParam();
                    String evidence = ContextBus.get().getTransmit(DOCQA_EVIDENCE);
                    String check = selfCheck(Map.of(
                            "question", question,
                            "answer", generation.getText(),
                            "evidence", evidence
                    ));
                    generation.setText(generation.getText() + "\n自检：" + check);
                    return generation;
                })
                .build();
    }

    private String searchDocument(Map<String, Object> args) {
        if (vectorStore == null) {
            return "{\"ok\":false,\"error\":\"vector store is not initialized\"}";
        }
        String question = String.valueOf(args.getOrDefault("question", ""));
        int topK = intValue(args.get("topK"), 5);
        List<Document> docs = hybridRetrieve(question, vectorStore.similaritySearch(question, Math.max(topK, 5)), topK);

        ArrayNode result = JsonUtil.objectMapper.createArrayNode();
        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
            result.add(JsonUtil.objectMapper.valueToTree(Map.of(
                    "docId", activeDocId,
                    "page", meta.getOrDefault("page", ""),
                    "chunkId", meta.getOrDefault("chunkId", ""),
                    "chunkType", meta.getOrDefault("chunkType", ""),
                    "sectionNo", meta.getOrDefault("sectionNo", ""),
                    "sectionTitle", meta.getOrDefault("sectionTitle", ""),
                    "snippet", buildSnippet(doc, question)
            )));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("question", question);
        payload.put("evidence", result);
        return JsonUtil.toJson(payload);
    }

    private String selfCheck(Map<String, Object> args) {
        String answer = String.valueOf(args.getOrDefault("answer", ""));
        String evidence = String.valueOf(args.getOrDefault("evidence", ""));
        boolean hasEvidence = hasUsableEvidence(evidence);
        boolean hasCitation = answer.contains("第") && (answer.contains("页") || answer.contains("chunk"));
        String risk = hasEvidence && hasCitation ? "LOW" : hasEvidence ? "MEDIUM" : "HIGH";
        String reason;
        if (!hasEvidence) {
            reason = "未提供可核验的 evidence，建议拒答。";
        } else if (!hasCitation) {
            reason = "答案有检索证据，但缺少明确页码或 chunk 引用。";
        } else {
            reason = "答案包含检索证据和来源引用。";
        }
        return JsonUtil.toJson(Map.of(
                "grounded", hasEvidence && hasCitation,
                "risk", risk,
                "reason", reason,
                "refuseSuggested", !hasEvidence
        ));
    }

    private List<Document> hybridRetrieve(String question, List<Document> vectorDocs, int topK) {
        // Hybrid retrieval stabilizes exact tokens such as GB/T numbers, dates, table names and AQL.
        List<Document> merged = new ArrayList<>(vectorDocs);
        Set<String> seen = new HashSet<>();
        vectorDocs.stream().map(this::dedupKey).forEach(seen::add);

        List<String> terms = queryTerms(question);
        storedChunks.stream()
                .map(doc -> Map.entry(doc, retrievalScore(doc, terms, question)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Document, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .forEach(doc -> {
                    String key = dedupKey(doc);
                    if (!seen.contains(key)) {
                        merged.add(doc);
                        seen.add(key);
                    }
                });

        return merged.stream()
                .sorted(Comparator.comparingInt((Document doc) -> retrievalScore(doc, terms, question)).reversed())
                .limit(topK)
                .toList();
    }

    private String dedupKey(Document doc) {
        Map<String, Object> meta = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
        Object chunkId = meta.get("chunkId");
        if (chunkId != null && !chunkId.toString().isBlank()) {
            return chunkId.toString();
        }
        return String.valueOf(doc.getId());
    }

    private List<String> queryTerms(String question) {
        List<String> terms = new ArrayList<>();
        Map<String, List<String>> aliases = Map.of(
                "标准号", List.of("GB/T", "1568", "标准号"),
                "发布日期", List.of("发布", "发布日期", "2008"),
                "实施日期", List.of("实施", "实施日期", "2009"),
                "表格", List.of("表 1", "表1", "检查项目", "AQL"),
                "尺寸", List.of("尺寸检查", "检查项目", "AQL"),
                "技术要求", List.of("技术要求", "抗拉强度", "裂纹", "毛刺")
        );
        for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
            if (question.contains(entry.getKey())) {
                terms.addAll(entry.getValue());
            }
        }
        for (String token : question.split("[\\s,，。？?、:：]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms.stream().distinct().toList();
    }

    private int keywordScore(String text, List<String> terms) {
        if (text == null || terms.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String term : terms) {
            if (!term.isBlank() && text.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private int retrievalScore(Document doc, List<String> terms, String question) {
        String text = doc.getPageContent();
        int score = keywordScore(text, terms);
        Map<String, Object> meta = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
        String chunkType = String.valueOf(meta.getOrDefault("chunkType", ""));
        boolean hasTable = Boolean.parseBoolean(String.valueOf(meta.getOrDefault("hasTable", "false")));
        if (("table".equalsIgnoreCase(chunkType) || hasTable) && isTableQuestion(question)) {
            score += 5;
        }
        return score;
    }

    private boolean isTableQuestion(String question) {
        return question.contains("表") || question.contains("表格") || question.contains("尺寸")
                || question.contains("检查项目") || question.contains("AQL");
    }

    private String buildSnippet(Document doc, String question) {
        Map<String, Object> meta = doc.getMetadata() != null ? doc.getMetadata() : Map.of();
        String chunkType = String.valueOf(meta.getOrDefault("chunkType", "text"));
        boolean hasTable = Boolean.parseBoolean(String.valueOf(meta.getOrDefault("hasTable", "false")));
        int maxLen = ("table".equalsIgnoreCase(chunkType) || hasTable) ? 1200 : 650;
        String text = doc.getPageContent();
        int hit = firstKeywordHit(text, queryTerms(question));
        if (hit < 0 || text == null || text.length() <= maxLen) {
            return abbreviate(text, maxLen);
        }
        int start = Math.max(0, hit - maxLen / 3);
        int end = Math.min(text.length(), start + maxLen);
        return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
    }

    private int firstKeywordHit(String text, List<String> terms) {
        if (text == null || terms.isEmpty()) {
            return -1;
        }
        int hit = -1;
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            int index = text.indexOf(term);
            if (index >= 0 && (hit < 0 || index < hit)) {
                hit = index;
            }
        }
        return hit;
    }

    private boolean hasUsableEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return false;
        }
        if (evidence.contains("snippet") || evidence.contains("[page=") || evidence.contains("chunkId") || evidence.contains("chunk=")) {
            return true;
        }
        return evidence.replaceAll("\\s+", "").length() >= 20;
    }

    private BaseChatModel buildLlm() {
        return ChatAliyun.builder()
                .model(System.getProperty("docqa.llm.model", "qwen-plus"))
                .temperature(0f)
                .build();
    }

    private static String extractJson(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty model output");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("model output is not JSON: " + text);
        }
        return text.substring(start, end + 1);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static int intValue(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private record StoreResult(String docId, VectorStore vectorStore, List<Document> chunks, Map<String, Object> summary) {
    }
}
