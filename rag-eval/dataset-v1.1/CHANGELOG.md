# dataset-v1.1 变更记录

## 与 dataset-v1.0 的关系

- 原始语料、canonical text、schema 语义和 dev 划分均与 V1.0 一致；两版 manifest 的
  `rawSha256` 均已同步为当前 raw 文件的真实 SHA-256。
- V1.0 保持冻结，不回写、不替换其历史运行结果。
- V1.1 仅修正已经确认的黄金标注歧义。

## 2026-10-03：重复 query 标注修订

V1.0 的 train/test split 含文本完全相同的 query：

```text
平台自动产生的数据和用户直接提交的数据，分别使用什么标记？
```

它们分别以 `membership-01-v1`、`membership-02-v1`、`membership-03-v1`、`membership-04-v1`、`membership-05-v1` 作为不同的单一黄金文档。该 query 本身不包含能区分这些同义证据的约束，因此把任意一份之外的命中判为错误会引入不可归因的检索惩罚。

V1.1 保留 `q-membership-03-v1-derived-rephrase`（test）与 `q-membership-04-v1-derived-rephrase`（train），删除以下重复项：

- `q-membership-01-v1-derived-rephrase`
- `q-membership-02-v1-derived-rephrase`
- `q-membership-05-v1-derived-rephrase`

因此 train split 从 40 题变为 39 题、test split 从 160 题变为 158 题。整体、`paraphrase` 分类及其聚合指标均不能与 V1.0 数值直接横比。后续检索优化应以 V1.1 首次冻结运行作为新基线。

## 2026-10-03：manifest 完整性修订

发现 V1.0 的 20 条 `rawSha256` 与仓库内对应 `corpus/raw/` 文件不一致。V1.1 保持与
V1.0 相同的 raw 语料，因此同步修正相同的 20 条 manifest 哈希。该修订不改变任何 raw
文件、canonical text 或 QA 标注，只恢复 manifest 对实际冻结语料的校验能力。
