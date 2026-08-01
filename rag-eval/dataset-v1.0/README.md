# dataset-v1.0

第一版 RAG 评测数据集。语料和 QA 标注均为自编的企业协作平台资料，不含个人信息、密钥或第三方受限内容。

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

## QA 标注与划分

`qa/` 共 240 条固定问题：`train` 40 条、`dev` 40 条、`test` 160 条。每条记录包含参考答案、黄金证据、能力类别、难度和答案关键字；无答案题显式标记为 `expectedBehavior: REFUSE`。

问题由 `../scripts/generate_qa_v1.py` 根据 canonical text 中刻意注入的事实离线生成。脚本使用固定随机种子，重跑不会改变划分；它不调用模型，因此标注可审计、可复现。

黄金证据使用 `documentKey + heading + quote`，不使用运行时的 `chunkNo`。这样分段策略变化后，评测集仍可复用。
