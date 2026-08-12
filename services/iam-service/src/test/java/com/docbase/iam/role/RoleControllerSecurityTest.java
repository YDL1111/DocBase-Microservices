package com.docbase.iam.role;

import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.role.domain.SysRole;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色管理 Controller 安全集成测试。
 *
 * 通过真实登录获取 token，验证：未认证 401、缺少权限 403、输入校验 400、
 * 普通管理员不能操作系统保留角色、不能通过菜单取得 admin:all、不能授予自身
 * 没有的权限。
 *
 * <p>普通管理员经 RBAC 持有 system:role:* 权限（通过独立的 role_manager 角色
 * 关联对应菜单），从而通过 @PreAuthorize；随后在 Service 层被资源级授权拦截。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleControllerSecurityTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SysUserMapper userMapper;
    @Autowired SysUserRoleMapper userRoleMapper;
    @Autowired SysRoleMapper roleMapper;
    @Autowired SysRoleMenuMapper roleMenuMapper;
    @Autowired SysMenuMapper menuMapper;
    @Autowired TokenStore tokenStore;
    @Autowired com.docbase.iam.user.mapper.TestUserCleanupMapper testUserCleanupMapper;

    @MockitoBean StringRedisTemplate redisTemplate;

    /**
     * 集成测试共享内存 H2（DB_CLOSE_DELAY=-1），每个测试物理清空涉及的表，
     * 避免残留用户/角色/菜单影响其它测试类（如用户名唯一索引冲突、is_admin=1 用户
     * 扩大有效管理员集合等）。
     */
    @AfterEach
    void tearDown() {
        testUserCleanupMapper.deleteAllPhysically();
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

    private void stubRedis() {
        var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(any(String.class))).thenReturn(1L);
        var setOps = mock(org.springframework.data.redis.core.SetOperations.class);
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

    /** 创建普通管理员：is_admin=0，但通过 role_manager 角色持有 system:role:* 菜单权限。 */
    private String loginNormalAdminWithRolePerms(String username) throws Exception {
        // 菜单权限
        Long mList = seedMenu("system:role:list");
        Long mCreate = seedMenu("system:role:create");
        Long mUpdate = seedMenu("system:role:update");
        Long mDelete = seedMenu("system:role:delete");
        // 角色 + 关联
        SysRole rm = new SysRole();
        rm.setRoleName("role_manager_" + username);
        rm.setRoleKey("role_manager_" + username);
        rm.setStatus(1);
        rm.setIsSystem(0);
        rm.setDeleted(0);
        roleMapper.insert(rm);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), mList));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), mCreate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), mUpdate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), mDelete));

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname("U " + username);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getUserId(), rm.getRoleId()));

        stubRedis();
        String json = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    private Long seedMenu(String permission) {
        SysMenu m = new SysMenu();
        m.setParentId(0L);
        m.setMenuName("M " + permission);
        m.setMenuType(1);
        m.setPermission(permission);
        m.setStatus(1);
        m.setIsButton(0);
        m.setSortNum(1);
        m.setDeleted(0);
        menuMapper.insert(m);
        return m.getMenuId();
    }

    private SysRole seedRole(String key, int isSystem) {
        SysRole r = new SysRole();
        r.setRoleName("R " + key);
        r.setRoleKey(key);
        r.setRoleSort(1);
        r.setStatus(1);
        r.setIsSystem(isSystem);
        r.setDeleted(0);
        roleMapper.insert(r);
        return r;
    }

    /* ========================= 401 / 403 ========================= */

    @Test
    void 未认证访问应返回401() throws Exception {
        mockMvc.perform(get("/api/system/roles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 缺少权限应返回403() throws Exception {
        String token = loginAs("rolenoperm", 0);
        mockMvc.perform(get("/api/system/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /* ========================= 输入校验 ========================= */

    @Test
    void 创建角色roleKey为空应返回400() throws Exception {
        String token = loginAs("rolekeyblank", 1);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建角色status非法值应返回400() throws Exception {
        String token = loginAs("rolestatus", 1);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"k1\",\"status\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 停用角色status为null应返回400() throws Exception {
        String token = loginAs("rolenull", 1);
        SysRole r = seedRole("rolenull_role", 0);
        mockMvc.perform(put("/api/system/roles/" + r.getRoleId() + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 分页size超限应返回400() throws Exception {
        String token = loginAs("rolesize", 1);
        mockMvc.perform(get("/api/system/roles?size=500")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /* ========================= 系统角色保护 ========================= */

    @Test
    void 普通管理员不能修改系统保留角色() throws Exception {
        String token = loginNormalAdminWithRolePerms("normaladmin");
        SysRole sysRole = seedRole("sys_admin_role", 1);

        mockMvc.perform(put("/api/system/roles/" + sysRole.getRoleId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"hacked\",\"roleKey\":\"sys_admin_role\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    @Test
    void 普通管理员不能停用系统保留角色() throws Exception {
        String token = loginNormalAdminWithRolePerms("normaladmin2");
        SysRole sysRole = seedRole("sys_admin_role2", 1);

        mockMvc.perform(put("/api/system/roles/" + sysRole.getRoleId() + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    @Test
    void 普通管理员不能删除系统保留角色() throws Exception {
        String token = loginNormalAdminWithRolePerms("normaladmin3");
        SysRole sysRole = seedRole("sys_admin_role3", 1);

        mockMvc.perform(delete("/api/system/roles/" + sysRole.getRoleId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    /* ========================= admin:all 防提权 ========================= */

    @Test
    void 普通管理员不能通过菜单取得admin_all() throws Exception {
        String token = loginNormalAdminWithRolePerms("normaladmin4");
        Long menuId = seedMenu("admin:all");

        String body = "{\"roleName\":\"evil\",\"roleKey\":\"evil_role\",\"menuIds\":[" + menuId + "]}";
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_GRANTABLE"));
    }

    /* ========================= DTO 边界校验 ========================= */

    @Test
    void 创建角色menuIds含负数应返回400() throws Exception {
        String token = loginAs("rolemenuneg", 1);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"neg_key\",\"menuIds\":[-1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建角色remark超长应返回400() throws Exception {
        String token = loginAs("roleremark", 1);
        String longRemark = "a".repeat(513);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"remark_key\",\"remark\":\"" + longRemark + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建角色roleSort超限应返回400() throws Exception {
        String token = loginAs("rolesort", 1);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"sort_key\",\"roleSort\":10000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建角色roleSort为负数应返回400() throws Exception {
        String token = loginAs("rolesortneg", 1);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"sortneg_key\",\"roleSort\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建角色dataScope为0应返回400() throws Exception {
        String token = loginAs("roledsz", 1);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"dsz_key\",\"dataScope\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建角色menuIds含null元素应返回400() throws Exception {
        String token = loginAs("rolemenuull", 1);
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"roleName\":\"x\",\"roleKey\":\"menunull_key\",\"menuIds\":[1,null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 普通管理员不能授予自身没有的权限() throws Exception {
        String token = loginNormalAdminWithRolePerms("normaladmin5");
        Long menuId = seedMenu("system:user:delete");

        String body = "{\"roleName\":\"evil2\",\"roleKey\":\"evil_role2\",\"menuIds\":[" + menuId + "]}";
        mockMvc.perform(post("/api/system/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_SUBSET"));
    }
}
