1. logout 功能添加 -- 跳转到登陆页
2. JWT 自动续期 refreshToken + accessToken





1. ✅同一文件上传按钮幂等处理

优先级	问题	当前表现	建议
P0	RUNNING 任务会永久卡死	消费者抢占任务后，进程若在 MinIO 写入前崩溃，任务停在 RUNNING；扫描器只处理 RETRY_WAIT，后续不会恢复	为任务增加 running_lease_until；扫描超时 RUNNING 任务，重新置为 RETRY_WAIT
P0	Outbox 事件没有发布抢占	多实例都能查到同一条 PENDING Outbox，并同时发送 MQ	Outbox 增加 PUBLISHING、publish_lease_until，通过条件更新抢占后再投递
P1	无限重试	MinIO 路径错误、权限错误等永久失败会每分钟无限重试	设置 max_retry_count；达到上限后任务进入 FAILED，Document 进入可识别的失败状态
P1	staging 孤儿对象	先写 staging，再进入 MySQL 事务；进程若在两者之间崩溃，MySQL 无记录可追踪该对象	定时按 staging/ 前缀和保留期清理无引用对象
P1	staging_path 有冗余	Document 与 UploadTask 都保存 staging 路径；当前正式执行只读取 Task	若不做“无任务 CREATING 文档修复”，删除 Document.stagingPath；否则补齐对应恢复任务
P2	文件被完整读入内存两次	Controller 使用 file.getBytes()；消费者又 download() 为 byte[]，50MB 文件并发时内存压力明显	改为流式上传，或使用 MinIO 服务端 CopyObject
P2	正式对象 MIME 丢失	消费者写正式对象时固定使用 application/octet-stream	Task 保存 contentType，或由消费者读取 Document 的 contentType
P2	并发首次重试会短暂重复写 staging	两个请求都在查不到 Document 后先写 staging，数据库唯一键只在后面阻止重复 Document	当前最终结果正确，但浪费 I/O；后续可用“先创建 CREATING Document，再写 staging”的两阶段设计优化







# Folder Domain V1 设计与实现方案

## 1. 任务目标

请基于当前项目已有的 Folder / Document / Workspace 实现，对 Folder Domain 进行 V1 重构和完善。

本次目标不是实现复杂文件系统，也不是实现协同编辑，而是建立一套：

- 树结构正确
- 删除 / 恢复安全
- Folder Move 安全
- 不产生环
- 同级名称约束明确
- Workspace 边界明确
- 删除 subtree 后不可被绕过访问
- 能够支持未来 Trash / GC / Collaboration 演进

的 Folder Domain 基础模型。

核心原则：

> Folder 使用 parent_id 邻接表维护树结构。
>
> Folder 删除采用“只软删除目标 Folder，不级联修改 descendant 状态”的模型。
>
> descendant 是否可访问由 ancestor chain 动态决定。
>
> 所有正常业务入口统一遵循 Accessible 规则。

本次实现优先保证领域 invariant 和正确性，不要为了未来需求过度设计。


---

# 2. 开始修改代码前必须先分析现有实现

不要直接开始修改。

首先搜索并梳理当前项目中所有 Folder 相关代码，包括但不限于：

- Folder Model / Entity / DO / PO
- Folder Repository / DAO / Mapper
- Folder Service
- Folder Controller / Handler / API
- Folder 创建逻辑
- Folder 查询逻辑
- Folder Children 查询
- Folder Rename
- Folder Move
- Folder Delete
- Workspace 创建时 Root Folder 初始化
- Document.parent_id 相关逻辑
- Document 创建 / 上传 / 查询 / 编辑 / 删除时对 Folder 的依赖
- Folder status 枚举
- Root Folder 相关字段与约束
- `is_root`
- `root_workspace_id`
- `workspace_id`
- `parent_id`
- Folder 名称唯一性约束
- 现有事务机制
- 现有并发控制方式
- 现有错误码体系
- 现有权限校验机制

同时重点确认：

1. 当前 Folder 删除是否是物理 DELETE；
2. 是否存在递归删除 Folder / Document / MinIO Object；
3. Folder 删除后 descendant 当前如何处理；
4. 查询 Document 时是否只检查 Document.status；
5. 是否存在通过 documentId / folderId 绕过 Folder Tree 直接访问内容的入口；
6. Root Folder 当前如何创建；
7. 每个 Workspace 是否已经保证只有一个 Root；
8. Folder Move 当前是否存在；
9. Move 是否有 cycle detection；
10. 是否允许跨 Workspace Move；
11. Folder 同级重名目前如何处理；
12. DELETED Folder 是否仍然占用名称；
13. 是否已经存在 recursive CTE / ancestor / descendant 查询能力；
14. Workspace / Folder 是否已有适合用于树结构变更串行化的锁机制。

完成分析后：

> 先输出当前实现分析 + 与本方案的差异 + implementation plan。

确认方案后再开始修改代码。

不要在没有理解现有实现的情况下大范围重构。


---

# 3. Folder V1 核心数据模型

Folder 继续使用邻接表模型：

    folder
    ------
    id
    folder_code
    workspace_id
    parent_id
    name
    is_root
    root_workspace_id
    status
    deleted_at
    created_at
    updated_at

具体字段命名和类型优先沿用现有项目。

如果现有表不存在 `deleted_at`，增加：

    deleted_at DATETIME DEFAULT NULL COMMENT '删除时间'

Folder 正常状态：

    status = ACTIVE
    deleted_at = NULL

Folder 被显式删除：

    status = DELETED
    deleted_at != NULL

不要增加：

- folder_version
- ancestor_deleted
- effective_status
- accessible
- full_path
- materialized_path
- closure table

等冗余模型。

`Accessible` 是动态领域状态，不是数据库持久化字段。


---

# 4. Folder Tree 基本 invariant

必须保证：

1. 每个 Workspace 恰好存在一个 Root Folder；
2. Root Folder：
   - parent_id = NULL
   - is_root = true
   - 永远 ACTIVE
   - 不允许删除
   - 不允许移动
   - V1 不允许重命名
3. 普通 Folder：
   - parent_id != NULL
   - is_root = false
4. Child Folder 与 Parent Folder 必须属于同一 Workspace；
5. Folder Tree 永远不能形成环；
6. V1 不允许 Folder 跨 Workspace Move；
7. Folder.workspace_id 必须与其 ancestor chain 的 Workspace 一致；
8. 普通业务空间中，可操作节点必须 Accessible。


---

# 5. Root Folder

每个 Workspace 必须有且只有一个 Root Folder。

逻辑结构：

    Workspace
        |
        +-- Root Folder
              |
              +-- Folder
              +-- Folder
              +-- Document

Root 是系统节点，不是普通用户 Folder。

禁止：

    Delete Root
    Move Root
    Rename Root

Root 必须：

    parent_id = NULL
    status = ACTIVE
    deleted_at = NULL

Workspace 对用户展示的名称来自：

    workspace.workspace_name

不要依赖 Root Folder.name 表示 Workspace 名称。

如果当前已有：

    root_workspace_id

生成字段或等价机制，用于保证：

    每个 workspace_id 最多一个 Root

则优先沿用现有设计。

不要无必要重构已有 Root 唯一性实现。


---

# 6. Folder Accessible 定义

Accessible 不作为数据库字段。

定义：

    Folder Accessible
    =
    Folder 自身 status == ACTIVE
    AND
    从该 Folder 到 Workspace Root 的所有 ancestor Folder 均为 ACTIVE
    AND
    ancestor chain 完整
    AND
    最终能够到达该 Workspace 的合法 Root

例如：

    Root ACTIVE
    |
    A ACTIVE
    |
    B ACTIVE

则：

    Accessible(B) = true

如果：

    Root ACTIVE
    |
    A DELETED
    |
    B ACTIVE

则：

    Accessible(A) = false
    Accessible(B) = false

虽然：

    B.status == ACTIVE

但由于 ancestor A 已删除：

    B effective accessibility = false

不要把 B.status 修改为 DELETED。


---

# 7. 统一 Accessible 检查能力

不要让：

- FolderService
- DocumentService
- DownloadService
- SearchService
- Agent Tool
- Move Service

分别实现自己的 ancestor 检查。

应在现有项目结构基础上抽象一个统一能力。

名称根据项目风格决定，例如：

    FolderAccessService
    FolderTreeService
    WorkspaceTreeService

应至少具备类似：

    isAccessible(folderId)
    
    requireAccessible(folderId)

的能力。

Document 可访问性定义为：

    Document Accessible
    =
    Document 自身处于正常 ACTIVE 状态
    AND
    Document.parent Folder Accessible

因此：

    document.status == ACTIVE

本身不足以证明 Document 可以访问。


---

# 8. Ancestor 查询实现

优先检查项目是否已有 ancestor / tree 查询能力。

如果没有，可以使用 MySQL 8 recursive CTE 或等价方式，一次查询 ancestor chain。

例如概念 SQL：

    WITH RECURSIVE folder_ancestors AS (
        SELECT
            id,
            workspace_id,
            parent_id,
            status,
            is_root
        FROM folder
        WHERE id = ?
    
        UNION ALL
    
        SELECT
            f.id,
            f.workspace_id,
            f.parent_id,
            f.status,
            f.is_root
        FROM folder f
        INNER JOIN folder_ancestors fa
            ON f.id = fa.parent_id
    )
    SELECT *
    FROM folder_ancestors;

需要能够验证：

- 是否存在 DELETED ancestor；
- ancestor chain 是否断裂；
- 是否最终到达 Root；
- Root 是否属于正确 Workspace；
- chain 中 workspace_id 是否一致。

避免明显的：

    Folder
      -> SELECT Parent
      -> SELECT Parent
      -> SELECT Parent
      -> ...

N+1 查询。

但如果项目已有成熟且性能可接受的 Tree Repository，请优先复用，不要为了使用 CTE 强行重构。


---

# 9. 普通目录浏览的 Accessible 优化

不要对每一个 child 重复执行完整 ancestor 查询。

例如：

    GET /folders/{folderId}/children

流程应该是：

    requireAccessible(folderId)
    
            ↓
    
    Parent 已确认 Accessible
    
            ↓
    
    查询直接 children
    WHERE parent_id = ?
      AND status = ACTIVE

因为 Parent 已经 Accessible，所以它下面自身 ACTIVE 的直接 Child 可以视为 Accessible。

原则：

> 入口节点进行完整 Accessible 校验；
> 沿着已经验证过的树向下遍历时，只检查直接节点自身状态。

避免：

    查询 100 个 children
    -> 每个 child 再查完整 ancestor chain


---

# 10. Create Folder

创建普通 Folder 时：

    CreateFolder(parentId, name)

必须：

1. Parent 存在；
2. Parent Accessible；
3. Parent 属于合法 Workspace；
4. 当前用户具有创建权限；
5. Folder Name 合法；
6. Parent 下不存在同名 ACTIVE Folder。

新 Folder：

    workspace_id = parent.workspace_id
    parent_id = parent.id
    is_root = false
    status = ACTIVE
    deleted_at = NULL

重要：

> workspace_id 应根据 Parent 推导。

不要把：

    client workspaceId

作为 Folder.workspace_id 的可信事实来源。

如果 API 当前同时接收：

    workspaceId
    parentId

则至少必须验证：

    workspaceId == parent.workspace_id

更推荐内部最终以 Parent.workspace_id 为准。


---

# 11. Folder Name 规则

V1 采用以下规则：

1. 创建 / 重命名前执行首尾 trim；
2. trim 后不能为空；
3. 禁止纯空白；
4. 禁止：
   - `.`
   - `..`
5. 长度不得超过数据库字段限制；
6. 同一 Parent 下 ACTIVE Folder 名称唯一；
7. 名称比较遵循当前数据库 collation；
8. 当前数据库如果使用 `utf8mb4_unicode_ci`，则视为大小写不敏感。

例如：

    Agent
    agent
    AGENT

在同一 Parent 下应发生冲突。

不要：

- 删除名称内部空格；
- 擅自把名称转换为 lowercase；
- 做复杂 Unicode normalization；
- 复制 Windows 文件系统全部非法字符规则。

Folder 是逻辑实体，不是本地文件系统路径。


---

# 12. Folder Name 不应参与 Object Storage Path

不要使用：

    /WorkspaceName/FolderName/SubFolderName/file.md

作为稳定 MinIO Object Key。

Folder Rename / Move 不应该触发：

- MinIO Object Move
- DocumentVersion 修改
- content_hash 修改
- content storage_path 修改

Object Storage 应继续使用稳定 ID / Code 结构。

例如：

    documents/{document_id}/versions/{version_no}/...

Folder Tree 是逻辑组织结构。

DocumentVersion / MinIO Object 不应依赖 Folder Name Path。


---

# 13. Folder 与 Document 不共享 namespace

V1 允许：

    Root
    |
    +-- Folder: Agent
    |
    +-- Document: Agent

Folder 名称唯一性只在 Folder 表内部保证。

不要为了禁止：

    Folder.name == Document.file_name

实现跨表唯一性检查。

Document 自身的命名规则属于 Document Domain。


---

# 14. Rename Folder

Rename：

    RenameFolder(folderId, newName)

必须：

1. Folder 存在；
2. Folder Accessible；
3. Folder 不是 Root；
4. normalize / trim newName；
5. 名称合法；
6. 如果 normalized newName == current name：
   - no-op success；
7. 同 Parent 下不存在另一个同名 ACTIVE Folder；
8. 更新 name。

Rename 只修改：

    folder.name

不要修改：

- descendant Folder
- descendant Document
- DocumentVersion
- MinIO Object
- storage_path
- parent_id

Folder Rename 属于 Tree Metadata Operation，不是 Content Operation。


---

# 15. Folder Delete

Folder 删除采用：

> Soft Delete Target Only

假设：

    Root
    |
    A
    |
    B
    |
    C
    |
    doc

用户：

    Delete A

数据库只修改：

    A.status = DELETED
    A.deleted_at = NOW()

保持：

    B.status = ACTIVE
    C.status = ACTIVE
    doc.status = ACTIVE

不要递归 UPDATE descendants。


---

# 16. Delete Folder SQL 语义

删除操作应具备幂等性。

概念 SQL：

    UPDATE folder
    SET status = DELETED,
        deleted_at = NOW()
    WHERE id = ?
      AND status != DELETED;

重复 Delete：

    不应刷新 deleted_at

原因：

未来 Trash Retention / GC 可能依赖：

    deleted_at

如果重复 DELETE 刷新 deleted_at，会错误延长永久清理时间。


---

# 17. Delete Folder 前置条件

DeleteFolder(folderId) 必须：

1. Folder 存在；
2. Folder Accessible；
3. Folder 不是 Root；
4. 用户有删除权限；
5. 执行 soft delete。

不要：

- 删除 descendants；
- 修改 descendant status；
- 删除 Document；
- 删除 DocumentVersion；
- 删除 MinIO Object；
- 创建 DocumentVersion。


---

# 18. Deleted Subtree 行为

删除：

    A [DELETED]
    |
    B [ACTIVE]
    |
    doc [ACTIVE]

虽然 B / doc 自身 ACTIVE，但：

    Accessible(B) = false
    Accessible(doc) = false

整个 subtree 在正常业务空间被冻结。

因此不允许：

- Get B as normal folder
- Rename B
- Move B
- Delete B
- Create Child under B
- Upload Document under B
- Read doc
- Edit doc
- Save new DocumentVersion
- Download doc through normal API

不能通过：

    folderId
    documentId

绕过 deleted ancestor。


---

# 19. Restore Folder

V1 Restore：

    RestoreFolder(folderId)

只恢复目标 Folder。

必须：

1. 使用能够查询 DELETED Folder 的特殊 Repository 查询；
2. Folder 当前必须为 DELETED；
3. Folder 不能是 Root；
4. Parent 必须 Accessible；
5. 原 Parent 下不存在同名 ACTIVE Folder；
6. 执行：

       status = ACTIVE
       deleted_at = NULL

不要：

- 修改 descendants；
- 创建 DocumentVersion；
- 修改 descendant Document；
- 修改 MinIO Object。


---

# 20. Restore 示例

原始：

    Root
    |
    A
    |
    B
    |
    doc

Delete A：

    A   DELETED
    B   ACTIVE
    doc ACTIVE

Restore A：

    A   ACTIVE
    B   ACTIVE
    doc ACTIVE

整个 subtree 自动重新 Accessible。


---

# 21. 独立删除状态必须保留

例如：

    Root
    |
    A ACTIVE
    |
    B ACTIVE
    |
    doc DELETED

之后：

    Delete A

得到：

    A   DELETED
    B   ACTIVE
    doc DELETED

Restore A：

    A   ACTIVE
    B   ACTIVE
    doc DELETED

doc 必须保持之前自己的显式删除状态。

这也是为什么禁止 Delete Folder 时级联修改 descendant.status。


---

# 22. Restore Parent 规则

如果：

    A DELETED
    |
    B DELETED

尝试：

    Restore B

但：

    Parent A is not Accessible

则拒绝恢复。

V1 不要自动：

    Restore A + Restore B

要求用户先恢复 A。

原则：

> Restore 只恢复用户明确指定的 Folder。


---

# 23. Restore Name Conflict

允许 Deleted Folder 不占用正常 namespace。

例如：

    Root
    |
    项目 [DELETED]

之后允许创建：

    Root
    |
    项目 [ACTIVE]
    项目 [DELETED]

此时恢复旧 Folder：

    Restore old 项目

必须返回名称冲突。

V1 不要自动：

    项目 -> 项目(1)

未来可以支持：

    Restore + Rename
    Restore + Move

但不属于当前 V1。


---

# 24. Trash 语义

Trash 只展示：

> 显式 DELETED 的顶层删除节点。

例如：

    A DELETED
    |
    B ACTIVE
    |
    C ACTIVE
    |
    doc ACTIVE

用户删除动作只有：

    Delete A

则 Trash 应展示：

    Folder A

不要把 B / C / doc 当作独立 Trash Item。

如果实现 Trash 查询，需要识别真正的 Trash Root。

概念条件：

    node.status = DELETED

并且：

    parent is Accessible

或者不存在另一个 DELETED ancestor 使该节点成为某个更高层 Trash subtree 的内部节点。

具体实现应结合现有 Tree Repository 设计。


---

# 25. Move Folder

Move 定义为：

> 修改 source Folder 的 parent_id。

例如：

    Root
    |
    +-- A
    |    |
    |    +-- B
    |
    +-- C

Move:

    B -> C

只修改：

    B.parent_id = C.id

不要修改：

- B descendants
- Document parent under B
- DocumentVersion
- MinIO Object
- storage_path


---

# 26. Move 基本校验

MoveFolder(sourceId, targetParentId) 必须满足：

1. Source 存在；
2. Target Parent 存在；
3. Source 不是 Root；
4. Source Accessible；
5. Target Parent Accessible；
6. Source.workspace_id == Target.workspace_id；
7. targetParentId != sourceId；
8. Target Parent 不是 Source descendant；
9. Target Parent 下不存在同名 ACTIVE Folder；
10. 当前用户具有 Move 权限。

V1 禁止跨 Workspace Move。


---

# 27. Move 到当前 Parent

如果：

    source.parent_id == targetParentId

则：

    no-op success

不要返回错误。

不要执行无意义 UPDATE。


---

# 28. Move 防环

例如：

    Root
    |
    A
    |
    B
    |
    C

禁止：

    Move A -> A
    Move A -> B
    Move A -> C

必须保证：

    targetParent ∉ descendants(source)

可以：

- 从 targetParent 向 Root 查询 ancestor chain；
- 如果过程中遇到 source，则拒绝；

或者：

- 查询 source descendants；
- 判断 targetParent 是否在其中。

优先复用项目已有 Tree 查询能力。


---

# 29. Move 与 Deleted Subtree

例如：

    Root
    |
    A DELETED
    |
    B ACTIVE

禁止：

    Move B -> Root

因为：

    Accessible(B) = false

不能通过 Move 把节点从 Deleted Subtree 中“偷出来”。

同样：

    Move C -> B

也必须拒绝，因为 Target B 不 Accessible。

Deleted subtree 在正常业务空间中应被冻结。


---

# 30. Move Name Conflict

例如：

    A
    |
    项目
    
    B
    |
    项目

执行：

    Move A/项目 -> B

必须返回：

    NAME_CONFLICT

V1 不自动重命名。


---

# 31. Move 并发安全

必须考虑：

    T1: Move A -> B
    T2: Move B -> A

如果两个事务分别先检查：

    B 不是 A descendant
    A 不是 B descendant

然后同时 UPDATE，可能形成：

    A <-> B

因此：

> Cycle Check + parent_id Update 必须具有并发安全性。

V1 不需要实现复杂 Tree Concurrency Protocol。

优先检查项目是否已有：

- Workspace Lock
- DB pessimistic lock
- distributed lock
- transaction lock pattern

对于当前规模，可以采用：

> 同一个 Workspace 内的 Tree Structure Mutation 串行化。

Tree Structure Mutation 至少包括：

- Move Folder

如果实现简单且符合现有架构，也可以将：

- Create Folder
- Delete Folder
- Restore Folder

纳入相同 Tree Mutation Lock。

目标是保证：

    Lock Workspace Tree
          ↓
    Reload Source / Target
          ↓
    Accessibility Check
          ↓
    Cycle Check
          ↓
    Name Conflict Check
          ↓
    UPDATE parent_id
          ↓
    COMMIT
          ↓
    Unlock

具体锁实现必须优先复用现有项目基础设施。

不要为了这个功能引入重量级分布式系统。


---

# 32. ACTIVE Folder 同级唯一

业务 invariant：

> 同一 Parent 下只能存在一个同名 ACTIVE Folder。

Deleted Folder 不占用 ACTIVE namespace。

因此允许：

    parent=100
    name=项目
    status=ACTIVE

同时存在：

    parent=100
    name=项目
    status=DELETED

但不允许两个 ACTIVE。


---

# 33. 名称唯一性必须有数据库兜底

不能只依赖：

    SELECT exists
          ↓
    INSERT

因为并发 Create / Rename / Move 可能同时通过检查。

必须：

    Service Validation
    +
    Database Unique Constraint

Service Validation 用于友好错误；

Database Constraint 用于最终保证 invariant。


---

# 34. ACTIVE Namespace Unique Constraint

如果当前 MySQL 结构适合，可以使用 Generated Column。

概念设计：

    active_parent_id BIGINT
    GENERATED ALWAYS AS (
        CASE
            WHEN status = ACTIVE THEN parent_id
            ELSE NULL
        END
    ) STORED

然后：

    UNIQUE KEY uk_folder_active_name (
        active_parent_id,
        name
    )

因为 MySQL UNIQUE 允许多个 NULL：

Deleted Folder：

    active_parent_id = NULL

不会占用 ACTIVE namespace。

具体 ACTIVE 枚举值必须使用项目当前定义，不要假设固定为 0。


---

# 35. Root 唯一性单独保证

由于 Root：

    parent_id = NULL

不能依赖：

    UNIQUE(parent_id, name)

保证 Workspace Root 唯一。

继续使用现有：

    is_root
    root_workspace_id

或等价机制保证：

> 每个 Workspace 最多一个 Root。

例如：

    root_workspace_id =
        CASE
            WHEN is_root = true
            THEN workspace_id
            ELSE NULL
        END

然后：

    UNIQUE(root_workspace_id)

具体实现优先复用现有 schema。


---

# 36. Repository 查询语义必须明确

不要让一个模糊的：

    findById()

在所有场景随意使用。

至少在逻辑上区分：

## Normal Space Query

用于正常业务。

要求：

    status == ACTIVE
    +
    ancestor chain Accessible

例如：

    findAccessibleFolder(...)
    requireAccessible(...)

## Deleted / Trash Query

用于：

- Restore
- Trash
- GC
- Admin recovery

允许查询 DELETED。

例如：

    findIncludingDeleted(...)
    findDeletedFolder(...)

具体方法名根据现有项目规范决定。

重点是：

> 正常业务路径不能意外读取 Deleted / ancestor-deleted 内容。


---

# 37. Document 与 Folder Accessible 集成

必须检查所有能够直接访问 Document 的业务入口。

例如：

    Get Document
    Read Document Content
    Download Document
    Edit Document
    Save Document Version
    Rename Document
    Move Document
    Delete Document
    Agent Read Document
    Search Result -> Open Document

这些操作不能只检查：

    document.status == ACTIVE

必须满足：

    document.status == ACTIVE
    AND
    requireAccessible(document.parent_id)

否则：

    A DELETED
    |
    secret.md ACTIVE

可能通过：

    GET /documents/{id}

绕过 Folder 删除语义。

这属于本次 Folder V1 必须修复的领域边界。


---

# 38. 与 DocumentVersion 的边界

以下 Folder 操作：

- Create
- Rename
- Move
- Delete
- Restore

都属于：

    Folder / Tree Metadata Operation

因此：

> 不创建 DocumentVersion。

Folder 操作不能修改 descendant Document 的：

    current_version_no

也不能修改：

    document_version

更不能覆盖 / 移动 MinIO Version Object。


---

# 39. Folder Purge / GC：仅保留演进能力

V1 不要求实现完整 Folder GC。

但模型必须支持未来：

    Trash
      ↓
    retention period
      ↓
    Purge Folder Subtree

未来 Purge Folder 必须以整个 subtree 为单位。

例如：

    A DELETED
    |
    B ACTIVE
    |
    doc ACTIVE

如果最终物理删除 A：

    B / doc 也必须一起物理清理

否则会产生 orphan。


---

# 40. 未来 Folder Purge 概念流程

未来可以：

    Trash Root 到期
          ↓
    查询完整 subtree
          ↓
    获取所有 descendant Documents
          ↓
    获取所有 DocumentVersions
          ↓
    清理 MinIO Objects
          ↓
    删除 DocumentVersions
          ↓
    删除 Documents
          ↓
    从叶子到根删除 Folders
          ↓
    删除 Trash Root

具体：

- Retry
- Compensation
- GC Job
- Purge State
- Batch
- Observability
- Object cleanup failure handling

全部不属于当前 V1。


---

# 41. Folder GC 应以 Trash Root 为单位

未来不要简单：

    WHERE status = DELETED
      AND deleted_at < retention

然后逐个 Folder 独立 purge。

例如：

    A DELETED at T1
    |
    B DELETED at T0

B 可能只是 A subtree 内部节点。

未来 GC 应识别：

> 顶层显式删除节点 / Trash Root

然后以该 subtree 的顶层删除时间作为整个 subtree 的 retention 基础。

当前 V1 不需要实现，只需要避免设计阻塞未来能力。


---

# 42. 本次明确不做

不要实现：

- Closure Table
- Nested Set
- Materialized Path
- full_path 持久化
- Folder Version
- Folder Event Sourcing
- EditSession
- Draft
- WebSocket
- CRDT
- Yjs
- Presence
- Cursor
- Selection
- CollaborationSession
- realtime sync
- Folder recursive soft-delete
- DocumentVersion soft-delete
- Folder 操作生成 DocumentVersion
- 跨 Workspace Folder Move
- 自动重命名冲突 Folder
- Restore + Rename
- Restore + Move
- 完整 Trash GC
- Purge State Machine
- 复杂分布式 Tree Lock Protocol

不要因为未来可能需要而提前引入这些结构。


---

# 43. 推荐领域操作语义

最终 Folder Domain 应大致具备：

    createFolder(parentId, name)
    
    renameFolder(folderId, newName)
    
    moveFolder(folderId, targetParentId)
    
    deleteFolder(folderId)
    
    restoreFolder(folderId)
    
    getFolder(folderId)
    
    listChildren(folderId)

以及统一 Tree Access 能力：

    isAccessible(folderId)
    
    requireAccessible(folderId)

具体方法名称、分层和 package 必须遵循当前项目代码风格。


---

# 44. 错误语义

请优先复用现有项目错误码体系。

需要能够明确区分类似：

    FOLDER_NOT_FOUND
    
    FOLDER_NOT_ACCESSIBLE
    
    FOLDER_ALREADY_DELETED
    
    ROOT_FOLDER_OPERATION_NOT_ALLOWED
    
    TARGET_FOLDER_NOT_ACCESSIBLE
    
    CROSS_WORKSPACE_MOVE_NOT_ALLOWED
    
    FOLDER_MOVE_CYCLE
    
    FOLDER_NAME_CONFLICT
    
    INVALID_FOLDER_NAME
    
    RESTORE_PARENT_NOT_ACCESSIBLE

具体名称不要机械照抄，如果项目已有统一错误码规范，应遵循现有规范。


---

# 45. 最低测试要求

至少覆盖以下测试。


## Create

1. 在正常 Parent 下创建 Folder 成功；
2. workspace_id 正确继承 Parent；
3. Parent DELETED 时创建失败；
4. Parent 因 ancestor deleted 而 inaccessible 时创建失败；
5. 同 Parent 同名 ACTIVE Folder 创建失败；
6. 同名 DELETED Folder 存在时允许创建；
7. 空名称失败；
8. 纯空白失败；
9. `.` / `..` 失败。


## Rename

10. 普通 Folder Rename 成功；
11. Rename 不影响 descendants；
12. Rename 不影响 DocumentVersion；
13. Root Rename 失败；
14. Rename 到同 Parent 已存在 ACTIVE 名称失败；
15. Deleted Folder 不可通过正常 Rename；
16. Rename 为当前名称时 no-op。


## Delete

17. ACTIVE Folder Delete 后：

        status = DELETED
        deleted_at != NULL

18. current descendants 状态全部保持不变；
19. descendant DocumentVersion 保持不变；
20. MinIO Objects 保持不变；
21. Root Delete 失败；
22. 重复 Delete 不刷新 deleted_at；
23. Delete 不创建 DocumentVersion。


## Accessibility

24. ACTIVE Folder + 全部 ACTIVE ancestors → Accessible；
25. Folder 自己 DELETED → Inaccessible；
26. Folder ACTIVE + ancestor DELETED → Inaccessible；
27. Document ACTIVE + Parent Accessible → Accessible；
28. Document ACTIVE + ancestor Folder DELETED → Inaccessible；
29. 直接通过 documentId 读取 ancestor-deleted Document 必须失败；
30. 直接通过 folderId 访问 ancestor-deleted Folder 必须失败。


## Restore

31. DELETED Folder + Parent Accessible → Restore 成功；
32. Restore 后：

        status = ACTIVE
        deleted_at = NULL

33. descendants 自动重新 Accessible；
34. descendant 原本显式 DELETED 的节点保持 DELETED；
35. Parent inaccessible 时 Restore 失败；
36. Parent 下出现同名 ACTIVE Folder 时 Restore 失败；
37. Restore 不创建 DocumentVersion。


## Move

38. 正常 Move 成功；
39. Move 只修改 source.parent_id；
40. descendants parent_id 不变；
41. descendant Documents 不变；
42. DocumentVersion 不变；
43. Move Root 失败；
44. Move 到自己失败；
45. Move 到 descendant 失败；
46. Move 后不能形成 cycle；
47. 跨 Workspace Move 失败；
48. Source inaccessible 时 Move 失败；
49. Target inaccessible 时 Move 失败；
50. Target 下存在同名 ACTIVE Folder 时 Move 失败；
51. Target 下只有同名 DELETED Folder 时允许 Move；
52. Move 到当前 Parent 为 no-op success。


## Concurrency

53. 并发创建同 Parent 同名 Folder 最终最多一个成功；
54. 并发 Rename / Move 不得破坏 ACTIVE namespace unique invariant；
55. 并发：

        Move A -> B
        Move B -> A

不得形成 cycle。


## Root

56. 每个 Workspace 只能存在一个 Root；
57. Root.parent_id = NULL；
58. Root 永远 ACTIVE；
59. Root 不允许 Delete；
60. Root 不允许 Move；
61. Root V1 不允许 Rename。


---

# 46. 实现原则

本次实现请遵循：

1. 优先复用当前项目架构；
2. 优先复用已有事务；
3. 优先复用已有权限体系；
4. 优先复用已有锁机制；
5. 优先复用已有 Repository / Mapper 风格；
6. 不无理由重构无关代码；
7. 不改变已有 API 行为，除非本方案明确要求；
8. Schema 变更提供 migration；
9. 所有 Tree invariant 尽可能同时具备：
   - Service 层校验；
   - Database 层最终约束；
10. 对现有 Document 业务做必要 Accessible 集成，但不要顺便大规模重构 Document Domain。


---

# 47. 最终需要输出的结果

执行时请按以下顺序：

## Step 1：现状分析

输出：

- 当前 Folder 数据模型；
- 当前 Root 实现；
- 当前 Create / Rename / Move / Delete 行为；
- 当前 Document 与 Folder 的关系；
- 当前 Tree 查询能力；
- 当前事务 / Lock 能力；
- 与本方案存在的差异。


## Step 2：Implementation Plan

列出：

- Schema 修改；
- Model 修改；
- Repository 修改；
- Tree / Access Domain 能力；
- Service 修改；
- API 修改；
- Document Accessible 集成；
- Lock / Transaction 方案；
- Test 修改。

并指出：

- 哪些代码可以复用；
- 哪些地方存在兼容风险；
- 哪些地方需要 migration。


## Step 3：实施

按照 Plan 修改。


## Step 4：验证

至少执行：

- build
- unit tests
- integration tests（如果项目已有）
- migration / schema 检查
- Folder Tree invariant 测试

最后输出：

- 修改文件列表；
- Schema 变化；
- 新增 / 修改接口；
- 核心领域规则；
- 测试结果；
- 尚未处理但属于未来 V2 的事项。


---

# 48. 最终 V1 领域模型

本次实现最终必须维持以下模型：

                         Workspace
                             |
                         Root Folder
                             |
              +--------------+--------------+
              |                             |
           Folder A                       Folder C
              |
           Folder B
              |
           Document
              |
        DocumentVersion
        v1 -> v2 -> v3


Folder Tree 负责：

    组织关系
    parent-child
    accessibility
    delete / restore
    move
    namespace

Document 负责：

    logical document
    metadata
    lifecycle

DocumentVersion 负责：

    immutable content history

MinIO 负责：

    immutable content objects


最终安全模型：

    Content Safety
        =
    DocumentVersion
    + Immutable Object
    + current_version_no
    
    Concurrency Safety
        =
    Document baseVersion / CAS
    
    Tree Safety
        =
    Folder parent_id
    + Cycle Detection
    + Workspace Boundary
    + Tree Mutation Concurrency Control
    
    Delete Safety
        =
    Document Soft Delete
    + Folder Target-Only Soft Delete
    + Restore
    
    Access Safety
        =
    Self ACTIVE
    + Ancestor Chain ACTIVE
    
    Physical Delete Safety
        =
    User Delete 与未来 GC / Purge 解耦


本次 V1 的目标不是做完整文件系统。

目标是：

> 先建立一个简单、正确、可演进的 Workspace / Folder / Document 基础领域模型，
> 使未来增加 Trash、Search、RAG、Agent、Permission、Share、Collaborative Editing、CRDT 时，
> 不需要推翻当前 Folder Tree 和 DocumentVersion 基础结构。