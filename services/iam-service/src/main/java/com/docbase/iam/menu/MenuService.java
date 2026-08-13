package com.docbase.iam.menu;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.PermissionMapping;
import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.dto.ChangeMenuStatusRequest;
import com.docbase.iam.menu.dto.CreateMenuRequest;
import com.docbase.iam.menu.dto.MenuWriteRequest;
import com.docbase.iam.menu.dto.UpdateMenuRequest;
import com.docbase.iam.menu.mapper.MenuOwnerMutexMapper;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.menu.mapper.SysMenuOwnerRoleMapper;
import com.docbase.iam.role.domain.SysRoleMenu;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.IamUserPrincipal;
import com.docbase.iam.security.TokenStore;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 菜单管理服务。
 *
 * <p>安全模型（全部在 Service 层执行，不依赖 @PreAuthorize / 前端）：
 * <ul>
 *   <li>系统保留菜单（is_system=1）：仅超级管理员可修改/停用；任何人都不可删除。</li>
 *   <li>admin:all 防提权：create/update 时 permission 归一化后不得为 admin:all。</li>
 *   <li>权限子集：非超级管理员只能使用自身已拥有的 permission。</li>
 *   <li>父子关系：父节点必须存在、未删除、启用、非按钮；禁止循环引用与超深树。</li>
 *   <li>写操作后精确失效受影响用户（关联该菜单的角色下的用户），非全局 SCAN。</li>
 * </ul>
 */
@Service
public class MenuService {

    /** 菜单树最大深度（根到叶子的边数），超过则拒绝创建/移动。 */
    private static final int MAX_DEPTH = 6;

    /** 单个菜单的 owner 角色数量上限，防止超大批量替换拖垮数据库。 */
    private static final int MAX_OWNER_ROLES = 100;

    /** 受保护资源的"不存在"与"受保护"统一返回此码，避免枚举。 */
    private static final String MENU_NOT_FOUND = "MENU_NOT_FOUND";

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuOwnerRoleMapper ownerRoleMapper;
    private final MenuOwnerMutexMapper ownerMutexMapper;
    private final OwnerLifecycleLockHook ownerLockHook;
    private final TokenStore tokenStore;

    public MenuService(SysMenuMapper menuMapper, SysRoleMenuMapper roleMenuMapper,
                       SysMenuOwnerRoleMapper ownerRoleMapper, MenuOwnerMutexMapper ownerMutexMapper,
                       OwnerLifecycleLockHook ownerLockHook, TokenStore tokenStore) {
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.ownerRoleMapper = ownerRoleMapper;
        this.ownerMutexMapper = ownerMutexMapper;
        this.ownerLockHook = ownerLockHook;
        this.tokenStore = tokenStore;
    }

    /* ========================= 只读查询（无需调用者校验） ========================= */

    public SysMenu getById(Long menuId) {
        return menuMapper.selectById(menuId);
    }

    public List<SysMenu> listAll() {
        return menuMapper.selectList(new QueryWrapper<SysMenu>().orderByAsc("parent_id", "sort_num"));
    }

    public List<MenuNode> tree() {
        List<SysMenu> menus = listAll();
        return buildTree(menus);
    }

    /* ========================= 写操作（含调用者授权校验） ========================= */

    @Transactional
    public Long create(CreateMenuRequest request) {
        IamUserPrincipal caller = currentPrincipal();

        // 字段不变量 + 节点类型规则（纯内存校验）
        validateMenuInput(request);
        // [C][P0] owner 继承是 owner 表写入口：锁必须先于任何数据库读取（MySQL
        // REPEATABLE READ 下第一个普通 SELECT 建立一致性读快照；锁后的第一个普通
        // SELECT 才建立快照，保证父节点 owner 的继承读取看到拿到锁时刻的最新数据）。
        // 非超级管理员分支会向 sys_menu_owner_role 写入继承行，故整个方法统一持锁。
        lockOwnerGuardRow();
        // 父节点合法性 + 循环/深度
        validateParent(request.parentId(), null);
        // [P0-1] 资源级授权：非超级管理员必须持有目标父目录的资源权限，
        // 防止跨业务树注入（如仅拥有 system:menu:* 的管理员把节点创建到 Knowledge/Ingest 根目录下）。
        // 根节点（parentId=0）无所属子树，单独允许。
        assertCanWriteUnder(caller, request.parentId());
        // 权限防提权 + 子集
        assertPermissionWritable(caller, request.permission());

        SysMenu menu = new SysMenu();
        menu.setParentId(request.parentId());
        menu.setMenuName(request.menuName().trim());
        menu.setMenuType(request.menuType());
        menu.setRouterName(request.routerName() != null ? request.routerName().trim() : null);
        menu.setPath(request.path() != null ? request.path().trim() : null);
        menu.setPermission(blankToNull(request.permission()));
        menu.setMetaInfo(request.metaInfo() != null ? request.metaInfo().trim() : null);
        menu.setIsButton(request.isButton() != null ? request.isButton() : 0);
        menu.setSortNum(request.sortNum() != null ? request.sortNum() : 0);
        menu.setStatus(request.status() != null ? request.status() : 1);
        // API 创建的菜单一律不是系统保留菜单，强制为 0，不可伪造。
        menu.setIsSystem(0);
        menu.setRemark(request.remark());

        if (caller.admin()) {
            // 超级管理员无需建立持久化角色归属：资源级校验对其全部跳过，且可直接管理任何节点。
            // 超级管理员创建的菜单无 owner 记录，仅由超级管理员管理——所有权归"系统"，
            // 不绑定到任何业务角色，避免把系统菜单的管理权扩散到普通角色。
            menuMapper.insert(menu);
            return menu.getMenuId();
        }

        // [P0] 非超级管理员创建菜单时，必须在同一事务内建立持久化的<b>资源归属</b>。
        // changeStatus() 对所有非超级管理员依赖 assertOwnsMenuViaRole()（调用者角色→菜单的
        // 所有者关联），若创建时不建立该关联，创建者能建/改/删该菜单，却无法停用或重新启用（自锁）。
        //
        // 关键：归属写入独立的 sys_menu_owner_role，<b>绝不</b>写入 sys_role_menu。
        // sys_role_menu 直接参与用户权限集计算，若把继承角色关联到 sys_role_menu，会让
        // 该角色的所有成员都被动获得新菜单的 permission（权限扩散 P0）。
        // 例如：调用者通过角色 B 拥有 knowledge:x，同时角色 A 关联父目录；若把 A 写入
        // sys_role_menu，A 的其他成员会越权获得 knowledge:x。
        // 归属表仅用于资源级授权校验，不参与权限计算，因此不会扩散权限。
        //
        // 不区分新节点的 permission 是否为空，一律从父节点的所有者角色中继承调用者持有的
        // 有效角色，写入归属表。顶级节点（parentId 为 null/0）无父可继承，非超级管理员不得
        // 创建——避免产生无法确定归属的孤儿根；当前 DTO 未携带 ownerRoleId，故顶级节点仅
        // 超级管理员可创建。
        if (request.parentId() == null || request.parentId() == 0L) {
            throw new BusinessException("MENU_OWNERSHIP_REQUIRED",
                    "only super admin can create a top-level menu");
        }
        // 锁已在方法开头获取（见 create 开头注释）：owner 继承的 read-check-write
        // 与角色删除/停用、owner 全量替换串行化，避免"角色清理后又插入指向已删除
        // 角色的 owner 行"。
        Set<Long> roleIds = ownerRoleMapper.selectOwnerRoleIdsLinkedToMenu(caller.userId(),
                request.parentId());
        if (roleIds == null || roleIds.isEmpty()) {
            // 父节点无调用者可继承的所有者角色——无法为新节点确定归属，拒绝。
            throw new BusinessException("MENU_OWNERSHIP_REQUIRED",
                    "cannot determine ownership for the new menu");
        }
        menuMapper.insert(menu);
        for (Long roleId : roleIds) {
            ownerRoleMapper.insert(new com.docbase.iam.menu.domain.SysMenuOwnerRole(
                    menu.getMenuId(), roleId));
        }
        return menu.getMenuId();
    }

    @Transactional
    public void update(Long menuId, UpdateMenuRequest request) {
        IamUserPrincipal caller = currentPrincipal();

        // [P1 纵深] 本方法依赖 owner 授权（assertOwnsMenuViaRole）且可能被并发 owner
        // 转让撤销归属：锁必须先于任何数据库读取，锁内重新校验，避免旧 owner 在归属
        // 已被撤销后仍继续写菜单。
        lockOwnerGuardRow();
        SysMenu existing = menuMapper.selectById(menuId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
        // 系统保护：非超级管理员禁止操作系统保留菜单。
        assertCanMutate(caller, existing);
        // [P0] 资源归属主校验：调用者必须是目标菜单的 owner。
        // 通过持久化的"调用者角色→菜单"所有者关联判定，与菜单当前 permission 及启用状态解耦。
        assertOwnsMenuViaRole(caller, existing.getMenuId());
        // 纵深防御：校验旧权限边界（目标自身 permission + 全部未删除后代的 permission），
        // 与 owner 校验互补——owner 身份不能绕过权限子集约束。
        assertHoldsMenuPermission(caller, existing);

        validateMenuInput(request);
        validateParent(request.parentId(), menuId);
        // [P0] 真正移动节点（parentId 发生变化）时，额外要求调用者持有目标父目录的归属与权限，
        // 防止把节点移动到无权管理的业务子树（如 ingest/chat 目录）。根节点单独允许。
        // 普通更新/改名（parentId 不变）不要求目标父目录 owner——兼容历史升级数据中
        // "拥有子菜单但未关联父目录"的合法管理员，避免对未移动的操作错误拒绝。
        if (!Objects.equals(existing.getParentId(), request.parentId())) {
            assertCanWriteUnder(caller, request.parentId());
        }
        assertPermissionWritable(caller, request.permission());

        existing.setParentId(request.parentId());
        existing.setMenuName(request.menuName().trim());
        existing.setMenuType(request.menuType());
        existing.setRouterName(request.routerName() != null ? request.routerName().trim() : null);
        existing.setPath(request.path() != null ? request.path().trim() : null);
        existing.setPermission(blankToNull(request.permission()));
        existing.setMetaInfo(request.metaInfo() != null ? request.metaInfo().trim() : null);
        existing.setIsButton(request.isButton());
        existing.setSortNum(request.sortNum());
        // status 不在此处更新——状态变更必须走 changeStatus()（PUT /{menuId}/status），
        // 以便统一执行"停用含启用子节点的目录"等状态专用校验，避免普通更新接口绕过。
        existing.setRemark(request.remark());
        // is_system 不可通过接口篡改。
        menuMapper.updateById(existing);

        invalidateUsers(affectedUserIds(menuId));
    }

    @Transactional
    public void delete(Long menuId) {
        IamUserPrincipal caller = currentPrincipal();

        // [C][P0] 删除菜单会清理 sys_menu_owner_role：必须进入 owner 生命周期互斥协议，
        // 且锁必须先于任何数据库读取——否则与 replaceOwners 并发时，转让可能在菜单
        // 软删除后重新插入 owner，留下指向已删除菜单的孤儿归属。锁内重新查询菜单。
        lockOwnerGuardRow();
        SysMenu existing = menuMapper.selectById(menuId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
        // 系统保留菜单任何人都不可删除；为防枚举统一返回 MENU_NOT_FOUND。
        if (existing.getIsSystem() != null && existing.getIsSystem() == 1) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
        // [P0] 资源归属主校验：非超级管理员只能删除自己"负责"的菜单。
        assertOwnsMenuViaRole(caller, existing.getMenuId());
        // 纵深防御：校验旧权限边界，与 owner 校验互补。
        assertHoldsMenuPermission(caller, existing);

        // 有子节点则拒绝删除，避免产生孤儿节点。
        if (menuMapper.countChildren(menuId) > 0) {
            throw new BusinessException("MENU_HAS_CHILDREN", "cannot delete menu with children");
        }

        // 先捕获受影响用户，再删除关联，保证失效通知不丢（全或无语义）。
        List<Long> affectedUserIds = new ArrayList<>(affectedUserIds(menuId));

        roleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().eq("menu_id", menuId));
        // 同步清理该菜单的资源归属关系。菜单已不存在，其 owner 记录必须清除，
        // 否则归属的孤儿行会残留在表中（虽不影响权限计算，但污染数据）。
        ownerRoleMapper.delete(
                new QueryWrapper<com.docbase.iam.menu.domain.SysMenuOwnerRole>().eq("menu_id", menuId));
        menuMapper.deleteById(menuId);

        invalidateUsers(affectedUserIds);
    }

    @Transactional
    public void changeStatus(Long menuId, ChangeMenuStatusRequest request) {
        IamUserPrincipal caller = currentPrincipal();

        // [P1 纵深] 依赖 owner 授权（assertOwnsMenuViaRole）：锁先于任何数据库读取，
        // 锁内重新校验，避免归属被撤销后旧请求仍继续变更菜单状态。
        lockOwnerGuardRow();
        SysMenu existing = menuMapper.selectById(menuId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
        // 非超级管理员禁止停用/启用系统保留菜单。
        assertCanMutate(caller, existing);
        // 资源级授权：非超级管理员只能停用/启用自己"负责"的菜单。
        // 此处不能复用 assertHoldsMenuPermission——它依赖菜单当前 permission 归一化后
        // 是否出现在调用者的有效权限集中，而有效权限集只包含 status=1 的菜单权限。
        // 若管理员停用自己负责的唯一权限菜单，Token 失效后失去该 permission，便无法再通过
        // 权限集校验来重新启用（自锁）。改用持久化的"调用者角色→菜单"关联来判定归属，
        // 与菜单当前启用状态解耦：只要调用者仍持有关联该菜单的角色，即可变更其状态。
        assertOwnsMenuViaRole(caller, existing.getMenuId());

        Integer newStatus = request.status();
        if (existing.getStatus() != null && existing.getStatus().equals(newStatus)) {
            throw new BusinessException(
                    newStatus == 0 ? "MENU_ALREADY_DISABLED" : "MENU_ALREADY_ENABLED",
                    "menu already in target status");
        }

        // 停用目录/菜单时，若其下仍有启用的子节点，禁止停用，避免把启用的子节点
        // 提升为孤儿根（selectMenusByUserId 只过滤 status=1，父节点停用后其启用子节点
        // 会作为根节点暴露给前端，造成结构混乱与潜在的权限错乱）。
        if (newStatus == 0 && menuMapper.countEnabledChildren(menuId) > 0) {
            throw new BusinessException("MENU_HAS_ENABLED_CHILDREN",
                    "cannot disable a menu that has enabled children");
        }

        // 启用节点时，若其父节点已被停用（或不存在/已删），禁止启用，避免在已停用
        // 父节点下重新启用子节点而成为可见的孤儿根（操作顺序：停用子→停用父→启用子）。
        if (newStatus == 1) {
            assertParentActive(existing.getParentId());
        }

        existing.setStatus(newStatus);
        menuMapper.updateById(existing);

        // 启用/停用都会改变关联用户的有效权限集，统一失效其缓存与令牌。
        invalidateUsers(affectedUserIds(menuId));
    }

    /* ========================= owner 查询 / 全量替换（仅超级管理员） ========================= */

    /**
     * 查询指定菜单的有效所有者角色 ID 集合（去重）。
     *
     * <p>仅超级管理员可调用（Controller 以 admin:all 收敛，此处 Service 层再兜底校验）。
     * 返回的是<b>有效</b> owner（角色 status=1 且 deleted=0）；已停用/已删除角色的
     * 残留归属行不返回——它们不再是有效 owner。
     */
    public List<Long> getOwners(Long menuId) {
        IamUserPrincipal caller = currentPrincipal();
        assertOwnerAdmin(caller);

        SysMenu existing = menuMapper.selectById(menuId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
        Set<Long> roleIds = ownerRoleMapper.selectOwnerRoleIds(menuId);
        return roleIds == null ? new ArrayList<>() : new ArrayList<>(roleIds);
    }

    /**
     * 全量替换（转让）指定菜单的有效 owner 角色集合。
     *
     * <p>仅超级管理员可调用。语义：
     * <ul>
     *   <li>非空列表：完整替换为这些角色（转让语义）；</li>
     *   <li>空列表：超级管理员明确选择"系统托管"——该菜单不再归属任何普通角色，
     *       仅超级管理员可管理。绝不被解释为"清空权限"或"拒绝管理"。</li>
     * </ul>
     *
     * <p>校验（全部满足才执行，任一失败整批拒绝）：
     * <ol>
     *   <li>菜单存在、未删除；</li>
     *   <li>roleIds 去重、非空元素、正安全整数、数量不超过 {@link #MAX_OWNER_ROLES}；</li>
     *   <li>所有角色必须存在、启用、未删除。</li>
     * </ol>
     *
     * <p>全量替换在同一事务内完成，并持有 owner 生命周期互斥锁直到提交——与角色删除/
     * 停用、菜单 owner 继承串行化，避免"检查时有其它 owner，提交后却没有"的竞态。
     *
     * <p><b>安全边界</b>：本方法只写 sys_menu_owner_role（资源归属），<b>绝不</b>写
     * sys_role_menu、绝不授予任何 permission——因此不会给角色成员新增任何权限。
     */
    @Transactional
    public void replaceOwners(Long menuId, List<Long> roleIds) {
        IamUserPrincipal caller = currentPrincipal();
        assertOwnerAdmin(caller);

        // [C][P0] 锁必须先于任何数据库读取：MySQL REPEATABLE READ 下，先查询菜单/
        // 校验角色再加锁会基于旧快照（等待锁期间其它事务可能已删除/停用候选角色，
        // 或已软删除菜单）。lockGuardRow() 是当前读，锁后的第一个普通 SELECT 才建立
        // 一致性读快照——因此菜单存在性、角色有效性、归属替换写入全部在锁内基于
        // 拿到锁时刻的最新数据重新执行。
        lockOwnerGuardRow();
        SysMenu existing = menuMapper.selectById(menuId);
        if (existing == null || isDeleted(existing)) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }

        List<Long> normalized = normalizeOwnerRoleIds(roleIds);
        validateOwnerRolesExist(normalized);

        // 全量替换（同一事务）：先清空该菜单的全部归属行，再写入新集合。
        ownerRoleMapper.delete(
                new QueryWrapper<com.docbase.iam.menu.domain.SysMenuOwnerRole>()
                        .eq("menu_id", menuId));
        for (Long roleId : normalized) {
            ownerRoleMapper.insert(new com.docbase.iam.menu.domain.SysMenuOwnerRole(menuId, roleId));
        }
        // owner 变化只影响"谁能管理菜单"（实时查询），不参与权限集计算，故无需失效任何
        // 用户的权限缓存/令牌；也不得触碰 sys_role_menu。
    }

    /* ========================= 输入校验 ========================= */

    /**
     * 校验字段不变量与节点类型规则。
     *
     * <p>在 create/update 的调用者校验之后执行，确保进入领域对象的数据合法。
     */
    private void validateMenuInput(MenuWriteRequest request) {
        int type = request.menuType();

        // 按钮节点：permission 必填；routerName/path 必须为空。
        if (type == 3) {
            if (request.permission() == null || request.permission().isBlank()) {
                throw new BusinessException("MENU_BUTTON_NEEDS_PERMISSION",
                        "button menu must have a non-empty permission");
            }
            if (request.routerName() != null && !request.routerName().isBlank()) {
                throw new BusinessException("MENU_BUTTON_NO_ROUTER",
                        "button menu must not have a routerName");
            }
            if (request.path() != null && !request.path().isBlank()) {
                throw new BusinessException("MENU_BUTTON_NO_PATH",
                        "button menu must not have a path");
            }
            if (request.isButton() != null && request.isButton() != 1) {
                throw new BusinessException("MENU_BUTTON_FLAG_MISMATCH",
                        "button menu must have isButton=1");
            }
        } else {
            // 目录/菜单节点：routerName/path 必填；isButton 必须为 0。
            if (request.routerName() == null || request.routerName().isBlank()) {
                throw new BusinessException("MENU_NEEDS_ROUTER",
                        "menu or directory must have a non-empty routerName");
            }
            if (!request.routerName().matches("^[A-Za-z][A-Za-z0-9_-]{0,127}$")) {
                throw new BusinessException("MENU_ROUTER_INVALID",
                        "routerName must start with a letter and contain only letters, digits, underscore or hyphen");
            }
            if (request.path() == null || request.path().isBlank()) {
                throw new BusinessException("MENU_NEEDS_PATH",
                        "menu or directory must have a non-empty path");
            }
            if (!request.path().matches("^(/[A-Za-z0-9_-]+)+$")) {
                throw new BusinessException("MENU_PATH_INVALID",
                        "path must start with '/' and contain only letters, digits, underscore or hyphen segments");
            }
            if (request.isButton() != null && request.isButton() != 0) {
                throw new BusinessException("MENU_NO_BUTTON_FLAG",
                        "non-button menu must have isButton=0");
            }
        }

        // permission 格式（非空时）。以 trim 后的值校验，与存储（blankToNull）一致，
        // 避免前后空白绕过格式与语义检查。
        String perm = request.permission() == null ? null : request.permission().trim();
        if (perm != null && !perm.isEmpty()) {
            if (!perm.matches("^[a-z0-9:._-]{1,128}$")) {
                throw new BusinessException("MENU_PERMISSION_INVALID",
                        "permission must contain only lowercase letters, digits, colon, dot, underscore or hyphen");
            }
        }

        // metaInfo 必须是合法 JSON 对象（非空时）。
        if (request.metaInfo() != null && !request.metaInfo().isBlank()) {
            String trimmed = request.metaInfo().trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw new BusinessException("MENU_METAINFO_INVALID",
                        "metaInfo must be a valid JSON object");
            }
            // 简易结构校验：大括号必须匹配且可被 JSON 解析。
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(trimmed);
                if (!node.isObject()) {
                    throw new BusinessException("MENU_METAINFO_INVALID",
                            "metaInfo must be a valid JSON object");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new BusinessException("MENU_METAINFO_INVALID",
                        "metaInfo must be a valid JSON object");
            }
        }
    }

    /**
     * 校验父节点合法性：存在、未删除、启用、非按钮；并校验循环引用与树深度。
     *
     * <p>{@code currentMenuId} 为 null 表示 create，非 null 表示 update（需排除自身）。
     */
    private void validateParent(Long parentId, Long currentMenuId) {
        if (parentId == null || parentId == 0L) {
            return; // 根节点，无需校验父
        }
        if (currentMenuId != null && parentId.equals(currentMenuId)) {
            throw new BusinessException("MENU_CIRCULAR_REF", "menu cannot be its own parent");
        }

        SysMenu parent = menuMapper.selectById(parentId);
        if (parent == null || isDeleted(parent)) {
            throw new BusinessException("MENU_PARENT_NOT_FOUND", "parent menu not found");
        }
        if (parent.getStatus() == null || parent.getStatus() != 1) {
            throw new BusinessException("MENU_PARENT_DISABLED", "parent menu is disabled");
        }
        if (parent.getIsButton() != null && parent.getIsButton() == 1) {
            throw new BusinessException("MENU_PARENT_IS_BUTTON", "button menu cannot be a parent");
        }

        // 循环引用：parentId 不能是 currentMenuId 的任意后代（否则移动后成环）。
        if (currentMenuId != null && isDescendant(currentMenuId, parentId)) {
            throw new BusinessException("MENU_CIRCULAR_REF",
                    "cannot move a menu to its own descendant");
        }

        // 深度校验：parentId 到根的深度 + 1（新节点/当前层）+ 被移动子树深度 不超上限。
        // create 时 currentMenuId 为 null，subtreeDepth 取 0，仅校验 parent 链深度 + 新节点；
        // update 时额外加上被移动子树的深度，防止移动后超深。
        int parentDepth = depthToRoot(parentId);
        int subtreeDepth = (currentMenuId == null) ? 0 : depthFromRoot(currentMenuId);
        if (parentDepth + 1 + subtreeDepth > MAX_DEPTH) {
            throw new BusinessException("MENU_DEPTH_EXCEEDED",
                    "menu tree depth must not exceed " + MAX_DEPTH);
        }
    }

    /** 判断 {@code candidateAncestor} 是否是 {@code menuId} 的后代（用于循环校验）。 */
    private boolean isDescendant(Long candidateAncestor, Long menuId) {
        // 从 candidateAncestor 向下 BFS，看能否到达 menuId。
        Set<Long> visited = new HashSet<>();
        List<Long> queue = new ArrayList<>(menuMapper.selectChildIds(candidateAncestor));
        while (!queue.isEmpty()) {
            Long current = queue.remove(0);
            if (current.equals(menuId)) {
                return true;
            }
            if (visited.add(current)) {
                queue.addAll(menuMapper.selectChildIds(current));
            }
        }
        return false;
    }

    /** 从某节点向上到根的深度（边数）。 */
    private int depthToRoot(Long menuId) {
        int depth = 0;
        Long current = menuId;
        Set<Long> visited = new HashSet<>();
        while (current != null && current != 0L && visited.add(current)) {
            SysMenu node = menuMapper.selectById(current);
            if (node == null || node.getParentId() == null || node.getParentId() == 0L) {
                break;
            }
            current = node.getParentId();
            depth++;
            if (depth > MAX_DEPTH) {
                break; // 防御异常数据
            }
        }
        return depth;
    }

    /** 从某节点向下到叶子的最大深度（边数）。 */
    private int depthFromRoot(Long menuId) {
        int max = 0;
        for (Long child : menuMapper.selectChildIds(menuId)) {
            max = Math.max(max, 1 + depthFromRoot(child));
            if (max > MAX_DEPTH) {
                return max;
            }
        }
        return max;
    }

    /* ========================= 授权校验 ========================= */

    /**
     * 非超级管理员禁止操作系统保留菜单。
     *
     * <p>为防信息泄露，对"受保护"统一返回 MENU_NOT_FOUND，使其无法区分
     * "菜单不存在"与"菜单存在但受系统保护"。
     */
    private void assertCanMutate(IamUserPrincipal caller, SysMenu target) {
        if (caller.admin()) {
            return;
        }
        if (target.getIsSystem() != null && target.getIsSystem() == 1) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
    }

    /**
     * 校验 permission 可被当前调用者写入：
     * <ul>
     *   <li>归一化后不得为 admin:all（防提权）</li>
     *   <li>非超级管理员只能使用自身已拥有的 permission（权限子集）</li>
     * </ul>
     * 空 permission 允许（目录/菜单可无权限；按钮已在 validateMenuInput 要求非空）。
     */
    private void assertPermissionWritable(IamUserPrincipal caller, String permission) {
        if (permission == null || permission.isBlank()) {
            return;
        }
        String normalized = PermissionMapping.mapToNew(permission.trim());
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        if (IamUserPrincipal.ADMIN_ALL_PERMISSION.equals(normalized)) {
            throw new BusinessException("PERMISSION_NOT_GRANTABLE",
                    "admin:all cannot be set as a menu permission");
        }
        if (!caller.admin() && !caller.hasPermission(normalized)) {
            throw new BusinessException("PERMISSION_NOT_SUBSET",
                    "cannot use a permission the caller does not have: " + normalized);
        }
    }

    /**
     * 资源级授权：非超级管理员只能修改/停用/删除"自己负责"的菜单。
     *
     * <p>与 {@link #assertPermissionWritable} 校验"新 permission 是否越权"形成互补：
     * <ul>
     *   <li>新权限子集校验：防止通过菜单取得自己没有的权限（防提权）</li>
     *   <li>本方法（资源级）：防止越权操作系统保留菜单以外的业务菜单
     *       （如 knowledge_admin 清空/停用/删除 ingest 菜单）</li>
     * </ul>
     * <p>菜单的"归属边界"由目标自身 permission（非空时）与<b>全部</b>未删除后代的 permission
     * 共同决定——操作会影响整棵子树，故调用者必须持有该边界中的每一个权限。
     * 例如 AiChat 根菜单具有 ai:chat:list、子节点具有 ai:chat:query，
     * 仅持有 ai:chat:list 的调用者不得移动/修改整个 AiChat 子树。
     *
     * <p>当边界为空（目标无自身 permission 且无可校验的后代）时，无法通过权限判定归属，
     * 改用持久化的"调用者角色→该菜单"关联来判定；无关联则拒绝——空边界不能视为授权成功
     * （否则任何拥有菜单管理接口权限的普通管理员都能修改/删除该节点或在其下创建）。
     * 为防枚举，资源不足时统一返回 MENU_NOT_FOUND。
     */
    private void assertHoldsMenuPermission(IamUserPrincipal caller, SysMenu target) {
        if (caller.admin()) {
            return;
        }
        if (target.getIsSystem() != null && target.getIsSystem() == 1) {
            // 系统保留菜单已由 assertCanMutate 以 MENU_NOT_FOUND 拒绝，此处不应到达；
            // 为防御性起见再次拒绝，保持语义一致。
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
        // 归属边界 = 目标自身 permission（非空时）+ 全部未删除后代的 permission（含停用）。
        Set<String> boundary = new HashSet<>();
        if (target.getPermission() != null && !target.getPermission().isBlank()) {
            boundary.add(target.getPermission());
        }
        boundary.addAll(collectSubtreePermissions(target.getMenuId()));

        if (!boundary.isEmpty()) {
            for (String perm : boundary) {
                String normalized = PermissionMapping.mapToNew(perm == null ? null : perm.trim());
                if (normalized == null || normalized.isBlank()) {
                    continue;
                }
                if (!caller.hasPermission(normalized)) {
                    // 统一返回 MENU_NOT_FOUND，避免泄露"存在但无权操作"的信息。
                    throw new BusinessException(MENU_NOT_FOUND, "menu not found");
                }
            }
            return;
        }
        // 边界为空：无自身 permission 且无可校验后代，改用持久化角色→菜单关联判定归属。
        assertOwnsMenuViaRole(caller, target.getMenuId());
    }

    /**
     * 递归收集指定节点全部后代节点的非空 permission 集合（含直接与间接后代）。
     *
     * <p>结构节点（目录/菜单）的归属即由这些叶子权限共同决定；收集后由
     * {@link #assertHoldsSubtreePermissions} 校验调用者是否全部持有。
     *
     * <p>使用 {@link SysMenuMapper#selectPermissionsByMenuIdsIgnoreStatus} 查询，包含已停用
     * 但未删除的后代。若仅取 status=1 的后代，调用者可能趁高权限后代停用时移动、停用或修改
     * 其父目录（子树校验变松），之后这些后代仍会被重新启用，造成归属校验被绕过。
     * 角色授权校验仍使用 {@code selectPermissionsByMenuIds}（只取启用菜单），二者职责不同。
     */
    private Set<String> collectSubtreePermissions(Long menuId) {
        Set<Long> descendantIds = collectDescendantIds(menuId);
        if (descendantIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> perms = menuMapper.selectPermissionsByMenuIdsIgnoreStatus(descendantIds);
        return perms == null ? new HashSet<>() : perms;
    }

    /** 递归收集指定节点全部后代节点的 ID（不含自身），用于子树权限校验。 */
    private Set<Long> collectDescendantIds(Long menuId) {
        Set<Long> result = new HashSet<>();
        List<Long> queue = new ArrayList<>();
        Set<Long> rootChildren = menuMapper.selectChildIds(menuId);
        if (rootChildren != null) {
            queue.addAll(rootChildren);
        }
        while (!queue.isEmpty()) {
            Long current = queue.remove(0);
            if (result.add(current)) {
                Set<Long> children = menuMapper.selectChildIds(current);
                if (children != null) {
                    queue.addAll(children);
                }
            }
        }
        return result;
    }

    /**
     * 校验父节点仍处于启用且未删除状态。
     *
     * <p>用于启用子节点前：若父节点已被停用，则子节点不得启用，否则会成为可见的孤儿根。
     * 根节点（parentId 为 null/0）无需校验。
     */
    private void assertParentActive(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return; // 根节点，无父可校验
        }
        SysMenu parent = menuMapper.selectById(parentId);
        if (parent == null || isDeleted(parent)) {
            throw new BusinessException("MENU_PARENT_NOT_FOUND", "parent menu not found");
        }
        if (parent.getStatus() == null || parent.getStatus() != 1) {
            throw new BusinessException("MENU_PARENT_DISABLED", "parent menu is disabled");
        }
    }

    /**
     * 校验调用者有权在目标父目录下写入（创建子节点或把节点移动过去）。
     *
     * <p>授权分两层，缺一不可：
     * <ol>
     *   <li><b>资源归属主校验</b>：调用者必须是父目录的 owner（通过持久化的
     *       "调用者角色→父目录"所有者关联判定）。防止非 owner 把节点注入他人业务子树。</li>
     *   <li><b>permission 边界纵深防御</b>：若父目录有非空 permission，调用者须持有该
     *       permission；若父目录是空 permission 的结构节点，调用者须持有其全部后代的
     *       permission（{@link #assertHoldsMenuPermission} 已封装此逻辑）。
     *       这防止仅拥有 system:menu:* 的管理员把节点创建/移动到 Knowledge/Ingest/Chat
     *       等业务根目录下，形成跨业务树注入。</li>
     * </ol>
     * 两层互补：owner 身份不能绕过权限子集约束，permission 持有也不能代替资源归属。
     *
     * <p>根节点（parentId 为 null/0）无所属业务子树，单独允许——创建顶级节点
     * 的权限已由 Controller 的 @PreAuthorize 与本节点的 permission 子集校验覆盖。
     */
    private void assertCanWriteUnder(IamUserPrincipal caller, Long parentId) {
        if (caller.admin()) {
            return;
        }
        if (parentId == null || parentId == 0L) {
            return; // 根节点，无所属子树，不校验父级归属
        }
        SysMenu parent = menuMapper.selectById(parentId);
        // validateParent 已保证父节点存在且未删除，此处 parent 不应为 null；
        // 防御性处理：缺失时按无权访问返回 MENU_NOT_FOUND，避免 NPE。
        if (parent == null || isDeleted(parent)) {
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
        // 资源归属主校验：调用者必须是父目录的 owner。
        assertOwnsMenuViaRole(caller, parentId);
        // 纵深防御：父目录的 permission 边界（含空边界时回落到 owner 校验）。
        assertHoldsMenuPermission(caller, parent);
    }

    /**
     * 通过持久化的"调用者角色→菜单"<b>所有者</b>关联，校验调用者是否负责该菜单。
     *
     * <p>与 {@link #assertHoldsMenuPermission} 不同，本方法不依赖菜单当前 permission
     * 是否出现在调用者的有效权限集中，因此不受菜单启用状态影响。专用于 changeStatus：
     * 避免管理员停用自己负责的唯一权限菜单后，Token 失效导致失去该 permission，
     * 从而无法再通过权限集校验重新启用（自锁）。
     *
     * <p>查询独立的 sys_menu_owner_role（资源归属），而非 sys_role_menu（权限授权）。
     * 二者职责严格分开：写入 sys_role_menu 会直接给角色成员新增 permission，
     * 而归属表仅用于资源级授权校验，不参与权限计算。
     */
    private void assertOwnsMenuViaRole(IamUserPrincipal caller, Long menuId) {
        if (caller.admin()) {
            return;
        }
        int links = ownerRoleMapper.countOwnerLinks(caller.userId(), menuId);
        if (links == 0) {
            // 统一返回 MENU_NOT_FOUND，避免泄露"菜单存在但调用者未关联所有者角色"的信息。
            throw new BusinessException(MENU_NOT_FOUND, "menu not found");
        }
    }

    /**
     * owner 查询/替换仅超级管理员可调用（Controller 已用 admin:all 收敛，此处兜底）。
     *
     * <p>使用显式 OPERATION_FORBIDDEN 而非 MENU_NOT_FOUND：该接口面向超级管理员，
     * 非超级管理员调用属于越权操作，返回明确业务码更利于审计与前端提示。
     */
    private void assertOwnerAdmin(IamUserPrincipal caller) {
        if (!caller.admin()) {
            throw new BusinessException("OPERATION_FORBIDDEN",
                    "only super-admin can manage menu owners");
        }
    }

    /**
     * 归一化 owner roleIds：去重、非空元素、正安全整数、数量上限。
     *
     * <p>任一非法即整批拒绝（不静默丢弃元素）。空列表是合法值——表示"系统托管"。
     */
    private List<Long> normalizeOwnerRoleIds(List<Long> roleIds) {
        if (roleIds == null) {
            throw new BusinessException("MENU_OWNER_ROLES_INVALID", "roleIds must not be null");
        }
        List<Long> deduped = new ArrayList<>(new LinkedHashSet<>(roleIds));
        if (deduped.size() > MAX_OWNER_ROLES) {
            throw new BusinessException("MENU_OWNER_ROLES_INVALID",
                    "roleIds must not exceed " + MAX_OWNER_ROLES + " entries");
        }
        for (Long roleId : deduped) {
            if (roleId == null || roleId <= 0) {
                throw new BusinessException("MENU_OWNER_ROLES_INVALID",
                        "roleIds must contain only positive IDs");
            }
        }
        return deduped;
    }

    /**
     * 校验候选 owner 角色全部存在、启用、未删除。
     *
     * <p>已停用（status=0）或已删除（deleted=1）的角色不能被设为有效 owner——否则会产生
     * 无效 owner，普通管理员依然无法管理该菜单。
     */
    private void validateOwnerRolesExist(List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return;
        }
        int valid = ownerRoleMapper.countValidOwnerRoles(roleIds);
        if (valid != roleIds.size()) {
            throw new BusinessException("ROLE_INVALID",
                    "roleIds contain non-existent, deleted or disabled role");
        }
    }

    /**
     * 在事务内锁定 owner 生命周期守卫行，取得行级写锁。
     *
     * <p>与 {@code MenuOwnerMutexMapper} 语义一致：对守卫行（主键 id=1）执行 UPDATE
     * 取得行级写锁，由数据库持有到当前事务提交/回滚。若守卫行缺失（迁移未执行），
     * 抛出 MIGRATION_MISSING。
     *
     * <p>前置条件：调用方已处于事务中（@Transactional 方法或事务模板内），且本方法
     * 必须是事务内<b>第一个数据库访问</b>（见各调用方 P0 注释：锁先于任何普通 SELECT，
     * 保证一致性读快照在锁后建立）。
     */
    private void lockOwnerGuardRow() {
        // 测试接缝：生产默认 no-op（NoopOwnerLifecycleLockHook），测试用 @MockitoBean
        // 替换为固定交错逻辑（锁前暂停/放行）。
        ownerLockHook.beforeLock();
        int affected = ownerMutexMapper.lockGuardRow();
        if (affected == 0) {
            throw new BusinessException("MIGRATION_MISSING",
                    "sys_menu_owner_mutex guard row missing — run Flyway migration V11");
        }
    }

    /* ========================= 受影响用户失效 ========================= */

    /**
     * 查询关联了指定菜单的全部用户 ID（去重）：通过 sys_role_menu → sys_user_role。
     */
    private List<Long> affectedUserIds(Long menuId) {
        Set<Long> userIds = menuMapper.selectUserIdsByMenuId(menuId);
        return userIds == null ? new ArrayList<>() : new ArrayList<>(userIds);
    }

    /**
     * 失效指定用户的权限缓存与 access token 版本。
     *
     * <p>异常会上抛，由 @Transactional 回滚 DB 变更，绝不吞异常后仍返回成功。
     */
    private void invalidateUsers(List<Long> userIds) {
        for (Long userId : userIds) {
            tokenStore.evictPermissions(userId);
            tokenStore.bumpAuthVersion(userId);
        }
    }

    /* ========================= 工具方法 ========================= */

    private IamUserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof IamUserPrincipal principal)) {
            throw new BusinessException("UNAUTHENTICATED", "no authenticated principal");
        }
        return principal;
    }

    private boolean isDeleted(SysMenu menu) {
        return menu.getDeleted() != null && menu.getDeleted() == 1;
    }

    /** 空白字符串转为 null，便于统一存储与比较。 */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private List<MenuNode> buildTree(List<SysMenu> menus) {
        Map<Long, MenuNode> nodeMap = new HashMap<>();
        for (SysMenu m : menus) {
            nodeMap.put(m.getMenuId(), new MenuNode(
                    m.getMenuId(), m.getParentId(), m.getMenuName(), m.getRouterName(),
                    m.getPath(), m.getPermission(), m.getMenuType(), m.getIsButton(),
                    m.getSortNum(), m.getMetaInfo(),
                    m.getStatus(), m.getIsSystem(),
                    new ArrayList<>()));
        }
        List<MenuNode> roots = new ArrayList<>();
        for (MenuNode node : nodeMap.values()) {
            if (node.parentId() == null || node.parentId() == 0L) {
                roots.add(node);
            } else {
                MenuNode parent = nodeMap.get(node.parentId());
                if (parent != null) {
                    parent.children().add(node);
                } else {
                    roots.add(node); // 孤儿提升为根
                }
            }
        }
        roots.sort(Comparator.comparingInt(a -> a.sortNum() != null ? a.sortNum() : 0));
        return roots;
    }

    /**
     * 菜单树节点（给前端）。
     *
     * <p>在原有 11 个字段末尾追加 {@code status} 与 {@code isSystem}，便于前端标记
     * 禁用/系统保留节点。追加字段对未识别它们的客户端透明（JSON 反序列化忽略未知字段）。
     */
    public record MenuNode(Long menuId, Long parentId, String menuName, String routerName,
                           String path, String permission, Integer menuType, Integer isButton,
                           Integer sortNum, String metaInfo,
                           Integer status, Integer isSystem,
                           List<MenuNode> children) {
    }
}
