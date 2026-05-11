#!/usr/bin/env python3
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False))


def parse_input(raw):
    raw = (raw or "").strip()
    if not raw:
        return ""
    if raw.startswith("{"):
        try:
            data = json.loads(raw)
            return str(data.get("args") or data.get("pdf_path") or data.get("path") or "")
        except Exception:
            return raw
    return raw


def run(cmd):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)


def command_exists(name):
    return run(["which", name]).returncode == 0


def artifact_summary(artifact_path):
    # Keep expensive OCR out of the Java demo loop; delete the artifact directory to force regeneration.
    artifact = json.loads(Path(artifact_path).read_text(encoding="utf-8"))
    text_blocks = 0
    table_blocks = 0
    for page in artifact.get("pages") or []:
        for block in page.get("blocks") or []:
            if block.get("type") == "table":
                table_blocks += 1
            else:
                text_blocks += 1
    return {
        "ok": True,
        "docId": artifact.get("doc_id"),
        "pdfType": artifact.get("pdf_type", "SCANNED_PDF"),
        "pages": len(artifact.get("pages") or []),
        "artifactPath": str(artifact_path),
        "textBlocks": text_blocks,
        "tableBlocks": table_blocks,
        "warnings": (artifact.get("warnings") or [])[:5],
        "cached": True
    }


def pdf_pages(pdf_path):
    if command_exists("pdfinfo"):
        proc = run(["pdfinfo", pdf_path])
        match = re.search(r"^Pages:\s+(\d+)", proc.stdout, re.MULTILINE)
        if match:
            return int(match.group(1))
    return 0


def text_layer(pdf_path, page):
    if not command_exists("pdftotext"):
        return ""
    proc = run(["pdftotext", "-f", str(page), "-l", str(page), pdf_path, "-"])
    if proc.returncode != 0:
        return ""
    return proc.stdout.strip()


def render_page(pdf_path, page, work_dir):
    if not command_exists("pdftoppm"):
        return None, "pdftoppm not found"

    prefix = str(work_dir / f"page_{page}")
    render = run(["pdftoppm", "-r", "220", "-f", str(page), "-l", str(page), "-png", pdf_path, prefix])
    if render.returncode != 0:
        return None, render.stderr.strip()

    image_path = work_dir / f"page_{page}-{page}.png"
    if not image_path.exists():
        image_path = work_dir / "page_1-1.png"
    if not image_path.exists():
        candidates = sorted(work_dir.glob(f"page_{page}-*.png"))
        if candidates:
            image_path = candidates[0]
    if not image_path.exists():
        return None, "rendered page image not found"
    return image_path, ""


def ocr_page_tesseract(image_path):
    if not command_exists("tesseract"):
        return "", [], "tesseract not found"
    tesseract_cmd = ["tesseract", str(image_path), "stdout", "-l", "chi_sim+eng", "--psm", "6"]
    proc = run(tesseract_cmd)
    if proc.returncode != 0:
        fallback = run(["tesseract", str(image_path), "stdout", "-l", "eng", "--psm", "6"])
        if fallback.returncode == 0 and fallback.stdout.strip():
            return fallback.stdout.strip(), [], proc.stderr.strip()
        return proc.stdout.strip(), [], proc.stderr.strip()
    return proc.stdout.strip(), [], proc.stderr.strip()


def extract_json(text):
    text = (text or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < start:
        raise ValueError("model output does not contain JSON object")
    return json.loads(text[start:end + 1])


def normalize_model_blocks(model_json, page):
    blocks = []
    for idx, block in enumerate(model_json.get("blocks") or [], start=1):
        btype = str(block.get("type") or "text").lower()
        if btype not in ("text", "table"):
            btype = "text"
        table_md = str(block.get("table_markdown") or "").strip()
        text = str(block.get("text") or "").strip()
        if btype == "table" and table_md:
            text = table_md if not text else text + "\n" + table_md
        if not text:
            continue
        blocks.append({
            "block_id": f"p{page}_b{idx}",
            "page": page,
            "type": btype,
            "section_no": str(block.get("section_no") or ""),
            "section_title": str(block.get("section_title") or ""),
            "text": text,
            "confidence": float(block.get("confidence") or 0.8)
        })
    raw_text = str(model_json.get("raw_text") or "").strip()
    if raw_text and (not blocks or len(raw_text) > sum(len(b["text"]) for b in blocks) * 3):
        blocks = split_blocks(raw_text, page)
    return raw_text or "\n".join(b["text"] for b in blocks), blocks


def ocr_page_aliyun(image_path, page):
    api_key = os.environ.get("ALIYUN_KEY") or os.environ.get("DASHSCOPE_API_KEY")
    if not api_key:
        return "", [], "ALIYUN_KEY or DASHSCOPE_API_KEY is not set"

    model = os.environ.get("DOCQA_OCR_MODEL", "qwen3.6-plus")
    url = os.environ.get("DOCQA_OCR_URL", "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
    image_b64 = base64.b64encode(Path(image_path).read_bytes()).decode("ascii")
    prompt = """
你是标准文件 OCR 引擎。请识别这页扫描版中文国家标准图片中的正文、条款编号和表格。
只输出 JSON，不要解释，不要使用 Markdown 代码块。

JSON schema:
{
  "page_title": "",
  "raw_text": "",
  "blocks": [
    {
      "type": "text|table",
      "section_no": "",
      "section_title": "",
      "text": "",
      "table_markdown": "",
      "confidence": 0.0
    }
  ]
}

要求：
1. 保留中文、英文、标准号、日期、单位和条款编号，例如 3.1、4.2。
2. 表格必须作为 type=table 单独输出，并尽量恢复为 markdown 表格。
3. 不要翻译原文。
4. 看不清的字用 [unclear] 标注，不要编造。
""".strip()
    payload = {
        "model": model,
        "input": {
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"image": f"data:image/png;base64,{image_b64}"},
                        {"text": prompt}
                    ]
                }
            ]
        },
        "parameters": {
            "vl_high_resolution_images": True
        }
    }
    body = ""
    last_error = ""
    request_data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    for attempt in range(1, 4):
        request = urllib.request.Request(
            url,
            data=request_data,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json"
            },
            method="POST"
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                body = response.read().decode("utf-8")
            break
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="ignore")
            last_error = f"aliyun OCR HTTP {e.code}: {detail[:500]}"
            if 400 <= e.code < 500:
                return "", [], last_error
        except Exception as e:
            last_error = f"aliyun OCR error: {e}"
    if not body:
        return "", [], last_error

    try:
        data = json.loads(body)
        content = data["output"]["choices"][0]["message"]["content"]
        if isinstance(content, list):
            content = "".join(item.get("text", "") if isinstance(item, dict) else str(item) for item in content)
        model_json = extract_json(str(content))
        page_text, blocks = normalize_model_blocks(model_json, page)
        return page_text, blocks, ""
    except Exception as e:
        return "", [], f"aliyun OCR parse error: {e}; response={body[:500]}"


def ocr_page(pdf_path, page, work_dir):
    image_path, render_warning = render_page(pdf_path, page, work_dir)
    if image_path is None:
        return "", [], render_warning, "ocr_render_failed"

    backend = os.environ.get("DOCQA_OCR_BACKEND", "aliyun").lower()
    warnings = []
    if backend == "aliyun":
        # Cloud OCR is better for scanned Chinese tables; Tesseract remains the local fallback.
        page_text, blocks, warning = ocr_page_aliyun(image_path, page)
        if page_text or blocks:
            if warning:
                warnings.append(warning)
            return page_text, blocks, "; ".join(warnings), "aliyun_vision"
        warnings.append(warning)

    page_text, blocks, warning = ocr_page_tesseract(image_path)
    if warning:
        warnings.append(warning)
    return page_text, blocks, "; ".join(warnings), "tesseract"


def block_type(lines):
    joined = "\n".join(lines)
    numeric = len(re.findall(r"\d", joined))
    multi_space_rows = sum(1 for line in lines if re.search(r"\S\s{2,}\S", line))
    avg_len = sum(len(line) for line in lines) / max(len(lines), 1)
    bracket_or_pipe = len(re.findall(r"[\[\]\|]", joined))
    table_words = any(word in joined for word in ["表", "尺寸", "宽度", "高度", "公差", "键", "键槽"])
    if len(lines) >= 2 and (multi_space_rows >= 1 or (numeric >= 8 and table_words)):
        return "table"
    if len(lines) >= 5 and avg_len <= 16 and (numeric >= 1 or bracket_or_pipe >= 2):
        return "table"
    return "text"


def section_info(text):
    first = text.strip().splitlines()[0] if text.strip().splitlines() else ""
    match = re.match(r"^\s*(\d+(?:\.\d+)*)\s+(.{1,40})", first)
    if not match:
        return "", ""
    return match.group(1), match.group(2).strip()


def split_blocks(page_text, page):
    lines = [line.strip() for line in page_text.splitlines() if line.strip()]
    groups = []
    current = []
    for line in lines:
        if re.match(r"^\d+(?:\.\d+)*\s+\S+", line) and current:
            groups.append(current)
            current = [line]
        else:
            current.append(line)
            if len(current) >= 8:
                groups.append(current)
                current = []
    if current:
        groups.append(current)

    blocks = []
    for idx, group in enumerate(groups, start=1):
        text = "\n".join(group).strip()
        sec_no, sec_title = section_info(text)
        blocks.append({
            "block_id": f"p{page}_b{idx}",
            "page": page,
            "type": block_type(group),
            "section_no": sec_no,
            "section_title": sec_title,
            "text": text,
            "confidence": 0.65 if block_type(group) == "table" else 0.75
        })
    return blocks


def main():
    pdf_path = parse_input(sys.argv[1] if len(sys.argv) > 1 else "")
    if not pdf_path:
        emit({"ok": False, "error": "missing pdf path"})
        return

    pdf = Path(pdf_path).expanduser()
    if not pdf.exists():
        emit({"ok": False, "error": f"pdf not found: {pdf}"})
        return

    digest = hashlib.sha1(str(pdf.resolve()).encode("utf-8")).hexdigest()[:10]
    doc_id = f"{pdf.stem}_{digest}".replace(" ", "_")
    out_dir = Path("target/docqa/parsed") / doc_id
    artifact_path = out_dir / "parsed.json"
    if artifact_path.exists():
        emit(artifact_summary(artifact_path))
        return

    out_dir.mkdir(parents=True, exist_ok=True)

    pages_count = pdf_pages(str(pdf)) or 1
    pages = []
    text_layer_chars = 0
    warnings = []
    text_blocks = 0
    table_blocks = 0

    for page in range(1, pages_count + 1):
        direct_text = text_layer(str(pdf), page)
        text_layer_chars += len(direct_text)
        if direct_text and len(direct_text) > 80:
            page_text = direct_text
            blocks = split_blocks(page_text, page)
            strategy = "text_layer"
            warning = ""
        else:
            page_text, blocks, warning, strategy = ocr_page(str(pdf), page, out_dir)
            if warning:
                warnings.append(f"page {page}: {warning[:160]}")

        if not blocks:
            blocks = split_blocks(page_text, page)
        text_blocks += sum(1 for b in blocks if b["type"] == "text")
        table_blocks += sum(1 for b in blocks if b["type"] == "table")
        pages.append({
            "page": page,
            "strategy": strategy,
            "char_count": len(page_text),
            "ocr_text": page_text,
            "blocks": blocks
        })

    pdf_type = "TEXT_PDF" if text_layer_chars > 200 else "SCANNED_PDF"
    artifact = {
        "ok": True,
        "doc_id": doc_id,
        "source_path": str(pdf.resolve()),
        "pdf_type": pdf_type,
        "pages": pages,
        "warnings": warnings
    }
    artifact_path.write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")

    emit({
        "ok": True,
        "docId": doc_id,
        "pdfType": pdf_type,
        "pages": pages_count,
        "artifactPath": str(artifact_path),
        "textBlocks": text_blocks,
        "tableBlocks": table_blocks,
        "warnings": warnings[:5]
    })


if __name__ == "__main__":
    main()
