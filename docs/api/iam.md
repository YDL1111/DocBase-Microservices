# IAM API 文档

IAM 服务提供认证、用户管理、角色管理、菜单管理和权限计算能力。所有接口通过
Gateway 路由到 `iam-service`。

## 路由前缀

| 前缀 | 说明 |
| --- | --- |
| `/api/auth/**` | 认证接口（登录、刷新、注销、当前用户、权限、菜单） |
| `/api/system/**` | 系统管理接口（用户、角色、菜单 CRUD） |

## 认证方式

登录后获得 `accessToken` 和 `refreshToken`。请求受保护接口时在 Header 中携带：

```
Authorization: Bearer <accessToken>
```

---

## 认证接口

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
      "admin": true
    },
    "permissions": ["system:user:list", "system:role:list"]
  }
}
```

错误：
- `401 BAD_CREDENTIALS` - 用户名或密码错误，或账号被禁用

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

角色停用或删除时，若该角色仍是某些菜单的唯一有效 Owner，服务端返回
`ROLE_LAST_MENU_OWNER` 并拒绝操作。应先将这些菜单的管理归属转让给其他有效角色；
不得通过自动清空 Owner 绕过该保护。

### 菜单管理

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/system/menus` | `system:menu:list` |
| GET | `/api/system/menus/tree` | `system:menu:list` |
| GET | `/api/system/menus/{menuId}` | `system:menu:list` |
| POST | `/api/system/menus` | `system:menu:create` |
| PUT | `/api/system/menus/{menuId}` | `system:menu:update` |
| DELETE | `/api/system/menus/{menuId}` | `system:menu:delete` |
| GET | `/api/system/menus/{menuId}/owners` | `admin:all` |
| PUT | `/api/system/menus/{menuId}/owners` | `admin:all` |

### 菜单管理归属（Owner）

`GET /api/system/menus/{menuId}/owners` 返回当前**有效** Owner 的角色 ID 列表；已停用或已删除角色不会出现在结果中。

`PUT /api/system/menus/{menuId}/owners` 按全量替换语义提交：

```json
{
  "roleIds": [1, 2, 3]
}
```

`roleIds` 必须是最多 100 个正整数，服务端会去重并要求候选角色存在、启用且未删除。空数组 `[]` 合法，表示该菜单由系统托管：不归属任何普通角色，仅超级管理员可管理。

Owner 只写入 `sys_menu_owner_role`，用于界定菜单管理权；角色菜单 permission 授权只写入 `sys_role_menu`。两者严格分离：设置 Owner 不会授予该菜单 permission，也绝不能通过角色菜单授权接口实现 Owner 管理。

> 非阻断技术债：系统预置菜单未来应使用稳定业务标识或唯一约束。当前 Flyway V12 为兼容升级，仍通过 `routerName`/`permission` 识别历史系统节点；本阶段未引入 `seed_key` 或进行数据库重构。

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

---

## 旧接口到新接口映射

| 旧项目路径 | 新路径 | 说明 |
| --- | --- | --- |
| `POST /login` | `POST /api/auth/login` | 登录 |
| `GET /getLoginUserInfo` | `GET /api/auth/me` | 当前用户 |
| `GET /getRouters` | `GET /api/auth/menus` | 菜单树 |
| `POST /logout` | `POST /api/auth/logout` | 注销 |
| `GET /system/users` | `GET /api/system/users` | 用户列表 |
| `POST /system/users` | `POST /api/system/users` | 创建用户 |
| `PUT /system/users/{id}` | `PUT /api/system/users/{id}` | 修改用户 |
| `DELETE /system/users/{id}` | `DELETE /api/system/users/{id}` | 删除用户 |
| `PUT /system/users/{id}/password` | `PUT /api/system/users/{id}/password` | 重置密码 |
| `PUT /system/users/{id}/status` | `PUT /api/system/users/{id}/status` | 状态修改 |
| `GET /system/role/list` | `GET /api/system/roles` | 角色列表 |
| `POST /system/role` | `POST /api/system/roles` | 创建角色 |
| `PUT /system/role` | `PUT /api/system/roles/{id}` | 修改角色 |
| `DELETE /system/role/{id}` | `DELETE /api/system/roles/{id}` | 删除角色 |
| `GET /system/menus` | `GET /api/system/menus` | 菜单列表 |
| `POST /system/menus` | `POST /api/system/menus` | 创建菜单 |
| `PUT /system/menus/{id}` | `PUT /api/system/menus/{id}` | 修改菜单 |
| `DELETE /system/menus/{id}` | `DELETE /api/system/menus/{id}` | 删除菜单 |
