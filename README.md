# hex-workspace

hex-workspace 是一个面向个人与团队文档资料库的智能工作台后端。当前版本在原有文档知识库 MVP 基础上，已建立 Workspace 聚合：新用户注册后会自动创建个人 Workspace。文档、文件、成员和知识检索仍暂以既有 `Workspace` 运行契约承载，后续将分阶段迁移到 Workspace。

项目后续将在现有 MVP 上继续完成 DocAI 的 DDD 重构与缺陷补足，并逐步演进为包含 AI Gateway、AI Orchestrator、Tool Runtime、RAG Runtime 和可观测体系的 AI 应用平台。

> 当前版本标签：`mvp-v1`。README 中“已实现”仅描述当前代码中真实存在的能力，规划能力不会计入当前完成度。

## 当前实现进度

### 已实现

- 用户注册、登录、JWT 鉴权、当前用户查询与登出接口。
- Workspace 聚合及 `PERSONAL`、`TEAM` 类型；新用户注册时在同一事务内创建默认个人 Workspace。
- 已支持注册默认 Workspace、创建自己的资料 Workspace，以及查询当前用户拥有的 Workspace；协作权限将在 Folder/Document 层实现。
- Workspace创建、查询，以及用户所属Workspace列表。
- Workspace成员初始化、添加、查询和移除，并区分 `ADMIN`、`MEMBER` 等角色与成员状态。
- 基于 MinIO 的Workspace级文件存储，区分用户原始文件与系统派生文件。
- 文档及不可变版本建模，支持文档创建、列表、详情和解析状态查询。
- 支持 TXT、Markdown、PDF、DOCX 文件上传，单文件上限 50 MB。
- 通过 RabbitMQ 异步完成文档解析，解析文本作为 MinIO 派生文件保存。
- 章节优先、固定窗口兜底的文档分段策略，默认窗口 1000 字符、重叠 100 字符。
- 通过 DashScope `text-embedding-v4` 批量生成 1024 维向量。
- 使用 Qdrant 保存Workspace隔离的文档向量，支持 Top-K 语义检索。
- 基于 OpenAI Chat Completions 兼容接口的单轮知识库问答，并返回实际检索来源。
- AI 模型运行信息及连通性检测接口。
- RAG `dataset-v1.0`、Baseline V1 执行器，以及 Hit Rate@6、Recall@6、MRR@6、回答正确率、拒答率和延迟指标。

### 当前限制

- 当前是单体、多 Maven 模块的后端 MVP，不是完整的微服务平台。
- RAG 链路尚未实现独立 Reranker、Query Rewrite、Answer Judge 和对话 Memory。
- 问答目前为单轮调用，没有 Planner、Workflow 或复杂 Agent 编排。
- 尚未实现 API Gateway、AI Gateway、Tool Runtime，以及 CRM、DB、MQ 等工具连接器。
- 尚未建立完整的 Trace、Cost、Metrics、Audit 体系。
- `hex-workspace-queries` 当前是预留模块，尚无查询侧实现代码。
- Java 自动化测试尚未补齐；当前 Maven 配置默认跳过测试。
- `hex-workspace-app` 的 Spring Boot Maven 插件仍残留旧模板入口 `cn.bugstack.Application`；源码真实入口为 `com.hector.HexWorkspaceApplication`，当前应从 IDE 运行真实入口，打包 JAR 的启动配置需要后续修复。
- 当前配置面向本地开发，生产环境的密钥管理、数据库迁移、容灾与安全加固仍需完善。

## MVP 链路

```text
客户端
  │
  ├─ 注册 / 登录 ────────────────> JWT
  │
  └─ 上传文档
       │
       ├─ MySQL：文档与版本元数据
       ├─ MinIO：原始文件
       └─ RabbitMQ：解析事件
              │
              ▼
         文档解析消费者
              │
              ├─ MinIO：解析后的 TXT 派生文件
              ├─ Document Segmenter：章节识别与固定窗口分段
              ├─ DashScope：Embedding
              └─ Qdrant：Workspace级向量索引
                         │
                         ▼
                   Search / RAG Ask
                         │
                         └─ OpenAI-compatible Chat Model
```

## 工程结构

```text
hex-workspace
├── hex-workspace-app             # Spring Boot 启动模块与运行配置
├── hex-workspace-api             # 对外 DTO、请求与统一响应模型
├── hex-workspace-trigger         # HTTP Controller、安全过滤器、MQ Listener
├── hex-workspace-domain          # 领域模型、领域服务、仓储与外部能力端口
├── hex-workspace-infrastructure  # MyBatis、MinIO、RabbitMQ、模型与向量库适配器
├── hex-workspace-types           # 通用枚举与业务异常
├── hex-workspace-queries         # 查询侧预留模块
├── docs/devops             # 本地依赖与 MySQL 初始化脚本
└── rag-eval                # RAG 数据集、配置、执行器与运行结果目录
```

当前领域主要包括：

- `user`：平台用户。
- `auth`：密码校验、JWT 签发与认证会话。
- `Workspace`：Workspace及其生命周期。
- `membership`：用户与Workspace之间的资源协作关系。
- `file`：Workspace级底层文件资源与对象存储。
- `document`：文档聚合、不可变版本和异步解析状态。
- `knowledge`：分段、向量化、检索和知识库问答。
- `workspace`：Workspace 类型、生命周期和默认个人 Workspace 创建；资源树和成员迁移尚未完成。

## 技术栈

- Java 17
- Spring Boot 3.2
- Spring Security + JWT
- Maven 多模块工程
- MyBatis + MySQL 8
- Redis / Redisson
- RabbitMQ
- MinIO
- Qdrant
- Apache PDFBox / Apache POI
- DashScope Embedding API
- OpenAI Chat Completions 兼容 API
- Python 3（仅用于 RAG 评测脚本）

## 本地启动

### 1. 环境要求

- JDK 17
- Maven 3.8+
- Docker 与 Docker Compose
- Python 3.10+（仅运行 RAG 评测时需要）
- 可用的 DashScope API Key
- 可用的 OpenAI API Key，或兼容 Chat Completions 的服务地址与密钥

### 2. 启动基础设施

```bash
docker compose -f docs/devops/docker-compose.yml up -d
```

默认端口：

| 服务 | 地址或端口 |
| --- | --- |
| MySQL | `127.0.0.1:13306` |
| Redis | `127.0.0.1:16379` |
| MinIO API | `http://127.0.0.1:19000` |
| MinIO Console | `http://127.0.0.1:19001` |
| RabbitMQ | `127.0.0.1:5672` |
| RabbitMQ Console | `http://127.0.0.1:15672` |
| Qdrant HTTP | `http://127.0.0.1:6333` |

首次创建 Docker 数据卷时，MySQL 会自动执行 `docs/devops/mysql/sql/001-init-hex-workspace.sql`。已有旧数据卷需要依次手动执行 `002-add-file-purpose.sql`、`003-add-workspace.sql` 与 `004-remove-workspace-team-type.sql`；后两者会补齐默认 Workspace，并将旧 TEAM 数据收敛为 Owner 的普通资料容器。

### 3. 配置模型密钥

不要把真实密钥写入或提交到配置文件。启动前设置环境变量：

```bash
export DASHSCOPE_API_KEY='你的 DashScope API Key'
export OPENAI_API_KEY='你的 OpenAI 或兼容服务 API Key'
export OPENAI_BASE_URL='https://api.openai.com/v1'
```

默认模型配置位于 `hex-workspace-app/src/main/resources/application-dev.yml`：

- Embedding：`text-embedding-v4`，1024 维。
- Chat：`gpt-5.4`。

如兼容服务不支持默认 Chat 模型，请在本地配置中调整 `ai.chat.openai.model`。

### 4. 构建并启动

```bash
mvn clean package -DskipTests
```

当前请在 IntelliJ IDEA 中运行：

```text
hex-workspace-app/src/main/java/com/hector/HexWorkspaceApplication.java
```

现有 `hex-workspace-app/pom.xml` 的 Spring Boot Maven 插件仍指向旧模板入口，因此暂不建议直接执行打包 JAR。修正插件的 `mainClass` 和版本后，再恢复标准的 `java -jar` 或 `spring-boot:run` 启动方式。

应用默认监听：

```text
http://127.0.0.1:8098
```

## 核心 API

除注册、登录和 `/error` 外，其余接口均需要：

```http
Authorization: Bearer <JWT>
```

| 能力 | 方法 | 路径 |
| --- | --- | --- |
| 用户注册 | POST | `/api/v1/users/register` |
| 用户登录 | POST | `/api/v1/users/login` |
| 用户登出 | POST | `/api/v1/users/logout` |
| 当前用户 | GET | `/api/v1/users/me` |
| 创建Workspace | POST | `/api/v1/Workspaces` |
| 查询Workspace | GET | `/api/v1/workspaces/{workspaceId}` |
| 查询我的Workspace | GET | `/api/v1/workspaces/my` |
| 创建 Workspace | POST | `/api/v1/workspaces` |
| 查询我的 Workspace | GET | `/api/v1/workspaces/my` |
| 初始化Workspace Owner | POST | `/api/v1/workspaces/{workspaceId}/members/owner` |
| 添加成员 | POST | `/api/v1/workspaces/{workspaceId}/members` |
| 查询Workspace成员 | GET | `/api/v1/workspaces/{workspaceId}/members` |
| 查询我的资源协作关系 | GET | `/api/v1/my` |
| 移除成员 | DELETE | `/api/v1/workspaces/{workspaceId}/members/{targetUserId}` |
| 上传并创建文档 | POST | `/api/v1/workspaces/{workspaceId}/documents` |
| 文档列表 | GET | `/api/v1/workspaces/{workspaceId}/documents` |
| 文档详情 | GET | `/api/v1/workspaces/{workspaceId}/documents/{documentCode}` |
| 文档处理状态 | GET | `/api/v1/workspaces/{workspaceId}/documents/{documentCode}/processing-status` |
| 知识检索 | GET | `/api/v1/workspaces/{workspaceId}/knowledge/search?question=...` |
| 知识问答 | POST | `/api/v1/workspaces/{workspaceId}/knowledge/ask` |
| AI 运行信息 | GET | `/api/v1/ai/runtime-info` |
| AI 连通性检测 | GET | `/api/v1/ai/ping` |

统一响应格式：

```json
{
  "code": "0000",
  "info": "成功",
  "data": {}
}
```

### 最小调用示例

注册：

```bash
curl -X POST 'http://127.0.0.1:8098/api/v1/users/register' \
  -H 'Content-Type: application/json' \
  -d '{"data":{"username":"hector","password":"123456"}}'
```

登录：

```bash
curl -X POST 'http://127.0.0.1:8098/api/v1/users/login' \
  -H 'Content-Type: application/json' \
  -d '{"data":{"username":"hector","password":"123456"}}'
```

上传文档：

```bash
curl -X POST 'http://127.0.0.1:8098/api/v1/workspaces/1/documents' \
  -H 'Authorization: Bearer <JWT>' \
  -F 'file=@./demo.pdf' \
  -F 'title=演示文档'
```

知识库问答：

```bash
curl -X POST 'http://127.0.0.1:8098/api/v1/workspaces/1/knowledge/ask' \
  -H 'Authorization: Bearer <JWT>' \
  -H 'Content-Type: application/json' \
  -d '{"question":"这份文档的主要内容是什么？"}'
```

## RAG 评测

评测资产位于 `rag-eval/`。`dataset-v1.0` 包含 40 份自编语料与 240 条固定问题，其中 train 40 条、dev 40 条、test 160 条。

首次在专用评测Workspace中运行：

```bash
export HEX_WORKSPACE_TOKEN='<JWT>'
export HEX_WORKSPACE_WORKSPACE_ID=1
python3 rag-eval/scripts/run_baseline_v1.py --upload
```

复用已有文档映射：

```bash
python3 rag-eval/scripts/run_baseline_v1.py \
  --document-map rag-eval/runs/baseline-v1-<timestamp>/document-map.json
```

运行结果写入 `rag-eval/runs/<runId>/`，该目录已被 Git 忽略。更完整的约定见 `rag-eval/README.md`。

## 后续演进方向

计划架构：

```text
API Gateway
    │
AI Gateway
路由 / 鉴权 / 限流 / 缓存
    │
AI Orchestrator
Planner + Workflow
    │
    ├── Tool Runtime ── CRM / DB / MQ
    │
    └── RAG Runtime
          ├── Retriever
          ├── Reranker
          ├── Query Rewrite
          ├── Answer Judge
          └── Memory
    │
Monitoring
Trace / Cost / Metrics / Audit
```

建议按以下顺序演进：

1. 以当前 MVP 为基线，审计并补足 DocAI 的领域边界、事务、一致性、测试和运维缺陷。
2. 收紧模块依赖方向，完善聚合、应用服务、领域事件和查询侧边界。
3. 将 RAG 能力拆分为可替换的 Retriever、Reranker、Query Rewrite、Answer Judge 和 Memory 组件。
4. 引入 AI Orchestrator，承载 Planner 与可持久化 Workflow。
5. 建设 Tool Runtime，并通过受控端口连接 CRM、DB、MQ 等外部系统。
6. 建设 AI Gateway 和 API Gateway，统一模型路由、鉴权、限流与缓存。
7. 补齐全链路 Trace、Token/Cost、Metrics 和 Audit。

## 构建验证

当前主分支已通过：

```bash
mvn -DskipTests package
docker compose -f docs/devops/docker-compose.yml config --quiet
```

Compose 会提示顶层 `version` 字段已过时，但不影响当前配置解析与运行。

## License

Apache License 2.0。
