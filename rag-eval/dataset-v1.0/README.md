# dataset-v1.0

第一版 RAG 评测数据集骨架。当前只建立结构和字段约定，原始文档与问题集待确认来源和标注口径后补充。

## 目录

```text
dataset-v1.0/
├─ manifest/documents.jsonl
├─ corpus/raw/
├─ corpus/canonical-text/
├─ qa/train.jsonl
├─ qa/dev.jsonl
├─ qa/test.jsonl
├─ schemas/document.schema.json
├─ schemas/question.schema.json
└─ licenses/SOURCES.md
```

`canonical-text` 是人工确认的标准纯文本，不等同于系统本次解析产生的 MinIO 派生 TXT；它用于区分解析、分段、检索和生成问题。
