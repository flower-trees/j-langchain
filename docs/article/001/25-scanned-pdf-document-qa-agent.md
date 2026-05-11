# 扫描 PDF 智能文档问答 Agent 原型设计与实现

> **适合人群**：需要处理扫描版合同、标准文件、说明书、合规材料的 Java / Agent 开发者  
> **核心技术**：Skill、OCR、RAG、Hybrid Retrieval、来源引用、自检拒答

---

## 背景与目标

真实业务里的 PDF 很多不是“可复制文本”的电子文档，而是扫描件。扫描件常见问题包括：

- 没有文本层，`pdftotext` 或 PDFBox 提取为空
- 中文 OCR 容易错字、漏字
- 表格结构容易丢失
- 条款编号、页码、标准号等精确字段对检索很关键
- 用户可能提出文档中没有答案的问题

本 Demo 以 `GBT 1568-2008 键 技术条件.pdf` 为例，实现一个最小可运行的“扫描 PDF 文档问答 Agent”闭环：

```
扫描 PDF
  -> PDF 类型判断与 OCR 解析
  -> 结构化 artifact
  -> 构建检索知识库
  -> 用户问答
  -> 来源引用
  -> 基础自检与拒答
```

对应代码入口：

```text
src/test/java/org/salt/jlangchain/demo/article/Article25DocumentQaAgent.java
src/test/resources/skills/scanned-pdf-parser/
```

---

## 总体架构

整体链路分为三个阶段：

```text
PDF path
  -> scanned_pdf_parser Skill
      -> parse_pdf.py
      -> 文本层检测
      -> 页面渲染
      -> 阿里云视觉 OCR / Tesseract fallback
      -> parsed.json artifact

  -> Java ingestion
      -> 每页构建一个 Document
      -> 默认写入 InMemoryVectorStore
      -> 可选写入 Milvus

  -> QA RAG Flow
      -> hybrid retrieval
      -> PromptTemplate
      -> ChatAliyun
      -> StrOutputParser
      -> Java self-check
```

这里有几个明确边界：

- **Skill**：负责 PDF 解析、OCR、表格识别这类更适合脚本和外部服务处理的能力。
- **Flow**：负责确定性编排，避免让 LLM 自主决定每一步是否执行。
- **VectorStore**：负责检索，默认内存向量库保证演示可复现。
- **LLM**：只基于检索 evidence 回答，不直接读取全文。

---

## 关键设计取舍

### 1. 为什么用 Skill 调 Python 解析 PDF？

现有 `PdfboxLoader` 对文本层 PDF 很方便，但本题 PDF 是扫描件，直接提取文本基本为空。OCR 和表格恢复更适合通过脚本集成图像渲染、云 OCR、fallback 策略。

因此解析阶段采用：

```text
McpAgentExecutor -> scanned_pdf_parser Skill -> parse_pdf.py
```

Skill 的最终输出只返回摘要，不把全文塞回 LLM 上下文，避免污染上下文或超过窗口。

### 2. 为什么默认使用阿里云视觉 OCR？

本地 Tesseract 对中文扫描标准文件和表格效果不稳定，尤其表格列结构容易丢失。`parse_pdf.py` 默认使用阿里云视觉模型进行 OCR，并保留 Tesseract 作为 fallback。

这让 Demo 能覆盖：

- 中文正文识别
- 标准号、日期、条款编号
- 表格 markdown 恢复
- OCR 失败 warnings

### 3. 为什么按“页”作为 chunk？

最初按 OCR block 入库时，封面会被拆成多个小块：

```text
p1_b4: GB/T 1568—2008
p1_b6: 2008-09-22 发布
p1_b7: 2009-05-01 实施
```

用户问“标准号、发布日期、实施日期”时，需要同时召回多个 block，容易漏字段。

本 PDF 只有 4 页，每页文本量可控，因此最终改为“每页一个 Document”：

```text
p1: 封面
p2: 前言
p3: 范围、规范性引用文件、技术要求、验收检查
p4: 表1、标志与包装
```

这样同页上下文保持完整，来源页码也天然准确。

### 4. 为什么默认用 InMemoryVectorStore？

Milvus 更接近生产环境，但对 Demo 偏重，需要额外服务。为了让 Demo 默认可运行，新增轻量内存向量库：

```text
src/main/java/org/salt/jlangchain/rag/vector/InMemoryVectorStore.java
```

它使用现有 `Embeddings` 生成向量，在内存中做 cosine similarity 检索。Milvus 仍保留为可选后端。

### 5. 为什么 QA 用固定 RAG Flow，而不是多轮 Agent？

文档问答本质是确定性 RAG：

```text
问题 -> 检索 -> 基于证据回答 -> 自检
```

如果让 QA Agent 自主 function calling，可能出现重复检索、上下文变长、响应变慢甚至超时。最终采用类似 `Article03RagPipeline#retrieveAndAsk` 的 Flow 结构，保证稳定和可解释。

---

## 实现说明

### 解析 Skill

Skill 文件：

```text
src/test/resources/skills/scanned-pdf-parser/SKILL.md
```

核心要求：

- 必须调用 `parse_pdf`
- 不返回 PDF 全文
- 只返回 JSON 摘要

脚本文件：

```text
src/test/resources/skills/scanned-pdf-parser/scripts/parse_pdf.py
```

脚本职责：

1. 读取 PDF path
2. 计算 `docId`
3. 如果 artifact 已存在，直接返回缓存摘要
4. 判断文本层
5. 渲染页面图片
6. 调用阿里云视觉 OCR
7. 失败时 fallback 到 Tesseract
8. 生成 `parsed.json`

artifact 输出位置：

```text
target/docqa/parsed/{docId}/parsed.json
```

如果需要强制重新 OCR，删除该目录即可。

### 页级 Document 构建

`Article25DocumentQaAgent#chunkAndStore` 会读取 `parsed.json`，每页构建一个 `Document`。如果页面中有表格 block，会把表格 markdown 追加到页内容中的 `[table_blocks]` 区域。

metadata 保留：

```java
metadata.put("page", page);
metadata.put("chunkId", "p" + page);
metadata.put("chunkType", "page");
metadata.put("hasTable", hasTable);
metadata.put("blockCount", blocks.size());
```

### 内存向量库

`InMemoryVectorStore` 继承现有 `VectorStore`：

```java
public class InMemoryVectorStore extends VectorStore
```

核心能力：

- `addDocument`：写入文本、metadata、embedding
- `similaritySearch`：对 query embedding 做 cosine 排序
- `asRetriever`：复用现有 `VectorStoreRetriever`

它不适合生产大规模数据，但非常适合本地 Demo 和单元测试。

### 问答 RAG Flow

问答链路参考 `Article03RagPipeline`：

```java
return chainActor.builder()
    .next(input -> {
        String evidence = searchDocument(Map.of("question", input.toString(), "topK", 4));
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

每个问题只进行一次检索和一次 LLM 回答，最后追加 Java 本地自检。

---

## 关键代码片段

### 1. Python artifact 缓存

```python
artifact_path = out_dir / "parsed.json"
if artifact_path.exists():
    emit(artifact_summary(artifact_path))
    return
```

解析 PDF 是最慢的步骤，缓存放在脚本侧，Java 主流程无需关心是否重跑 OCR。

### 2. OCR 后端选择

```python
backend = os.environ.get("DOCQA_OCR_BACKEND", "aliyun").lower()
if backend == "aliyun":
    page_text, blocks, warning = ocr_page_aliyun(image_path, page)
    if page_text or blocks:
        return page_text, blocks, warning, "aliyun_vision"

page_text, blocks, warning = ocr_page_tesseract(image_path)
return page_text, blocks, warning, "tesseract"
```

云 OCR 提供更好的中文和表格识别，Tesseract 提供本地降级能力。

### 3. 页级 chunk

```java
pageContent.append("[page=").append(page)
    .append("][type=page][hasTable=").append(hasTable).append("]\n");
pageContent.append(pageText);
if (!tableTexts.isEmpty()) {
    pageContent.append("\n\n[table_blocks]\n")
        .append(String.join("\n\n", tableTexts));
}
```

页级 chunk 避免过细切分导致同页事实被拆散。

### 4. Hybrid retrieval

```java
List<Document> docs = hybridRetrieve(
    question,
    vectorStore.similaritySearch(question, Math.max(topK, 5)),
    topK
);
```

`hybridRetrieve` 在向量召回基础上补充关键词重排，稳定处理标准号、日期、表名、AQL 等精确 token。

### 5. 本地自检

```java
boolean hasEvidence = hasUsableEvidence(evidence);
boolean hasCitation = answer.contains("第") && (answer.contains("页") || answer.contains("chunk"));
String risk = hasEvidence && hasCitation ? "LOW" : hasEvidence ? "MEDIUM" : "HIGH";
```

自检目前是轻量规则，主要判断是否有 evidence、是否包含来源引用、是否建议拒答。

---

## 局部难点与处理

### OCR 中文模型问题

调试时发现本地 `chi_sim.traineddata` 文件不完整，Tesseract 退化为英文识别，导致中文大面积乱码。处理方式：

- 修复本地中文模型
- 脚本记录 OCR warnings
- 默认引入阿里云视觉 OCR
- Tesseract 只作为 fallback

### 表格识别问题

Tesseract 对表格结构恢复较差，无法稳定输出列关系。云 OCR 能将表格恢复为 markdown，artifact 中保留 `type=table` block，页级 Document 中追加 `[table_blocks]`。

### block 级切分导致召回不完整

封面上的标准号、发布日期、实施日期原本被拆成多个 block，检索时容易漏召回。改为页级 chunk 后，单次召回第 1 页即可覆盖完整封面信息。

### 多轮 QA Agent 慢且不稳定

QA Agent 使用 function calling 时，一个问题可能触发多次 LLM 调用和多次检索。文档问答场景更适合固定 RAG Flow，因此最终改为一次检索、一次回答、本地自检。

### 无答案问题

Prompt 明确要求 evidence 不足时回答“文档中未找到相关信息”。自检结果会附带 `grounded`、`risk`、`refuseSuggested`，用于提示答案是否有依据。

---

## 运行与验证

默认使用：

- 阿里云 Chat / Embedding
- 阿里云视觉 OCR
- 内存向量库

运行：

```bash
export ALIYUN_KEY=你的阿里云百炼Key

mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo
```

可选：指定 PDF 路径：

```bash
mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
  -Ddocqa.pdf="/tmp/GBT_1568-2008_键_技术条件.pdf"
```

可选：使用 Milvus：

```bash
mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
  -Ddocqa.vector=milvus \
  -Drag.vector.milvus.use=true
```

可选：使用 Ollama embedding：

```bash
mvn test -Dtest=Article25DocumentQaAgent#fullDocumentQaDemo \
  -Ddocqa.embedding=ollama
```

强制重新 OCR：

```bash
rm -rf target/docqa/parsed/{docId}
```

Demo 固定验证 5 类问题：

1. 标准号、发布日期、实施日期
2. 适用范围
3. 表格问题
4. OCR 容错问题
5. 无答案问题

---

## 当前限制

- 云 OCR 依赖网络和模型稳定性。
- 页级 chunk 适合短文档；长文档需要页内条款切分。
- 表格 markdown 依赖视觉模型输出质量。
- `InMemoryVectorStore` 不持久化，不适合生产大规模检索。
- 自检目前是规则型，只能做基础 grounding 判断。

---

## 后续扩展

### 长文档

可升级为父子 chunk：

```text
page chunk 作为 parent
section/table chunk 作为 child
检索 child，回答时补 parent 上下文
```

这样既保留页级上下文，又能提高长文档召回精度。

### 多文档知识库

扩展 metadata：

- `docId`
- `fileId`
- `sourcePath`
- `documentType`
- `businessDomain`

并在检索时增加 metadata filter。

### 生产向量库

默认内存库可替换为：

- Milvus
- Elasticsearch / OpenSearch
- pgvector

当前 `VectorStore` 抽象已经支持替换。

### 更强自检

可以增加：

- evidence 覆盖率检查
- 数值一致性检查
- 表格答案行列校验
- LLM judge 二次校验
- 低置信度人工复核

### Agent 化增强

普通文档 QA 保持固定 RAG Flow。只有在需要多工具、多文档、多步骤推理时，再启用 `McpAgentExecutor` 做自主工具调用。

### 业务场景迁移

这套方案的核心模块是可迁移的：

```text
解析 Skill -> 结构化 artifact -> 元数据化入库 -> Hybrid Retrieval -> 带引用回答 -> 自检
```

迁移到不同业务时，主要替换 OCR prompt、metadata schema、检索策略和自检规则。

**金融材料**

- 文档类型：研报、年报、财报、产品说明书、风险揭示书。
- metadata 增强：报告期、公司代码、币种、科目、页码、表格名称。
- 检索策略：表格和数值优先，支持同义词和指标别名，例如“营收/营业收入”“净利润/归母净利润”。
- 自检重点：数值、单位、币种、报告期必须与 evidence 一致；涉及投资建议时增加风险提示。

**合规与标准文件**

- 文档类型：监管规定、内控制度、国家/行业标准、审计材料。
- metadata 增强：条款号、章节标题、生效日期、废止状态、适用范围。
- 检索策略：条款编号和关键词混合检索，必要时按章节父子 chunk 召回。
- 自检重点：必须引用条款号和页码；无依据时拒答；对过期制度或冲突条款给出不确定性提示。

**客户交付与知识库**

- 文档类型：产品手册、合同、实施方案、SOP、FAQ。
- metadata 增强：客户、项目、版本、模块、交付阶段、权限级别。
- 检索策略：按 `docId/customer/project/version` 做过滤，避免跨客户或跨版本误召回。
- 自检重点：输出必须带来源页码和版本；涉及客户数据时增加权限校验和脱敏处理。

生产化时建议把这些场景差异沉淀为配置：

```text
DocumentProfile
  -> OCR prompt
  -> metadata schema
  -> chunk policy
  -> retrieval policy
  -> self-check policy
```

这样底层框架保持不变，不同业务只切换 profile，即可复用同一套 Agent / RAG / 工具链。

---

## AI 使用说明

开发过程中使用 AI 辅助：

- 阅读 j-langchain 中 Skill、McpAgentExecutor、VectorStore、RAG demo 的实现
- 讨论扫描 PDF、OCR、表格、RAG、自检的架构取舍
- 生成和调整 Demo 代码
- 分析运行错误，例如 OCR 模型损坏、embedding batch 限制、Agent 超时

所有结果均通过人工检查和本地运行验证，包括：

- Python OCR artifact
- 表格 markdown
- 检索 evidence
- 问答引用
- `mvn -DskipTests test`
