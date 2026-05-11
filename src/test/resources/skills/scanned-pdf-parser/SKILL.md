---
name: scanned_pdf_parser
description: 扫描版 PDF 解析技能。用于识别 PDF 类型、执行 OCR、抽取正文/条款/表格块，并将完整解析结果保存为本地 artifact；只返回 artifact 摘要，不返回全文。
max-iterations: 3
---

# 扫描 PDF 解析工作流

你负责把用户提供的 PDF 文件解析成结构化文档 artifact。

## 输入

用户会提供一个 PDF 文件路径。调用 `parse_pdf` 脚本时，把该路径作为 `args` 参数传入。

## 规则

1. 必须调用 `parse_pdf`。
2. 不要把 PDF 全文复制到最终回答。
3. 最终只返回 `parse_pdf` 的 JSON 摘要结果。
4. 如果脚本返回错误，原样返回错误 JSON，便于上层流程处理。

