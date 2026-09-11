# User 与 Auth 技术设计

## 1. 当前实现边界

本文件描述当前已实现的用户与认证主链路。

已完成：用户注册、密码哈希校验、`ACTIVE / DISABLED` 用户状态、默认个人 Workspace 初始化、双 JWT 登录、核心接口鉴权、accessToken 刷新、Redis 会话撤销和登出。

未完成：refreshToken 轮换、全端登出、登录设备管理、登录审计、风控、MFA，以及跨站 Cookie 的 CORS 白名单。

## 2. 领域职责

| 模块 | 职责 |
| --- | --- |
| `user` | 用户主体、密码哈希、账号状态、默认个人 Workspace 初始化。 |
| `auth` | 登录校验、JWT 签发与解析、刷新、会话撤销编排。 |
| `infrastructure` | Redisson/Redis 会话撤销记录。 |
| `trigger` | HTTP 接口、Cookie 读写、JWT Filter、Spring Security 集成。 |

`user` 不保存 Token；`auth` 不负责修改用户状态。

## 3. 令牌模型

一次登录签发一对 JWT，它们共享同一个 `session_id`。

| 项目 | accessToken | refreshToken |
| --- | --- | --- |
| 作用 | 调用核心业务接口 | 刷新 accessToken、登出会话 |
| 默认有效期 | 15 分钟 | 30 天 |
| 浏览器承载 | 前端内存，以 `Authorization: Bearer` 发送 | `HttpOnly`、`SameSite=Strict` Cookie |
| `token_type` | `ACCESS` | `REFRESH` |
| 是否可访问业务接口 | 是 | 否 |

JWT 声明：

```text
sub                userId
username           用户名
jti                当前 JWT 的唯一编号
token_type         ACCESS 或 REFRESH
session_id         登录会话编号
session_expires_at refreshToken 的过期时间戳
exp                当前 JWT 的过期时间
```

默认配置：

```yaml
hexworkspace:
  jwt:
    access-expire-minutes: 15
    refresh-expire-days: 30
    refresh-cookie-secure: false # 生产 HTTPS 环境必须为 true
```

## 4. 核心链路

### 4.1 注册

```text
注册请求 → UserService.registerUser
→ BCrypt 哈希密码并保存 ACTIVE 用户
→ WorkspaceService.ensurePersonalWorkspace
→ 返回用户信息
```

注册用户与默认个人 Workspace 处于同一 MySQL 事务，避免产生可登录但没有资料库的用户。

### 4.2 登录

```text
登录请求 → AuthService.login
→ 校验用户名、ACTIVE 状态、BCrypt 密码
→ TokenService.createTokenPair
→ JSON 返回 accessToken
→ Set-Cookie 写入 HttpOnly refreshToken
```

Redis 不保存所有有效 refreshToken；refreshToken 的签名、用途和过期时间构成默认有效性。

### 4.3 业务请求认证

```text
Authorization: Bearer accessToken
→ 校验签名、exp、token_type=ACCESS
→ Redis 检查 auth:revoked:session:{sessionId}
→ MySQL 查询用户且确认 ACTIVE
→ 写入 SecurityContext
→ Spring Security 放行业务接口
```

失败时不建立 `SecurityContext`。注册、登录、刷新、登出以外的接口均要求 `authenticated()`。

### 4.4 自动刷新

```text
前端发现 accessToken 认证失败
→ POST /api/v1/users/refresh
→ 浏览器携带 refreshToken Cookie
→ 校验签名、exp、token_type=REFRESH、未撤销会话、ACTIVE 用户
→ 返回新的 accessToken
→ 前端重试原请求
```

后端不会主动调用浏览器续期；自动刷新由前端拦截认证失败、调用刷新接口和重试原请求完成。刷新不会轮换 refreshToken，也不会延长 30 天的固定会话上限。

### 4.5 登出

```text
POST /api/v1/users/logout
→ 从 refreshToken Cookie 获取 sessionId
→ Redis 写入 auth:revoked:session:{sessionId}=true
→ TTL = refreshToken 剩余有效期
→ Set-Cookie 删除 refreshToken
```

同一 `sessionId` 下的 accessToken 和 refreshToken 在下一次请求时都会被拒绝。登出接口允许匿名到达 Controller，以支持 accessToken 已过期时仍能退出。

## 5. Redis 撤销记录

| 项目 | 说明 |
| --- | --- |
| Key | `auth:revoked:session:{sessionId}` |
| Value | `true` |
| 写入时机 | 登出 |
| TTL | refreshToken 剩余有效期 |
| 检查时机 | accessToken 访问、refreshToken 刷新 |

Redis 保存的是“已撤销会话”，不是 refreshToken 正文。Redis 不可用时认证过滤器失败关闭，不建立认证上下文。

## 6. HTTP 接口

| 接口 | 认证要求 | 输入/承载 | 输出 |
| --- | --- | --- | --- |
| `POST /api/v1/users/register` | 否 | 用户名、密码 | 用户信息 |
| `POST /api/v1/users/login` | 否 | 用户名、密码 | JSON accessToken、Cookie refreshToken |
| `POST /api/v1/users/refresh` | 否 | refreshToken Cookie | 新 accessToken |
| `POST /api/v1/users/logout` | 否 | refreshToken Cookie | 清 Cookie、撤销会话 |
| `GET /api/v1/users/me` | 是 | Bearer accessToken | 当前用户 |

浏览器侧刷新请求需要设置 `credentials: 'include'`。若前后端跨站部署，还必须配置允许 Origin、`allowCredentials=true` 和生产环境 `refresh-cookie-secure=true`。

## 7. 源码定位

| 能力 | 源码 |
| --- | --- |
| Token 对签发、用途隔离、JWT 解析 | `hex-workspace-domain/src/main/java/com/hector/domain/auth/service/impl/TokenServiceImpl.java` |
| 登录、刷新、会话撤销编排 | `hex-workspace-domain/src/main/java/com/hector/domain/auth/service/impl/AuthServiceImpl.java` |
| Redis 撤销记录 | `hex-workspace-infrastructure/src/main/java/com/hector/infrastructure/repository/TokenRevocationRepository.java` |
| 业务请求认证过滤器 | `hex-workspace-trigger/src/main/java/com/hector/trigger/http/security/JwtAuthenticationFilter.java` |
| 登录、刷新、登出 Cookie 适配 | `hex-workspace-trigger/src/main/java/com/hector/trigger/http/UserController.java` |
| 匿名接口与核心接口保护规则 | `hex-workspace-app/src/main/java/com/hector/config/SecurityConfig.java` |

## 8. 验证

- `TokenServiceImplTest`：双 JWT 使用同一 `sessionId`，且用途隔离；
- `AuthServiceImplTest`：登出撤销会话，有效未撤销会话可以刷新 accessToken；
- 全项目检查：`mvn -q test`、`git diff --check`。
