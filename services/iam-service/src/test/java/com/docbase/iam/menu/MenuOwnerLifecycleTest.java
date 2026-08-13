package com.docbase.iam.menu;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.domain.SysMenuOwnerRole;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.menu.mapper.SysMenuOwnerRoleMapper;
import com.docbase.iam.role.domain.SysRole;
import com.docbase.iam.role.domain.SysRoleMenu;
import com.docbase.iam.role.mapper.SysRoleMapper;
import com.docbase.iam.role.mapper.SysRoleMenuMapper;
import com.docbase.iam.security.JwtProperties;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.domain.SysUserRole;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.docbase.iam.user.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜单 owner（所有者角色）生命周期的集成测试（Phase 5C1）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>超级管理员能给自己创建的菜单分配 owner（转让），并查询；</li>
 *   <li>普通管理员不能调用 owner 查询/替换接口（admin:all 收敛）；</li>
 *   <li>owner 转让只写 sys_menu_owner_role，不写 sys_role_menu（不扩散 permission）；</li>
 *   <li>转让后新 owner 可管理菜单，旧 owner 不可；</li>
 *   <li>roleIds 非法/不存在/停用角色的校验；</li>
 *   <li>另一个 owner 已停用/已删除时，停用当前唯一有效 owner 仍被拒绝。</li>
 * </ol>
 *
 * <p>通过真实登录获取 token，与 MenuControllerSecurityTest 共享同一 H2 测试库约定，
 * 测试前后显式清理各表，避免跨测试类污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MenuOwnerLifecycleTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SysUserMapper userMapper;
    @Autowired SysUserRoleMapper userRoleMapper;
    @Autowired SysRoleMapper roleMapper;
    @Autowired SysRoleMenuMapper roleMenuMapper;
    @Autowired SysMenuOwnerRoleMapper ownerRoleMapper;
    @Autowired SysMenuMapper menuMapper;
    @Autowired TokenStore tokenStore;
    @Autowired com.docbase.iam.user.mapper.TestUserCleanupMapper testUserCleanupMapper;

    @MockitoBean StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        testUserCleanupMapper.deleteAllPhysically();
        ownerRoleMapper.delete(null);
        roleMenuMapper.delete(null);
        userRoleMapper.delete(null);
        roleMapper.delete(null);
        menuMapper.delete(null);
        SecurityContextHolder.clearContext();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        JwtProperties testJwtProperties() throws IOException {
            KeyPair pair = com.docbase.iam.security.TestKeys.generate();
            Path dir = com.docbase.iam.security.TestKeys.writeTempKeyPair(pair);
            return new JwtProperties(
                    dir.resolve("private.pem").toString(),
                    "docbase-iam", "30m", "7d");
        }
    }

    /* ========================= 辅助方法 ========================= */

    private void stubRedis() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(any(String.class))).thenReturn(1L);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(any())).thenReturn(java.util.Set.of());
    }

    private String loginAs(String username, int isAdmin) throws Exception {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname("U " + username);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(isAdmin);
        user.setDeleted(0);
        userMapper.insert(user);

        stubRedis();
        String json = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    /** 以指定角色登录普通管理员（角色须已关联 system:menu:update 等所需菜单）。 */
    private String loginUserWithRole(String username, Long roleId) throws Exception {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setNickname("U " + username);
        u.setPassword(passwordEncoder.encode("password123"));
        u.setStatus(1);
        u.setIsAdmin(0);
        u.setDeleted(0);
        userMapper.insert(u);
        userRoleMapper.insert(new SysUserRole(u.getUserId(), roleId));

        stubRedis();
        String json = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private Long seedMenu(String permission, String routerName, int type, int isButton, int status, int isSystem) {
        SysMenu m = new SysMenu();
        m.setParentId(0L);
        m.setMenuName("M " + routerName);
        m.setMenuType(type);
        m.setRouterName(routerName);
        m.setPath("/" + routerName.toLowerCase());
        m.setPermission(permission);
        m.setIsButton(isButton);
        m.setStatus(status);
        m.setIsSystem(isSystem);
        m.setSortNum(1);
        m.setMetaInfo("{}");
        m.setDeleted(0);
        menuMapper.insert(m);
        return m.getMenuId();
    }

    private SysRole insertRole(String name, String key, int status, int deleted) {
        SysRole r = new SysRole();
        r.setRoleName(name);
        r.setRoleKey(key);
        r.setStatus(status);
        r.setIsSystem(0);
        r.setDeleted(deleted);
        roleMapper.insert(r);
        return r;
    }

    private void grantMenuPerm(Long roleId, String permission) {
        Long menuId = seedMenu(permission, permission.replace(':', '_') + "_" + roleId, 1, 0, 1, 0);
        roleMenuMapper.insert(new SysRoleMenu(roleId, menuId));
    }

    private String ownersBody(Long... roleIds) {
        StringBuilder sb = new StringBuilder("{\"roleIds\":[");
        for (int i = 0; i < roleIds.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(roleIds[i]);
        }
        return sb.append("]}").toString();
    }

    private long ownerLinkCount(Long menuId, Long roleId) {
        return ownerRoleMapper.selectCount(
                new QueryWrapper<SysMenuOwnerRole>()
                        .eq("menu_id", menuId).eq("role_id", roleId));
    }

    /* ========================= 测试 5：超级管理员分配/查询 owner ========================= */

    @Test
    void 超级管理员可给自己创建的菜单分配并查询owner() throws Exception {
        String token = loginAs("ownersuper", 1);
        Long menuId = seedMenu("", "OwnedRoot", 2, 0, 1, 0);

        SysRole r1 = insertRole("owner_r1", "owner_r1", 1, 0);
        SysRole r2 = insertRole("owner_r2", "owner_r2", 1, 0);

        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(ownersBody(r1.getRoleId(), r2.getRoleId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 查询返回两个有效 owner
        mockMvc.perform(get("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(2));

        assertEquals(1, ownerLinkCount(menuId, r1.getRoleId()));
        assertEquals(1, ownerLinkCount(menuId, r2.getRoleId()));
    }

    /* ========================= 测试 6：普通管理员不能调用 ========================= */

    @Test
    void 普通管理员不能调用owner查询与替换接口() throws Exception {
        // 普通管理员：is_admin=0，持有 system:menu:update 等权限（但无 admin:all）。
        SysRole rm = insertRole("menu_mgr_owner", "menu_mgr_owner", 1, 0);
        grantMenuPerm(rm.getRoleId(), "system:menu:list");
        grantMenuPerm(rm.getRoleId(), "system:menu:update");
        String token = loginUserWithRole("ownernormal", rm.getRoleId());

        Long menuId = seedMenu("", "OwnedNoPerm", 2, 0, 1, 0);

        mockMvc.perform(get("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(ownersBody(rm.getRoleId())))
                .andExpect(status().isForbidden());
    }

    /* ========================= 测试 7：转让不写 sys_role_menu ========================= */

    @Test
    void owner转让不写sys_role_menu_不扩散permission() throws Exception {
        String token = loginAs("ownersuper7", 1);
        Long menuId = seedMenu("knowledge:x:list", "OwnedBiz7", 1, 0, 1, 0);

        SysRole r = insertRole("owner_r7", "owner_r7", 1, 0);
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(ownersBody(r.getRoleId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 归属写入 sys_menu_owner_role
        assertEquals(1, ownerLinkCount(menuId, r.getRoleId()));
        // 关键：不得写入 sys_role_menu（否则角色成员会被动获得 knowledge:x:list）
        long roleMenuLinks = roleMenuMapper.selectCount(
                new QueryWrapper<SysRoleMenu>()
                        .eq("menu_id", menuId).eq("role_id", r.getRoleId()));
        assertEquals(0L, roleMenuLinks, "owner 转让不得写入 sys_role_menu（防权限扩散）");
    }

    /* ========================= 测试 8：转让后新 owner 可管理、旧 owner 不可 ========================= */

    @Test
    void 转让后新owner可管理菜单_旧owner不可() throws Exception {
        String superToken = loginAs("ownersuper8", 1);
        Long menuId = seedMenu("", "OwnedSwitch", 2, 0, 1, 0);

        // 两个角色都持有 system:menu:update，用于通过 @PreAuthorize。
        SysRole roleA = insertRole("owner_a8", "owner_a8", 1, 0);
        SysRole roleB = insertRole("owner_b8", "owner_b8", 1, 0);
        grantMenuPerm(roleA.getRoleId(), "system:menu:update");
        grantMenuPerm(roleB.getRoleId(), "system:menu:update");

        String ua = loginUserWithRole("ua_owner8", roleA.getRoleId());
        String ub = loginUserWithRole("ub_owner8", roleB.getRoleId());

        // 初始 owner = [A]
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType("application/json")
                        .content(ownersBody(roleA.getRoleId())))
                .andExpect(status().isOk());

        // A（旧 owner）可以停用
        mockMvc.perform(put("/api/system/menus/" + menuId + "/status")
                        .header("Authorization", "Bearer " + ua)
                        .contentType("application/json").content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 转让 owner = [B]
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + superToken)
                        .contentType("application/json")
                        .content(ownersBody(roleB.getRoleId())))
                .andExpect(status().isOk());

        // A 不再是 owner → 启用被拒（MENU_NOT_FOUND）
        mockMvc.perform(put("/api/system/menus/" + menuId + "/status")
                        .header("Authorization", "Bearer " + ua)
                        .contentType("application/json").content("{\"status\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));

        // B（新 owner）可以启用
        mockMvc.perform(put("/api/system/menus/" + menuId + "/status")
                        .header("Authorization", "Bearer " + ub)
                        .contentType("application/json").content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    /* ========================= 测试 9：roleIds 校验 ========================= */

    @Test
    void roleIds非法_不存在_停用角色被拒绝() throws Exception {
        String token = loginAs("ownersuper9", 1);
        Long menuId = seedMenu("", "OwnedValidate", 2, 0, 1, 0);

        // null 元素 → 400（Bean Validation）
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleIds\":[null]}"))
                .andExpect(status().isBadRequest());

        // 非正数 → 400
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleIds\":[0]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleIds\":[-5]}"))
                .andExpect(status().isBadRequest());

        // 不存在的角色 → ROLE_INVALID
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleIds\":[999999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_INVALID"));

        // 停用角色 → ROLE_INVALID
        SysRole disabled = insertRole("owner_dis9", "owner_dis9", 0, 0);
        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(ownersBody(disabled.getRoleId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_INVALID"));
    }

    @Test
    void roleIds重复_去重后仅写入一次() throws Exception {
        String token = loginAs("ownersuper9b", 1);
        Long menuId = seedMenu("", "OwnedDedup", 2, 0, 1, 0);
        SysRole r = insertRole("owner_dup9", "owner_dup9", 1, 0);

        mockMvc.perform(put("/api/system/menus/" + menuId + "/owners")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(ownersBody(r.getRoleId(), r.getRoleId(), r.getRoleId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 去重后归属表仅一行
        long total = ownerRoleMapper.selectCount(
                new QueryWrapper<SysMenuOwnerRole>().eq("menu_id", menuId));
        assertEquals(1L, total, "重复 roleIds 去重后应仅写入一条归属");
    }

    /* ========================= 测试 4：备用 owner 已停用/删除时仍拒绝 ========================= */

    @Test
    void 另一个owner已停用时_停用唯一有效owner仍被拒绝() throws Exception {
        String token = loginAs("ownersuper4", 1);
        Long menuId = seedMenu("", "OwnedLastStop", 2, 0, 1, 0);

        SysRole active = insertRole("owner_act4", "owner_act4", 1, 0);
        SysRole disabledBackup = insertRole("owner_dis4", "owner_dis4", 0, 0); // 已停用
        ownerRoleMapper.insert(new SysMenuOwnerRole(menuId, active.getRoleId()));
        ownerRoleMapper.insert(new SysMenuOwnerRole(menuId, disabledBackup.getRoleId()));

        // 停用 active：disabledBackup 已停用不算有效 owner → 拒绝
        mockMvc.perform(put("/api/system/roles/" + active.getRoleId() + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_LAST_MENU_OWNER"));
    }

    @Test
    void 另一个owner已删除时_停用唯一有效owner仍被拒绝() throws Exception {
        String token = loginAs("ownersuper4b", 1);
        Long menuId = seedMenu("", "OwnedLastDel", 2, 0, 1, 0);

        SysRole active = insertRole("owner_act4b", "owner_act4b", 1, 0);
        SysRole deletedBackup = insertRole("owner_del4b", "owner_del4b", 1, 1); // 已删除
        ownerRoleMapper.insert(new SysMenuOwnerRole(menuId, active.getRoleId()));
        ownerRoleMapper.insert(new SysMenuOwnerRole(menuId, deletedBackup.getRoleId()));

        mockMvc.perform(put("/api/system/roles/" + active.getRoleId() + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_LAST_MENU_OWNER"));
    }
}
