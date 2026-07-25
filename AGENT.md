# AGENT.md

## 开发总则
- 不要主动改动项目结构；只在既有结构内补实现。
- 新增功能前先确认当前分支与当前阶段边界，不把不同主题混成一笔提交。
- 代码风格优先参考 `~/Desktop/big_market_review/big-market`。

## 领域与分层
- `user`、`tenant`、`membership` 作为同一业务下的三个子域，职责分开。
- `user` 只管用户主体；`tenant` 只管租户主体；`membership` 只管成员关系。
- `app` 模块只保留启动类和配置类，不放领域 Service 实现。
- `trigger` 负责接口适配、参数校验、响应包装和异常捕捉。
- `domain` 放领域接口、业务规则和领域实体。
- `infrastructure` 放 MySQL/MyBatis、Redis、JWT、密码等技术实现。

## 命名规范
- `domain/.../model/entity` 下的类是业务实体，只在业务层流转，命名必须以 `Entity` 结尾。
- 业务实体属性要凝练，只保留业务需要的字段。
- `infrastructure/.../dao/po` 下的是持久化对象，只做数据库映射，命名按表名语义来，不强制使用 `PO` 后缀。
- `service` 接口命名必须以 `Service` 结尾。
- `service` 实现类必须在对应接口父级目录的 `impl` 子目录下，并以 `Impl` 结尾。

## 持久化规范
- 用户、租户等基础 CRUD 统一使用 MySQL + MyBatis。
- DAO 接口与 `mapper.xml` 必须同步补齐。
- 领域层不直接写 SQL。
- 当前 `user` 仓储先保持纯 MySQL，不引入 Redis 缓存。
- `infrastructure` 下的仓储实现统一放在 `repository` 目录，不按领域再细分子目录。

## 接口规范
- Controller 层统一做 `Response` 包装。
- `success` / `failure` 这类通用包装逻辑必须抽到可复用基类，避免每个 Controller 重复实现。
- 每个对外接口都要写注释，说明作用，并给出简单 `curl` 示例。

## 注入规范
- 优先使用 `@Resource` 从 Spring 容器注入依赖。
- 尤其是 Controller 层，后续会做业务聚合，尽量避免构造注入带来的代码膨胀。

## 日志规范
- 所有关键方法要打印中文日志。
- 日志要包含必要业务信息，如 `userId`、`tenantId`、`username`、`token` 等。
- 方法真正执行前要打开始日志。
- 异常发生时必须打异常日志。
- 日志风格参考 `big-market`：中文动作语义 + 关键字段 + 开始/完成/异常。

## 认证规范
- 当前登录方案使用 JWT。
- JWT 只放最小必要身份信息，不塞太多业务数据。
- `logout` 先保留轻量实现，不把认证复杂度作为当前项目亮点。
- 认证相关能力与用户主体能力拆分到不同领域。

## 数据与环境
- Docker 与初始化脚本统一放在 `docs/devops` 下。
- MySQL 库名固定使用 `hex_doc`。
- MySQL 启动时要自动加载 `docs/devops/mysql/sql` 下的初始化脚本。

## Git 规范
- 保持 `main` 稳定，不要把开发中的功能直接混进 `main`。
- 新功能从当前功能分支继续切分，不要先合回 `main` 再开新分支。
- 一次提交尽量只做一类事情。
- 及时清理 `.idea`、`target`、生成物等不该进版本库的内容。

## 当前阶段约定
- 先按顺序推进 `user`、`tenant`、`membership`。
- 当前如果明确说“先不做某领域”，就不要主动扩展那个领域。
