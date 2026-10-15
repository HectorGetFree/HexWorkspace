# HexWorkspace

HexWorkspace 是面向个人资料与任务的智能工作台。Java 后端负责 Workspace、版本化文档、异步 RAG 与业务授权，通过服务接口集成独立的 [Hexgent](../hexgent) Agent Runtime；Vue 前端提供资料浏览、Markdown 编辑和 Agent 会话交互。

项目是 Java 17 / Spring Boot 3.2 的 Maven 多模块单体，不是完整微服务平台。下文只描述当前源码能力，并保留可选开关及已知边界。

## 核心能力

### 版本化资料与异步索引

- 用户注册、登录、JWT 认证，个人 Workspace、目录树与文档管理。
- Markdown 创建和编辑，以及 TXT、Markdown、PDF、DOCX 上传；大小上限以运行配置为准。
- 文档元数据与不可变 DocumentVersion 分离；原文、解析及 Chunk 产物保存到 MinIO。
- 上传幂等键处理同一次请求重投，不等于按内容禁止用户再次上传。
- UploadTask / IndexJob 登记处理意图，Outbox 负责可靠投递，RabbitMQ 驱动异步 Worker。
- CAS + Token + Lease 支持竞争领取、续期、过期接管和旧执行者隔离；通过重试与补偿处理部分失败，不承诺跨 MySQL、MinIO、Qdrant 原子提交或端到端 exactly-once。
- 每次构建使用独立 Generation；完成解析、结构感知分段、Embedding 和向量写入，经完整性与当前输入状态校验后，事务切换 Active 指针。
- 支持过保留期的失效 Generation 回收和 staging 对象清理，避开当前有效引用。

### 混合检索与可信结果

```text
Query Embedding → Dense + BM25 → RRF
→ 首次 MySQL 权威校验 → 结构上下文 Rerank → 总 TopK
→ 返回前权威复核 → 带不可变 versionId 的结果
```

- Qdrant 按 Workspace 召回；进程内 BM25 复用 Generation Chunk 产物，后台全量刷新并原子发布快照。
- BM25 使用英文正则切词，不具备中文分词能力；冷启动、快照不匹配或调用异常时回退 Dense。刷新失败保留旧快照，候选仍需权威复核。
- RRF 融合排名；Rerank 输入为标题、章节路径和正文，只处理已通过首次权威校验的候选。
- 校验 Workspace、目录、文档版本、输入修订号、Active/READY Generation 与 Serving Embedding 配置；返回前重读状态，剔除检索期间失效的候选。
- 当前普通生产检索仅限制总 TopK；每文档限额与单机制 A/B 保留在受控评测入口。
- Rerank 为可选能力，默认配置关闭；未启用或失败时保留召回顺序。完整链路需显式启用。

### Agent 业务集成

- Conversation / AgentRun 与 Hexgent Runtime Session 分离，支持启动、控制、事件流和权限确认适配。
- Agent 通过 Capability 调用 `search_workspace`、`list_folder`、`get_document`、`create_document`、`update_document`。
- 执行范围来自权威 Run 的用户、Workspace、状态和 allowedTools，不信任模型提供的身份。
- `get_document` 按不可变 DocumentVersion 主键精确读取，支持分页；普通 HTTP API 的存在不代表 Agent 自动拥有对应工具。

## 工程结构

```text
hex-workspace-app/             启动、配置与装配
hex-workspace-api/             请求、响应及接口 DTO
hex-workspace-trigger/         HTTP、安全过滤器、MQ Listener、Job
hex-workspace-domain/          领域规则、实体、服务及仓储/外部能力端口
hex-workspace-infrastructure/  MyBatis、MinIO、MQ、模型、Qdrant 等实现
hex-workspace-types/           通用枚举和异常
hex-workspace-queries/         查询侧预留模块
frontend/                     Vue 3 / TypeScript / Vite / Element Plus
docs/devops/                  本地基础设施和数据库初始化
rag-eval/                     数据合同、执行器、评分及实验说明
```

## 本地启动

要求 JDK 17、Maven、Docker Compose；前端需要支持当前 Vite 的 Node.js。索引/检索需要匹配的 Embedding 配置；Agent 执行另需 Hexgent 与 Responses 兼容模型服务。

### 基础设施

```bash
docker compose -f docs/devops/docker-compose.yml up -d
```

默认端口：MySQL `13306`、Redis `16379`、MinIO API/Console `19000/19001`、RabbitMQ/Console `5672/15672`、Qdrant HTTP/gRPC `6333/6334`。

首次创建数据卷时执行 `docs/devops/mysql/sql/` 初始化。已有卷不会自动重放脚本；升级前备份并核对结构，不要为启动直接删除已有卷。

### 后端

配置入口：`hex-workspace-app/src/main/resources/application-dev.yml`。不要提交真实密钥；环境变量必须在启动后端的终端设置。

```bash
export DASHSCOPE_API_KEY='你的 Embedding API Key'
# 可选：启用完整重排，endpoint 按账号区域配置。
export HEX_WORKSPACE_RERANK_ENABLED=true
export DASHSCOPE_RERANK_API_KEY='你的 Rerank API Key'
# 较大语料按内存预算提高上限；默认 400 万正文字符。
# export HEX_WORKSPACE_BM25_MAX_SOURCE_CHARS=12000000

mvn -pl hex-workspace-app -am -DskipTests package
java -jar hex-workspace-app/target/hex-workspace-app.jar --spring.profiles.active=dev
```

默认地址 `http://127.0.0.1:8098`，启动类 `com.hector.HexWorkspaceApplication`。

### 前端

```bash
cd frontend
npm ci
npm run dev
```

Vite 将 `/api` 代理到本地 8098；模型密钥不得交给浏览器。

### 可选：连接 Hexgent

在 Hexgent 项目启动 Service：

```bash
python -m hexgent.adapters.web --host 127.0.0.1 --port 8000
```

Java 端使用 `HEXGENT_RUNTIME_URL`（默认 `http://127.0.0.1:8000`）。按实际部署设置两端匹配的：

| 变量 | 用途 |
| --- | --- |
| `HEXGENT_WORKSPACE_INTEGRATION_TOKEN` | Workspace → Runtime 命令 |
| `HEXGENT_WORKSPACE_CAPABILITY_TOKEN` | Runtime → Workspace 业务工具 |
| `HEXGENT_WORKSPACE_LIFECYCLE_TOKEN` | 生命周期回调 |

Runtime 还需 `HEXGENT_WORKSPACE_URL`；启用回调时配置 `HEXGENT_WORKSPACE_LIFECYCLE_URL`。内部 Token 不能替代最终用户授权，协议以两端 integration 源码为准。

## 接口入口

普通业务接口使用 JWT；Runtime、Capability、回调及评测接口使用各自独立凭证。

| 能力 | 入口 |
| --- | --- |
| 用户认证 | `/api/v1/users/*` |
| Workspace / 目录 / 文档 | `/api/v1/workspaces/*` |
| 创建会话 | `POST /api/v1/workspaces/{workspaceId}/conversations` |
| 启动 Run | `POST /api/v1/conversations/{conversationId}/runs` |
| Agent Capability | `POST /internal/agent/capabilities/invoke` |
| 受控检索评测 | `/internal/v1/evaluation/retrieval*` |

旧 `/knowledge/search`、`/knowledge/ask` 默认关闭，不是当前 Agent 可信检索入口。当前检索服务要求 Workspace ACTIVE 且调用者为 Owner；成员存在不等于已支持任意成员检索。

## 评测与验证

- SciFact：5,183 份文档、300 条 test Query，按公开相关文档标注评分。
- Java Notes：34 份资料、143 条 Query，同时统计文档命中和证据组覆盖；保留语义审核待完成的草案状态。
- 就绪检查、Generation/快照哈希、逐请求诊断、失败账本和 Resume 防重复机制支撑追溯。
- TopK Chunk 与 TopK 不同文档不能混算；本地 Token 估算不是 Provider Usage，检索命中也不等于最终回答正确。

参阅 [评测总览](rag-eval/README.md)、[SciFact 重测](rag-eval/PRODUCTION_SCIFACT.md)、[业务集重测](rag-eval/PRODUCTION_BUSINESS.md)、[业务 A/B](rag-eval/BUSINESS_AB.md)、[Top5 Chunk](rag-eval/SCIFACT_CHUNK_TOP5.md)。缓存、运行结果和部分派生数据被 Git 忽略，克隆仓库不一定包含全部复现资产。

```bash
# 部分 IntegrationTest 依赖 Docker / 数据库；真实 Provider Smoke 需单独审阅费用与开关。
mvn -pl hex-workspace-infrastructure -am test
# app 测试默认跳过，需要时显式开启：
# mvn -pl hex-workspace-app -am test -Dapp.tests.skip=false
docker compose -f docs/devops/docker-compose.yml config --quiet
cd frontend && npm run typecheck
```

## 当前边界

- 开发配置不等于生产安全配置；部署前检查自动批准、内部 Token、网络暴露、密钥与备份。
- BM25 全量刷新且仅保存在进程内，不保证发布后立即完成 Sparse 更新。
- 返回前校验不是跨资源快照事务；剔除失效候选后不承诺补满 TopK。
- Runtime 工具、Memory 和恢复语义由 Hexgent 实现，Java AgentRun 不是 Runtime Transcript 的替代品。
- README 不把历史测试通过数、单次真实实验或离线契约测试描述为线上可靠性承诺。

## License

项目 POM 声明 Apache License 2.0；当前根目录未提供独立 LICENSE 文件，发布前应补齐许可证正文及第三方声明。
