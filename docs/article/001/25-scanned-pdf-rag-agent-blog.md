# 用 Java 构建扫描 PDF 文档问答 Agent：从 OCR 到可溯源 RAG

> **适合人群**：正在做企业知识库、文档问答、合同/标准/手册解析的 Java 开发者  
> **核心技术**：扫描 PDF、OCR、Skill、RAG、Hybrid Retrieval、来源引用、自检

---

## 为什么扫描 PDF 不是普通 PDF？

在前面的 RAG 示例里，我们通常假设 PDF 能直接提取文本：

```
PDF -> PdfboxLoader -> TextSplitter -> Embedding -> VectorStore -> LLM
```

但真实企业文档里，经常遇到扫描件：

- `pdftotext` 提取不到文字
- PDFBox 只能看到图片
- OCR 会把中文识别错
- 表格列结构容易丢
- 标准号、日期、条款号这类精确字段很容易被召回漏掉

这类文档不能简单套普通 RAG。本文用 j-langchain 实现一个扫描 PDF 文档问答 Agent 原型，目标是完成完整闭环：

```
扫描 PDF
  -> OCR 解析
  -> 结构化 artifact
  -> 构建知识库
  -> 检索问答
  -> 来源引用
  -> 答案自检
```

对应示例代码：

```text
src/test/java/org/salt/jlangchain/demo/article/Article25DocumentQaAgent.java
src/test/resources/skills/scanned-pdf-parser/
```

---

## 整体方案

最终流程如下：

```text
PDF 路径
  ↓
McpAgentExecutor
  ↓
scanned_pdf_parser Skill
  ↓
parse_pdf.py
  ├─ 文本层检测
  ├─ 页面渲染
  ├─ 阿里云视觉 OCR
  ├─ Tesseract fallback
  └─ parsed.json artifact
  ↓
Java ingestion
  ├─ 每页一个 Document
  ├─ metadata 记录页码/表格信息
  └─ InMemoryVectorStore
  ↓
RAG Flow
  ├─ hybrid retrieval
  ├─ PromptTemplate
  ├─ ChatAliyun
  ├─ StrOutputParser
  └─ self-check
```

这套方案里，LLM 不直接读取 PDF 全文。它只读取检索出来的 evidence，并且回答必须带来源页码和 chunkId。

---

## Step 1：先判断 PDF 是不是扫描件

扫描 PDF 最明显的特征是文本层很少甚至为空。可以先用 `pdftotext` 或 PDFBox 测一遍。

这个示例里的 PDF，用 `pdftotext` 基本提取不到正文，因此需要走 OCR。

在 `parse_pdf.py` 中，先检查每页文本层：

```python
def text_layer(pdf_path, page):
    if not command_exists("pdftotext"):
        return ""
    proc = run(["pdftotext", "-f", str(page), "-l", str(page), pdf_path, "-"])
    if proc.returncode != 0:
        return ""
    return proc.stdout.strip()
```

如果文本层足够，就直接使用；否则将页面渲染成图片进入 OCR。

---

## Step 2：用 Skill 封装 PDF 解析

PDF 解析不是一个简单 Java 类能优雅解决的问题。它涉及：

- PDF 页面渲染
- OCR 服务调用
- 表格结构恢复
- OCR 缓存
- fallback 策略

因此这里用 Skill 封装。

Skill 文件：

```text
src/test/resources/skills/scanned-pdf-parser/SKILL.md
```

核心要求：

```markdown
1. 必须调用 parse_pdf。
2. 不要把 PDF 全文复制到最终回答。
3. 最终只返回 parse_pdf 的 JSON 摘要结果。
4. 如果脚本返回错误，原样返回错误 JSON。
```

Java 侧通过 `McpAgentExecutor` 调用这个 Skill：

```java
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath(SKILL_DIR);
Skill skill = Skill.from(config, chainActor)
        .llm(buildLlm())
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
        .build();
```

注意这里有个关键点：**Skill 只返回摘要，不返回全文**。完整 OCR 内容写入本地 artifact，后续由 Java ingestion 读取。

---

## Step 3：渲染页面并调用 OCR

扫描 PDF 的每一页本质上是一张图。脚本先用 `pdftoppm` 渲染：

```python
def render_page(pdf_path, page, work_dir):
    prefix = str(work_dir / f"page_{page}")
    render = run([
        "pdftoppm", "-r", "220",
        "-f", str(page), "-l", str(page),
        "-png", pdf_path, prefix
    ])
```

然后默认调用阿里云视觉 OCR：

```python
backend = os.environ.get("DOCQA_OCR_BACKEND", "aliyun").lower()
if backend == "aliyun":
    page_text, blocks, warning = ocr_page_aliyun(image_path, page)
    if page_text or blocks:
        return page_text, blocks, warning, "aliyun_vision"
```

如果云 OCR 不可用，再 fallback 到 Tesseract：

```python
page_text, blocks, warning = ocr_page_tesseract(image_path)
return page_text, blocks, warning, "tesseract"
```

为什么要用云 OCR？因为本地 Tesseract 对中文扫描表格效果不稳定，尤其是表格列结构。视觉模型可以直接要求输出 JSON 和 markdown 表格。

OCR prompt 中会明确要求：

```text
1. 保留中文、英文、标准号、日期、单位和条款编号。
2. 表格必须作为 type=table 单独输出，并尽量恢复为 markdown 表格。
3. 不要翻译原文。
4. 看不清的字用 [unclear] 标注，不要编造。
```

---

## Step 4：生成 parsed.json artifact

解析脚本最终写出一个结构化文件：

```text
target/docqa/parsed/{docId}/parsed.json
```

示例结构：

```json
{
  "ok": true,
  "doc_id": "GBT_1568-2008_键_技术条件_7706a8c3ec",
  "source_path": "...",
  "pdf_type": "SCANNED_PDF",
  "pages": [
    {
      "page": 1,
      "strategy": "aliyun_vision",
      "ocr_text": "GB/T 1568—2008 ...",
      "blocks": [
        {
          "block_id": "p1_b4",
          "type": "text",
          "text": "GB/T 1568—2008\n代替 GB/T 1568—1997"
        }
      ]
    }
  ],
  "warnings": []
}
```

脚本会先检查 artifact 是否已存在：

```python
artifact_path = out_dir / "parsed.json"
if artifact_path.exists():
    emit(artifact_summary(artifact_path))
    return
```

调试时不需要每次重新 OCR。如果想强制重跑，删除对应目录即可：

```bash
rm -rf target/docqa/parsed/{docId}
```

---

## Step 5：为什么最终选择“每页一个 chunk”？

一开始我们尝试把每个 OCR block 单独入库。问题很快出现了。

封面被拆成多个小块：

```text
p1_b4: GB/T 1568—2008
p1_b6: 2008-09-22 发布
p1_b7: 2009-05-01 实施
```

当用户问：

```text
这个标准的标准号、发布日期和实施日期是什么？
```

检索必须同时召回这三个 block。只要排序稍微偏一点，答案就会漏掉字段。

这个 PDF 只有 4 页，每页文本量可控，所以最终改成页级 chunk：

```text
p1: 封面
p2: 前言
p3: 范围、规范性引用文件、技术要求、验收检查
p4: 表1、标志与包装
```

Java ingestion 中每页生成一个 `Document`：

```java
pageContent.append("[page=").append(page)
    .append("][type=page][hasTable=").append(hasTable).append("]\n");
pageContent.append(pageText);

if (!tableTexts.isEmpty()) {
    pageContent.append("\n\n[table_blocks]\n")
        .append(String.join("\n\n", tableTexts));
}
```

metadata 记录页码和表格信息：

```java
metadata.put("page", page);
metadata.put("chunkId", "p" + page);
metadata.put("chunkType", "page");
metadata.put("hasTable", hasTable);
metadata.put("blockCount", blocks.size());
```

对于短文档，页级 chunk 的稳定性优于过细切分。长文档后续可以升级为 parent-child chunk。

---

## Step 6：轻量内存向量库

Demo 默认不使用 Milvus，而是新增了：

```text
src/main/java/org/salt/jlangchain/rag/vector/InMemoryVectorStore.java
```

它继承现有 `VectorStore`：

```java
public class InMemoryVectorStore extends VectorStore
```

核心逻辑：

```java
List<List<Float>> embeddings = embeddingFunction.embedDocuments(texts);
entries.add(new Entry(id, fileId, text, metadata, embedding));
```

查询时做 cosine similarity：

```java
return entries.stream()
    .map(entry -> new ScoredEntry(entry, cosine(queryVector, entry.getVector())))
    .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
    .limit(k)
    .map(scored -> toDocument(scored.entry()))
    .toList();
```

这样本地运行不依赖外部向量数据库。需要生产化时，可以通过参数切换到 Milvus：

```bash
-Ddocqa.vector=milvus -Drag.vector.milvus.use=true
```

---

## Step 7：参考 Article03 实现 RAG Flow

问答阶段没有继续使用多轮 Agent，而是参考 `Article03RagPipeline#retrieveAndAsk`，使用确定性 RAG Flow：

```text
question
  -> searchDocument
  -> PromptTemplate
  -> ChatAliyun
  -> StrOutputParser
  -> selfCheck
```

代码结构：

```java
return chainActor.builder()
    .next(input -> {
        String question = input.toString();
        String evidence = searchDocument(Map.of("question", question, "topK", 4));
        ContextBus.get().putTransmit(DOCQA_EVIDENCE, evidence);
        return evidence;
    })
    .next(input -> Map.of(
        "context", input,
        "question", ContextBus.get().getFlowParam()
    ))
    .next(prompt)
    .next(buildLlm())
    .next(new StrOutputParser())
    .next(input -> appendSelfCheck(input))
    .build();
```

为什么不用 QA Agent？

- 多轮 function calling 会慢
- 容易重复检索
- evidence 会越塞越长
- 文档 QA 本来就是确定性链路

Agent 仍然用于解析阶段，因为那里需要通过 Skill 调用脚本能力。

---

## Step 8：Hybrid Retrieval

纯向量检索对语义问题很有效，但对短 token 不一定稳定，例如：

- `GB/T 1568—2008`
- `2008-09-22`
- `表1`
- `AQL`
- `1:100`

因此检索阶段采用轻量 hybrid retrieval：

```java
List<Document> docs = hybridRetrieve(
    question,
    vectorStore.similaritySearch(question, Math.max(topK, 5)),
    topK
);
```

`hybridRetrieve` 会：

1. 使用向量检索获得候选页
2. 遍历全部页级 chunk 做关键词补召回
3. 按 `retrievalScore` 重排
4. 表格类问题对 `hasTable=true` 的页加权

简化后的 scoring：

```java
private int retrievalScore(Document doc, List<String> terms, String question) {
    int score = keywordScore(doc.getPageContent(), terms);
    if (hasTable(doc) && isTableQuestion(question)) {
        score += 5;
    }
    return score;
}
```

这不是复杂搜索引擎，只是为 Demo 增加一点稳定性。

---

## Step 9：答案自检

RAG 不仅要回答，还要知道什么时候不该回答。

本 Demo 的自检是轻量规则：

```java
boolean hasEvidence = hasUsableEvidence(evidence);
boolean hasCitation = answer.contains("第") && (answer.contains("页") || answer.contains("chunk"));
String risk = hasEvidence && hasCitation ? "LOW" : hasEvidence ? "MEDIUM" : "HIGH";
```

输出示例：

```json
{
  "grounded": true,
  "risk": "LOW",
  "reason": "答案包含检索证据和来源引用。",
  "refuseSuggested": false
}
```

它不是严格的事实验证器，但能覆盖基础问题：

- 是否有 evidence
- 是否有来源引用
- 是否建议拒答

---

## Step 10：演示问题

Demo 固定 5 轮问题：

```java
List<String> questions = List.of(
    "这个标准的标准号、发布日期和实施日期是什么？",
    "这份标准主要适用于什么范围？",
    "文档中的表格对键的技术条件或尺寸有什么规定？",
    "如果 OCR 里把键写成健，仍然请说明文档对键的要求。",
    "这份标准是否规定了新能源汽车电池接口要求？"
);
```

覆盖：

- 封面精确字段
- 正文章节
- 表格
- OCR 容错
- 无答案拒答

---

## 踩坑记录

### 1. Tesseract 中文模型损坏

一开始 `tesseract --list-langs` 能看到 `chi_sim`，但实际 OCR 报错：

```text
Failed loading language 'chi_sim'
```

原因是 `chi_sim.traineddata` 文件不完整。修复后 Tesseract 能识别中文，但表格效果仍不理想，所以最终默认使用阿里云视觉 OCR。

### 2. 阿里云 embedding batch size

阿里云 embedding 一次最多 10 条 input。最初一次提交全部 chunk，接口返回：

```text
batch size is invalid, it should not be larger than 10
```

后来在 `AliyunEmbeddings` 中做了自动分批。

### 3. block 级 chunk 召回不完整

标准号、发布日期、实施日期被拆成不同 block，导致一个问题需要召回多个小块。改成页级 chunk 后稳定很多。

### 4. QA Agent 多轮调用慢

使用 `McpAgentExecutor` 做问答时，一个问题可能触发多次 LLM 调用和多次检索。改为固定 RAG Flow 后，每个问题只需要一次检索和一次 LLM 回答。

---

## 运行方式

默认使用：

- 阿里云 OCR
- 阿里云 Chat / Embedding
- 内存向量库

运行：

```bash
export ALIYUN_KEY=你的阿里云百炼Key

mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo
```

指定 PDF：

```bash
mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
  -Ddocqa.pdf="/tmp/GBT_1568-2008_键_技术条件.pdf"
```

使用 Milvus：

```bash
mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
  -Ddocqa.vector=milvus \
  -Drag.vector.milvus.use=true
```

使用本地 Ollama embedding：

```bash
mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
  -Ddocqa.embedding=ollama
```

---

## 后续优化

### 长文档：parent-child chunk

短文档可以按页切。长文档更适合：

```text
page chunk 作为 parent
section/table chunk 作为 child
检索 child，回答时补 parent 上下文
```

### 更强表格检索

可以为表格单独建立结构化索引：

- table name
- row header
- column header
- cell value

表格问题优先查结构化表格，再回退向量检索。

### 更严格自检

当前自检只是规则型。后续可以增加：

- 数值一致性检查
- 引用覆盖率检查
- 表格行列校验
- LLM judge 二次核验
- 低置信度人工复核

### 多业务场景迁移

金融、合规、客户交付等场景可以复用这条主链路：

```text
解析 Skill -> artifact -> metadata 入库 -> hybrid retrieval -> 引用回答 -> 自检
```

差异主要沉淀到 profile 中：

```text
DocumentProfile
  -> OCR prompt
  -> metadata schema
  -> chunk policy
  -> retrieval policy
  -> self-check policy
```

比如金融文档要重点校验数值、单位、报告期；合规文档要重点保留条款号、生效日期；客户交付文档要增加客户、项目、版本和权限过滤。

---

## 小结

扫描 PDF RAG 的关键不在于“把 OCR 文本丢进向量库”，而在于工程链路的可靠性：

- OCR 结果要结构化落盘
- 不要把全文直接塞给 LLM
- chunk 粒度要服务检索目标
- 短文档可以优先页级 chunk
- 检索要结合向量和关键词
- 答案必须带来源
- 无依据时要拒答

这个 Demo 不是完整商业系统，但覆盖了扫描文档问答从解析到检索、回答、自检的最小闭环，也为后续扩展到金融、合规、客户交付等场景打下了基础。

