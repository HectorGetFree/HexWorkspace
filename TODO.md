# Command

## StartRun

```
User
 │
 │ instruction
 ▼
hex-workspace
 │
 │ transaction
 ├── validate user/workspace
 ├── validate conversation
 ├── ensure no non-terminal AgentRun
 └── create R001(PENDING)
 │
 │
 │ StartRun
 │ commandId = CMD001
 │ runId = R001
 │ conversationId = C001
 │ RunContext
 ▼
hexgent Runtime Adapter
 │
 ├── validate command
 ├── deduplicate commandId
 ├── deduplicate runId
 ├── ensure conversation/session binding
 ├── ensure session not busy
 ├── persist accepted runtime state
 │
 └── ACK ACCEPTED
 │
 ▼
hydrate AgentEngine
 │
 ▼
RunRequest.submit(instruction)
 │
 ▼
session.start_run()
 │
 ├── Workspace lifecycle:
 │      AgentRunStarted(R001)
 │
 ▼
agent_loop()
 │
 ├── AgentEvent*
 │
 └── ...
```

StartRun V1，我建议先收口成这 8 条
1. Workspace先创建 AgentRun(PENDING)，再发送 StartRun。
2. 不暴露独立 EnsureSession；Session由 Hexgent在首次 StartRun 中 lazy create。
3. 跨服务使用 conversationId + runId，Hexgent Session ID 保持 Runtime内部。
4. StartRun携带 instruction 和最小 RunContext；不暴露原生 RunOptions。
5. commandId 保证 Command重试幂等，runId 保证 Logical Run只能 submit 一次。
6. Ack只代表 Hexgent持久化接受 Runtime responsibility，不代表 Run 已经开始。
7. PENDING → RUNNING 由 AgentRunStarted lifecycle event 驱动，而不是 Ack。
8. 同一 Conversation 的单非终态 Run 约束由 Workspace 和 Hexgent双侧保护。

## ContinueRun

可以。结合你补充的 Hexgent 真实实现，我认为 `ContinueRun` 现在可以比较明确地收口。

### 核心定义

`ContinueRun` 表示：

> **Workspace 要求 Hexgent 基于已经保存的可续跑状态，继续执行一个处于 `INCOMPLETE` 的同一 AgentRun。**

它不是新任务、不是 retry，也不是对当前正在运行的 `agent_loop()` 发控制信号。

这里尤其要区分两个层次：

- Workspace：仍然是同一个 `AgentRun`
- Hexgent：会调用 `AgentEngine.run(RunRequest.continue_run())`，从 `LoopStateSnapshot` 恢复，并产生一个新的 Session Run / execution segment

所以，一个 AgentRun 可以经历多次 `continue`，但 `runId` 始终不变。

### 合法状态

在 Workspace Control Plane 中，我建议严格限定：

**只有 `INCOMPLETE` 可以执行 `ContinueRun`。**

虽然 Hexgent 底层的 `RunRequest.continue` 同样可以恢复 `interrupted`，但那是 Runtime primitive。Workspace 应该根据业务原因拆开：

- `INCOMPLETE` → `ContinueRun`
- `PAUSED` → `ResumeRun`
- `INTERRUPTED` → `RecoverRun`

三者最后都可以映射到 Hexgent 的 `RunRequest.continue`。

这正是 Workspace Business Command 和 Hexgent Runtime API 不应一一对应的地方。

### Command 应携带什么

V1 我认为保持很小即可：

```
commandId + runId + conversationId + refreshed RunContext
```

**不携带新的 instruction。**

因为 instruction 属于最初的 `StartRun`。如果用户给了新的任务要求，那已经不是 Continue，而应该考虑新的 AgentRun，或者未来单独设计 Steering。

其中 `RunContext` 的意义也不是重新提供任务上下文，而是**重新绑定当前 Workspace 权威状态**。

因此 Continue 时遵循我们之前定下来的原则：

> Runtime Semantic State 从历史 Snapshot 恢复；Business Authority Context 以当前 Workspace 状态为准。

同时，新的权限范围不能借 Continue 自动突破原 AgentRun 的 `resource_scope / capability_scope` 上限。

### Hexgent 接到 ContinueRun 后做什么

核心只有四步：

1. 验证 `runId` 属于对应 Conversation，并且当前没有活跃执行。
2. 验证最新 Runtime 状态确实是 `incomplete` 且 Checkpoint 为 `continuable`。
3. 恢复 `LoopStateSnapshot`，同时绑定 Workspace 传来的最新 RunContext。
4. 调用 `AgentEngine.run(continue_request)`，开始新的 execution segment。

如果 Checkpoint 丢失、损坏或已经因后续状态事实而不可恢复，则应该拒绝 Command，而不是假装 Continue 成功。

### 状态什么时候变成 RUNNING

仍然坚持之前的 Command/Event 边界：

`ContinueRun → ACK ACCEPTED` **不代表已经继续运行。**

Ack 只表示 Hexgent 接受了这次 continuation responsibility。

等新的 execution segment 真正启动后，再通过 Workspace-facing lifecycle event（例如 `AgentRunContinued`）让：

**`INCOMPLETE → RUNNING`**

之后 Hexgent 最终又会产生 `RunFinishedEvent`。如果还是 `incomplete`，AgentRun 再次进入 `INCOMPLETE`；如果 `completed`，才进入 `SUCCEEDED`。

### 幂等边界

这里和 `StartRun` 有一个重要区别：

**同一个 AgentRun 只能 Start 一次，但可以 Continue 多次。**

因此 `ContinueRun` 的幂等键是 `commandId`，而不是 `runId`。

例如同一个 R001 可以合法经历：

```
Start CMD001 → Continue CMD002 → Continue CMD003
```

但因为网络超时重发 `CMD002` 时，Hexgent不能再次执行一次 Continue。

------

所以我会把 `ContinueRun` 最终收口成一句话：

> **ContinueRun 是 Workspace 对 `INCOMPLETE` AgentRun 发出的显式续跑命令：不改变任务身份、不引入新用户意图，从 Hexgent 的 continuable Snapshot 恢复 Runtime Semantic State，同时重新绑定最新且不超过原授权上限的 Workspace Business Context，并启动新的 execution segment。**

我认为这个定义现在已经和你 Hexgent 的实际 `incomplete → snapshot → RunRequest.continue → new Session Run` 机制对齐，可以正式收口。

## PauseRun / ResumeRun

`PauseRun`：只作用于 `RUNNING`，请求 Hexgent 在安全边界停止当前 execution segment，并保留 continuable state；真正收到 `interrupted/user_pause` 后才进入 `PAUSED`。

`ResumeRun`：只作用于 `PAUSED`，恢复同一个 AgentRun 的 Snapshot，重新绑定最新 Workspace 权威上下文，并启动新的 execution segment；真正启动后才回到 `RUNNING`。

这里我认为还有**一个值得继续确认的边界**：`PauseRun` 发出后、但尚未真正 Pause Applied 的这段窗口里，如果用户又发 `CancelRun` 或 `SteerRun`，控制命令之间应该如何竞争和确定优先级。这个问题会直接影响 `RunControl` 的并发控制语义，适合在收口 Pause/Resume 前解决。