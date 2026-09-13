# 文档上传最终一致性设计

## 1. 目标与边界

MySQL 与 MinIO 不共享本地事务，不能承诺跨存储的强一致提交。本设计通过临时对象、上传任务、Outbox 和幂等消费，保证文档最终只会在正式对象已经可用后变为 `ACTIVE`。

本设计覆盖本地文件导入与平台新建 Markdown 的首次内容写入；RAG 不参与该主链路。

本地文件导入必须携带 `Idempotency-Key` 请求头。幂等信息直接保存在 `document`：以 `workspace_id + owner_user_id + idempotency_key` 建立唯一索引。命中既有文档时，比较目录、完整文件名、内容哈希、内容类型和来源类型；相同请求复用首次创建的文档，使用同一个键提交不同内容会被拒绝。

## 2. 正常链路

```text
请求内容
  → MinIO staging/{documentCode}/... 临时对象
  → MySQL 同一事务：Document(CREATING) + UploadTask(PENDING) + OutboxEvent(PENDING)
  → 定时 Outbox 发布器投递 taskCode 至 RabbitMQ
  → 消费者读取临时对象并写入固定正式 objectKey
  → MySQL 同一事务：UploadTask(SUCCEEDED) + Document(ACTIVE)
  → 删除临时对象
```

接口创建成功只表示任务已可靠登记，不表示文档已经可读取；`CREATING` 文档不会出现在正常文档列表，也不能读取正文。

## 3. 失败与幂等规则

- Outbox 事件只有在 MQ 调用成功返回后标记为 `SENT`；未标记的事件会被后续扫描再次投递。
- 消费者通过 `PENDING / RETRY_WAIT → RUNNING` 条件更新抢占任务，因此重复消息不会并发处理同一任务。
- 正式对象使用任务固定的 `targetPath`。若 MinIO 写入成功但 MySQL 激活失败，重试会覆盖同一对象，并再次尝试激活，结果保持幂等。
- 消费失败后任务进入 `RETRY_WAIT`，记录原因和下次重试时间；定时任务重新创建 Outbox 事件，而不依赖消息中间件的内存重试。
- 临时对象写入成功但本地事务提交失败时，请求线程会尽力删除临时对象。进程在该窗口异常退出时，可能留下无任务引用的临时对象；这是对象存储侧残留，后续应按 `staging/` 前缀和保留期增加独立清理任务。

## 4. 状态

| 对象 | 状态 | 含义 |
| --- | --- | --- |
| Document | `CREATING` | 已登记上传任务，尚不可读 |
| Document | `ACTIVE` | 正式 MinIO 对象已写入，可读 |
| UploadTask | `PENDING` | 等待 Outbox 投递 |
| UploadTask | `RUNNING` | 消费者已抢占 |
| UploadTask | `SUCCEEDED` | 文档已激活 |
| UploadTask | `RETRY_WAIT` | 本次失败，等待下一轮投递 |
| OutboxEvent | `PENDING` | 尚未成功发往 MQ |
| OutboxEvent | `SENT` | 已成功调用 MQ 发送 |
