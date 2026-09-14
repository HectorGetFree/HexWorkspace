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

- Outbox 事件先由发布器通过 `PENDING → PUBLISHING` 条件更新抢占发布权，并记录唯一发布令牌与租约；仅抢占成功的实例可发送 MQ。
- Outbox 事件只有在 RabbitMQ broker 确认后、且发布令牌仍匹配时才标记为 `SENT`；发布失败或租约超时会回到 `PENDING`。确认结果未知时允许再次投递，由 UploadTask 的执行抢占保证业务幂等。
- 消费者通过 `PENDING / RETRY_WAIT → RUNNING` 条件更新抢占任务，并写入唯一 `executionToken` 与 10 分钟执行租约；重复消息不会并发处理同一任务。
- 消费者完成或失败时必须携带同一个 `executionToken` 做条件更新。租约到期仍为 `RUNNING` 的任务由调度器以条件更新回收为 `RETRY_WAIT` 并重新投递；已过期消费者即使继续运行，也不能再激活文档、覆盖任务状态或删除临时对象。
- 正式对象使用任务固定的 `targetPath`。若 MinIO 写入成功但 MySQL 激活失败，重试会覆盖同一对象，并再次尝试激活，结果保持幂等。
- 消费失败或执行租约超时后，未耗尽 `max-retry-count` 的任务进入 `RETRY_WAIT`，记录原因和下次重试时间；定时任务重新创建 Outbox 事件，而不依赖消息中间件的内存重试。
- `max-retry-count` 默认值为 3，表示首次执行失败后最多再次投递 3 次。重试额度耗尽后，UploadTask 与 Document 在同一 MySQL 事务中进入 `FAILED`，任务不再被扫描投递。
- 临时对象写入成功但本地事务提交失败时，请求线程会尽力删除临时对象。进程在该窗口异常退出时，可能留下无任务引用的临时对象；这是对象存储侧残留，后续应按 `staging/` 前缀和保留期增加独立清理任务。

## 4. 状态

| 对象 | 状态 | 含义 |
| --- | --- | --- |
| Document | `CREATING` | 已登记上传任务，尚不可读 |
| Document | `ACTIVE` | 正式 MinIO 对象已写入，可读 |
| Document | `FAILED` | 首次内容写入失败且重试额度耗尽 |
| UploadTask | `PENDING` | 等待 Outbox 投递 |
| UploadTask | `RUNNING` | 消费者已抢占 |
| UploadTask | `SUCCEEDED` | 文档已激活 |
| UploadTask | `RETRY_WAIT` | 本次失败，等待下一轮投递 |
| UploadTask | `FAILED` | 重试额度耗尽，已终止，不再投递 |
| OutboxEvent | `PENDING` | 尚未成功发往 MQ |
| OutboxEvent | `PUBLISHING` | 已被一个发布器实例抢占，等待 MQ 确认 |
| OutboxEvent | `SENT` | RabbitMQ 已确认接收 |
