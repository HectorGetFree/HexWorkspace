# dataset-v1.1

基于 `dataset-v1.0` 的兼容修订版。语料和绝大多数 QA 标注与 V1.0 完全一致；具体变更见 `CHANGELOG.md`。

## 目录

```text
dataset-v1.1/
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

`qa/` 共 237 条固定问题：`train` 39 条、`dev` 40 条、`test` 158 条。每条记录包含参考答案、黄金证据、能力类别、难度和答案关键字；无答案题显式标记为 `expectedBehavior: REFUSE`。

问题由 `../scripts/generate_qa_v1.py` 根据 canonical text 中刻意注入的事实离线生成。V1.1 是冻结修订版，不应通过重跑该生成脚本覆盖；它不调用模型，因此标注可审计、可复现。

黄金证据使用 `documentKey + heading + quote`，不使用运行时的 `chunkNo`。这样分段策略变化后，评测集仍可复用。
