# IAM 业务迁移文档

本文档说明从旧项目 `DocBase-Back-End`（AgileBoot 单体）到 `iam-service` 的 IAM
业务迁移细节。

## 1. 旧项目 IAM 结构分析

旧项目基于 AgileBoot 框架，采用以下结构：

- **认证**：`LoginController` + `LoginService` + `TokenService` + `JwtAuthenticationTokenFilter`
- **用户/角色/菜单**：DDD 分层（`UserApplicationService` → `SysUserMapper` → `SysUserEntity`）
- **JWT**：对称签名（HS256），Token 仅携带 UUID，完整身份存 Redis
- **密码**：BCrypt 存储，RSA 加密传输
- **权限**：`sys_menu.permission` 字段，登录时加载到 Redis

## 2. 新旧模型映射

### 表映射

| 旧表 | 新表 | 变化 |
| --- | --- | --- |
| `sys_user` | `sys_user` | 移除 `role_id` 单角色字段，改为 `sys_user_role` 多角色 |
| `sys_role` | `sys_role` | 基本一致，移除 `dept_id_set` |
| `sys_menu` | `sys_menu` | 基本一致，`sort` → `sort_num`，移除 `rank` |
| `sys_role_menu` | `sys_role_menu` | 一致 |
| — | `sys_user_role` | 新增，支持用户多角色 |

### 字段保留与删除

**保留字段**：`username`, `nickname`, `password`, `status`, `deleted`, `sort`,
`parent_id`, `menu_type`, `permission`, `create_time`, `update_time`, `role_key`,
`role_name`, `email`, `phone_number`, `is_admin`。

**删除字段及原因**：

- `sys_user.role_id`：改为独立关联表，支持多角色
- `sys_user.post_id` / `sys_user.dept_id`：本期不迁移部门和岗位
- `sys_role.dept_id_set`：依赖部门模型，本期不迁移
- `sys_menu.rank`：前端路由排序使用 `sort_num` 替代
- `sys_menu.meta_info` 中的 `auths`/`roles`：本期权限模型简化为权限字符串

### 密码兼容策略

旧项目密码使用 BCrypt 存储，新项目继续使用 BCrypt。旧密码 Hash 可直接复用
（BCrypt 算法本身向后兼容）。无需渐进升级方案。

旧项目使用 RSA 加密传输密码（前端 RSA 公钥加密，后端私钥解密）。新项目
**不再沿用 RSA 传输加密**，改为依赖 HTTPS 传输层保护。这是合理的简化：
本地开发和生产都应使用 HTTPS。

### 角色和权限映射

旧项目：用户 → 单角色（`sys_user.role_id`）→ 菜单 → `permission` 字段。
新项目：用户 → 多角色（`sys_user_role`）→ 菜单 → `permission` 字段。

权限字符串语义保持不变，例如 `system:user:list`、`system:role:create`。

### 菜单树迁移方式

旧项目通过 `MenuApplicationService.getRouterTree()` 返回前端路由结构。
新项目通过 `GET /api/system/menus/tree` 和 `GET /api/auth/menus` 返回菜单树。
菜单数据通过 `parent_id` 自关联构建树形结构。

### 用户 ID 保持

新项目的 `sys_user.user_id` 使用自增 BIGINT，与旧项目保持一致。
正式导入数据时可保留旧 ID（通过显式设置 `user_id`）。

## 3. JWT 与 Redis 会话设计

### 旧项目设计（对称 JWT）

- JWT 使用 HS256 对称签名
- Token 仅携带 UUID（`login_user_key`）
- 完整身份（用户、角色、权限）存 Redis `login_tokens:`，TTL 30 分钟
- 无 Refresh Token，Session 滑动刷新

### 新项目设计（非对称 JWT）

- JWT 使用 RS256 非对称签名（2048-bit RSA）
- **iam-service 持有私钥**，负责签发
- **Gateway 和业务服务持有公钥**，负责验签
- Access Token 有效期 30 分钟，Refresh Token 有效期 7 天
- Token 携带 `sub`(userId), `username`, `token_type`, `jti`, `iss`, `exp`

### Redis Key 设计

| Key 模式 | 用途 | TTL |
| --- | --- | --- |
| `docbase:iam:token:refresh:{jti}` | Refresh Token 状态（userId:sessionVersion） | 7 天 |
| `docbase:iam:token:session:{userId}` | 用户会话版本（密码修改时 bump） | 30 天 |
| `docbase:iam:token:auth:{userId}` | 授权版本（注销/禁用/密码修改时 bump，用于 Access Token 失效） | 30 天 |
| `docbase:iam:permission:{userId}` | 权限集合缓存 | 1 小时 |

### 会话失效控制

- **密码修改**：`bumpAuthVersion(userId)` + `bumpSessionVersion(userId)` → Access Token 和 Refresh Token 立即失效
- **用户禁用**：`changeStatus(userId, 0)` → bump 授权版本和会话版本
- **注销**：撤销当前 refreshToken + bump 授权版本和会话版本
- **Refresh Token 轮换**：使用 Lua 脚本原子消费旧 token 并存储新 token（防止并发重放）
- **Access Token 验证**：每次请求检查 token 中的 `auth_version` 是否与 Redis 中的当前版本一致

### 安全修复记录

1. **Refresh Token 篡改防护**：刷新时完整验证 RSA 签名、issuer、expiry、token_type=refresh，并验证 Redis 中的 userId 与 JWT sub 一致
2. **会话版本读取修复**：将 `sessionVersion()` 拆分为 `getSessionVersion()`（只读）、`initSessionVersion()`（初始化）、`bumpSessionVersion()`（递增）三个明确操作
3. **权限声明**：Access Token 包含 `permissions` Claim，超级管理员获得 `admin:all` 权限
4. **原子轮换**：使用 Redis Lua 脚本实现 Refresh Token 的原子消费和替换
5. **权限映射**：提供旧权限字符串到新字符串的映射（如 `system:user:add` → `system:user:create`）

## 4. 数据导入顺序

正式导入旧数据时，按以下顺序执行：

1. `sys_menu`（菜单）
2. `sys_role`（角色）
3. `sys_role_menu`（角色菜单关联）
4. `sys_user`（用户）
5. `sys_user_role`（用户角色关联）

## 5. 回滚方案

- 数据库变更通过 Flyway 管理，可使用 `flyway undo`（需 Pro 版本）或手动回滚脚本
- 代码变更通过 Git 管理，可回退到迁移前 commit
- 新旧系统并行运行期间，旧项目保持只读

## 6. 数据校验 SQL

```sql
-- 检查用户总数
SELECT COUNT(*) FROM sys_user WHERE deleted = 0;

-- 检查角色总数
SELECT COUNT(*) FROM sys_role WHERE deleted = 0;

-- 检查菜单总数
SELECT COUNT(*) FROM sys_menu WHERE deleted = 0;

-- 检查用户角色关联完整性（所有关联的角色都存在）
SELECT ur.* FROM sys_user_role ur
LEFT JOIN sys_role r ON ur.role_id = r.role_id
WHERE r.role_id IS NULL OR r.deleted = 1;

-- 检查角色菜单关联完整性
SELECT rm.* FROM sys_role_menu rm
LEFT JOIN sys_menu m ON rm.menu_id = m.menu_id
WHERE m.menu_id IS NULL OR m.deleted = 1;
```

## 7. 正式导入前需确认事项

- [ ] 旧数据库已备份
- [ ] 密码 Hash 算法确认为 BCrypt（`$2a$` / `$2b$` / `$2y$` 前缀）
- [ ] 用户状态值映射确认（旧：1=正常/2=停用/3=冻结 → 新：1=正常/0=停用）
- [ ] 角色 `role_key` 唯一性确认
- [ ] 菜单 `parent_id` 引用完整性确认
