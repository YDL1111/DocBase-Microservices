# 身份认证与系统管理 API

IAM 服务提供认证、用户管理、角色管理、菜单管理、组织管理和权限计算能力。所有接口通过
Gateway 路由到 `iam-service`。

## 路由前缀

| 前缀 | 说明 |
| --- | --- |
| `/api/auth/**` | 认证接口（登录、注册、刷新、注销、当前用户、权限、菜单） |
| `/api/system/**` | 系统管理接口（用户、角色、菜单、组织 CRUD） |

## 认证方式

登录后获得 `accessToken` 和 `refreshToken`。请求受保护接口时在 Header 中携带：

```
Authorization: Bearer <accessToken>
```

---

## 认证接口

### GET /api/auth/setup

匿名。返回首次超级管理员初始化状态：

```json
{
  "required": true,
  "enabled": true
}
```

- `required=true`：当前没有启用且未删除的超级管理员。
- `enabled=true`：部署人员已配置 32–256 位的 `IAM_ADMIN_SETUP_KEY`，可以提交初始化请求。
- 已存在有效超级管理员时 `required=false`，初始化入口关闭。

### POST /api/auth/setup

匿名但受部署密钥保护，仅创建首个超级管理员，不是普通用户注册接口。创建、数据库互斥锁
和系统角色关联在同一事务中完成；多个 IAM 实例并发请求时最多一个成功。
Gateway 通过 Redis 按来源共享限流：每秒补充 1 个令牌，最多突发 3 个请求。

请求：

```json
{
  "setupKey": "operator-only-key",
  "username": "admin",
  "nickname": "Administrator",
  "password": "your-strong-password"
}
```

错误：

- `403 ADMIN_SETUP_KEY_INVALID` - 部署密钥错误
- `400 ADMIN_SETUP_DISABLED` - 未配置部署密钥
- `400 ADMIN_SETUP_CLOSED` - 已存在有效超级管理员
- `400 USERNAME_EXISTS` - 用户名已存在（包括软删除用户）
- `400 MIGRATION_MISSING` - 管理员互斥守卫行或系统管理员角色缺失

### POST /api/auth/login

匿名。使用账号密码登录，返回访问令牌和刷新令牌。

请求：
```json
{
  "username": "admin",
  "password": "your-password"
}
```

响应：
```json
{
  "success": true,
  "code": "OK",
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "expiresIn": 1800,
    "userInfo": {
      "userId": 1,
      "username": "admin",
      "nickname": "Administrator",
      "email": "",
      "phoneNumber": "",
      "organizationId": 2,
      "admin": true
    },
    "permissions": ["system:user:list", "system:role:list"]
  }
}
```

错误：
- `401 BAD_CREDENTIALS` - 用户名或密码错误，或账号被禁用

### GET /api/auth/registration

匿名。返回布尔值，表示当前部署是否开放自助注册。

### POST /api/auth/register

匿名。请求仅允许账号资料：

```json
{
  "username": "alice",
  "nickname": "Alice",
  "email": "alice@example.com",
  "password": "your-strong-password"
}
```

服务端固定关联 `registered_user` 最小权限角色；请求不能指定 `roleIds`、`organizationId`
或管理员标记。新账号初始不属于任何组织，需由管理员在用户管理中分配。

### POST /api/auth/refresh

匿名。使用 refreshToken 轮换新的令牌对。旧的 refreshToken 立即失效。

请求：
```json
{
  "refreshToken": "eyJ..."
}
```

响应：同 `/api/auth/login`。

错误：
- `401 BAD_CREDENTIALS` - refreshToken 无效、过期或已被撤销

### POST /api/auth/logout

需认证。撤销 refreshToken 并 bump 会话版本（使该用户所有会话失效）。

请求：
```json
{
  "refreshToken": "eyJ..."
}
```

### GET /api/auth/me

需认证。返回当前用户信息。

### GET /api/auth/permissions

需认证。返回当前用户的权限标识集合。

### GET /api/auth/menus

需认证。返回当前用户的菜单树。

---

## 系统管理接口

所有 `/api/system/**` 接口需要认证，并按权限字符串进行方法级授权。

### 用户管理

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/system/users` | `system:user:list` |
| GET | `/api/system/users/{userId}` | `system:user:list` |
| POST | `/api/system/users` | `system:user:create` |
| PUT | `/api/system/users/{userId}` | `system:user:update` |
| DELETE | `/api/system/users/{userId}` | `system:user:delete` |
| PUT | `/api/system/users/{userId}/status` | `system:user:update` |
| PUT | `/api/system/users/{userId}/password` | `system:user:reset-password` |
| GET | `/api/system/users/{userId}/roles` | `system:user:list` |

创建、更新用户均可使用可空字段 `organizationId`。组织变更会提升用户授权版本，旧 access
token 立即失效；用户重新登录或刷新 token 后获得新的 `organization_id` claim。

### 角色管理

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/system/roles` | `system:role:list` |
| GET | `/api/system/roles/all` | `system:role:list` |
| GET | `/api/system/roles/{roleId}` | `system:role:list` |
| POST | `/api/system/roles` | `system:role:create` |
| PUT | `/api/system/roles/{roleId}` | `system:role:update` |
| DELETE | `/api/system/roles/{roleId}` | `system:role:delete` |
| PUT | `/api/system/roles/{roleId}/status` | `system:role:update` |
| GET | `/api/system/roles/{roleId}/menus` | `system:role:list` |
| PUT | `/api/system/roles/{roleId}/menus` | `system:role:update` |

角色停用或删除时，若该角色仍是某些菜单的唯一有效 Owner，服务端返回
`ROLE_LAST_MENU_OWNER` 并拒绝操作。应先将这些菜单的管理归属转让给其他有效角色；
不得通过自动清空 Owner 绕过该保护。

### 组织管理

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/system/organizations` | `system:org:list` |
| POST | `/api/system/organizations` | `system:org:create` |
| PUT | `/api/system/organizations/{organizationId}` | `system:org:update` |
| DELETE | `/api/system/organizations/{organizationId}` | `system:org:delete` |

组织树最大 8 层；父组织必须启用；禁止循环。存在下级组织或用户时不能删除；存在启用的
下级组织或启用用户时不能停用。Knowledge 服务将 JWT 的 `organization_id` 与知识库、
文档创建时的组织快照比较：公开内容可读、同组织部门内容可读、私有内容仍要求成员关系。
历史 `organization_id=NULL` 数据保持 fail-closed，不会因升级扩大可见范围。

### 菜单管理

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/system/menus` | `system:menu:list` |
| GET | `/api/system/menus/tree` | `system:menu:list` |
| GET | `/api/system/menus/{menuId}` | `system:menu:list` |
| POST | `/api/system/menus` | `system:menu:create` |
| PUT | `/api/system/menus/{menuId}` | `system:menu:update` |
| PUT | `/api/system/menus/{menuId}/status` | `system:menu:update` |
| DELETE | `/api/system/menus/{menuId}` | `system:menu:delete` |
| GET | `/api/system/menus/{menuId}/owners` | `admin:all` |
| PUT | `/api/system/menus/{menuId}/owners` | `admin:all` |

### 菜单管理归属

`GET /api/system/menus/{menuId}/owners` 返回当前有效管理角色的 ID 列表；已停用或已删除角色不会出现在结果中。

`PUT /api/system/menus/{menuId}/owners` 按全量替换语义提交：

```json
{
  "roleIds": [1, 2, 3]
}
```

`roleIds` 必须是最多 100 个正整数，服务端会去重并要求候选角色存在、启用且未删除。空数组 `[]` 合法，表示该菜单由系统托管：不归属任何普通角色，仅超级管理员可管理。

菜单管理归属只写入 `sys_menu_owner_role`，用于界定菜单管理权；角色菜单权限授权只写入 `sys_role_menu`。两者严格分离：设置管理归属不会授予该菜单权限，也不能通过角色菜单授权接口实现归属管理。

---

## 权限字符串清单

| 权限 | 说明 |
| --- | --- |
| `system:user:list` | 查看用户 |
| `system:user:create` | 创建用户 |
| `system:user:update` | 修改用户/状态 |
| `system:user:delete` | 删除用户 |
| `system:user:reset-password` | 重置密码 |
| `system:role:list` | 查看角色 |
| `system:role:create` | 创建角色 |
| `system:role:update` | 修改角色/状态 |
| `system:role:delete` | 删除角色 |
| `system:menu:list` | 查看菜单 |
| `system:menu:create` | 创建菜单 |
| `system:menu:update` | 修改菜单 |
| `system:menu:delete` | 删除菜单 |
| `system:org:list` | 查看组织树 |
| `system:org:create` | 创建组织 |
| `system:org:update` | 修改或启停组织 |
| `system:org:delete` | 删除空组织 |

---

## 常见错误码

| HTTP | Code | 说明 |
| ---: | --- | --- |
| 401 | `UNAUTHORIZED` | 未认证或 Token 无效 |
| 401 | `BAD_CREDENTIALS` | 用户名/密码错误或 refreshToken 无效 |
| 403 | `FORBIDDEN` | 无权限访问 |
| 400 | `USERNAME_EXISTS` | 用户名已存在 |
| 400 | `ROLE_KEY_EXISTS` | 角色标识已存在 |
| 400 | `USER_NOT_FOUND` | 用户不存在 |
| 400 | `ROLE_NOT_FOUND` | 角色不存在 |
| 400 | `MENU_NOT_FOUND` | 菜单不存在 |
| 400 | `ROLE_LAST_MENU_OWNER` | 角色仍是某些菜单的唯一有效 Owner，需先转让菜单归属 |
| 400 | `MENU_HAS_CHILDREN` | 菜单存在子节点，无法删除 |
| 400 | `VALIDATION_ERROR` | 请求参数校验失败 |
| 500 | `INTERNAL_ERROR` | 服务器内部错误 |
