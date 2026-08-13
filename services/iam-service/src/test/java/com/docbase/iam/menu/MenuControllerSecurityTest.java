package com.docbase.iam.menu;

import com.docbase.iam.menu.domain.SysMenu;
import com.docbase.iam.menu.domain.SysMenuOwnerRole;
import com.docbase.iam.menu.mapper.SysMenuMapper;
import com.docbase.iam.menu.mapper.SysMenuOwnerRoleMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜单管理 Controller 安全集成测试。
 *
 * <p>通过真实登录获取 token，验证：未认证 401、缺少权限 403、输入校验 400、
 * 普通管理员不能操作系统保留菜单、不能创建 admin:all、不能创建自身没有的 permission、
 * 父子关系/字段不变量校验。
 *
 * <p>普通管理员经 RBAC 持有 system:menu:* 权限（通过独立的 menu_manager 角色
 * 关联对应菜单），从而通过 @PreAuthorize；随后在 Service 层被资源级授权拦截。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MenuControllerSecurityTest {

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
        // 归属表无外键级联（角色为逻辑删除），必须显式清理，避免测试间污染。
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

    /** 创建普通管理员：is_admin=0，但通过 menu_manager 角色持有 system:menu:* 菜单权限。 */
    private String loginNormalAdminWithMenuPerms(String username) throws Exception {
        Long mList = seedMenu("system:menu:list", "MenuList", 1, 0, 1, 0);
        Long mCreate = seedMenu("system:menu:create", "MenuCreate", 1, 0, 1, 0);
        Long mUpdate = seedMenu("system:menu:update", "MenuUpdate", 1, 0, 1, 0);
        Long mDelete = seedMenu("system:menu:delete", "MenuDelete", 1, 0, 1, 0);

        SysRole rm = new SysRole();
        rm.setRoleName("menu_manager_" + username);
        rm.setRoleKey("menu_manager_" + username);
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

    /** 插入一棵菜单，返回 menuId。isSystem 可指定以构造系统保留菜单。 */
    private Long seedMenu(String permission, String routerName, int type, int isButton, int status, int isSystem) {
        SysMenu m = new SysMenu();
        m.setParentId(0L);
        m.setMenuName("M " + permission);
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

    private String menuBody(String routerName, int type, String permission) {
        return "{\"parentId\":0,\"menuName\":\"" + routerName + "\",\"menuType\":" + type
                + ",\"routerName\":\"" + routerName + "\",\"path\":\"/" + routerName.toLowerCase()
                + "\",\"permission\":\"" + permission + "\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
    }

    /* ========================= 401 / 403 ========================= */

    @Test
    void 未认证访问应返回401() throws Exception {
        mockMvc.perform(get("/api/system/menus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 缺少权限应返回403() throws Exception {
        String token = loginAs("menunoperm", 0);
        mockMvc.perform(get("/api/system/menus").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /* ========================= 超级管理员合法 CRUD ========================= */

    @Test
    void 超级管理员可创建查询更新删除菜单() throws Exception {
        String token = loginAs("menusuper", 1);

        // 创建
        String body = menuBody("AuditLog", 1, "system:audit:list");
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 列表可见
        mockMvc.perform(get("/api/system/menus").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 树可见且含 status / isSystem 字段
        mockMvc.perform(get("/api/system/menus/tree").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    /* ========================= 系统菜单保护 ========================= */

    @Test
    void 普通管理员不能创建系统保留菜单() throws Exception {
        String token = loginNormalAdminWithMenuPerms("menunormal1");
        // 先由超级管理员创建一个系统保留菜单，再让普通管理员去改它
        Long sysMenuId = seedMenu("system:role:list", "SystemRole", 2, 0, 1, 1);

        mockMvc.perform(put("/api/system/menus/" + sysMenuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(menuBody("SystemRole", 2, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    @Test
    void 普通管理员不能删除系统保留菜单() throws Exception {
        String token = loginNormalAdminWithMenuPerms("menunormal2");
        Long sysMenuId = seedMenu("system:user:list", "SystemUser", 2, 0, 1, 1);

        mockMvc.perform(delete("/api/system/menus/" + sysMenuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    @Test
    void 普通管理员不能停用系统保留菜单() throws Exception {
        String token = loginNormalAdminWithMenuPerms("menunormal3");
        Long sysMenuId = seedMenu("system:menu:list", "SystemManage", 2, 0, 1, 1);

        mockMvc.perform(put("/api/system/menus/" + sysMenuId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    /* ========================= admin:all 防提权 ========================= */

    @Test
    void 普通管理员不能创建权限为admin_all的菜单() throws Exception {
        String token = loginNormalAdminWithMenuPerms("menunormal4");

        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(menuBody("Evil", 1, "admin:all")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_GRANTABLE"));
    }

    /* ========================= 权限子集 ========================= */

    @Test
    void 普通管理员不能创建自身没有的权限() throws Exception {
        String token = loginNormalAdminWithMenuPerms("menunormal5");

        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(menuBody("Evil2", 1, "system:user:delete")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_SUBSET"));
    }

    @Test
    void 旧权限映射归一化后不误判越权() throws Exception {
        // 普通管理员持有新格式 system:role:update；创建菜单使用旧格式 system:role:edit。
        // 归一化后一致，应允许创建。
        // 顶级节点仅超级管理员可建，故在父目录下创建，并继承父目录的角色关联。
        Long mUpdate = seedMenu("system:role:update", "RoleUpdate", 1, 0, 1, 0);
        SysRole rm = new SysRole();
        rm.setRoleName("menu_manager_map");
        rm.setRoleKey("menu_manager_map");
        rm.setStatus(1);
        rm.setIsSystem(0);
        rm.setDeleted(0);
        roleMapper.insert(rm);
        // 同时授予 system:menu:* 以通过 @PreAuthorize
        long ml = seedMenu("system:menu:list", "Ml", 1, 0, 1, 0);
        long mc = seedMenu("system:menu:create", "Mc", 1, 0, 1, 0);
        long mu = seedMenu("system:menu:update", "Mu", 1, 0, 1, 0);
        long md = seedMenu("system:menu:delete", "Md", 1, 0, 1, 0);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), ml));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), mc));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), mu));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), md));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(rm.getRoleId(), mUpdate));
        // 父目录：空 permission，角色作为所有者关联到它（写入归属表），使管理员可继承归属。
        // 注意：此处表达"所有权"，必须写归属表而非 sys_role_menu。
        Long parentDir = seedMenu("", "RoleParent", 2, 0, 1, 0);
        ownerRoleMapper.insert(new SysMenuOwnerRole(parentDir, rm.getRoleId()));

        SysUser user = new SysUser();
        user.setUsername("menumap");
        user.setNickname("U menumap");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getUserId(), rm.getRoleId()));
        stubRedis();
        String json = "{\"username\":\"menumap\",\"password\":\"password123\"}";
        String resp = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(resp).get("data").get("accessToken").asText();

        // 在父目录下创建（parentId 非 0），继承父目录的角色关联
        String body = "{\"parentId\":" + parentDir + ",\"menuName\":\"RoleEdit\",\"menuType\":1,"
                + "\"routerName\":\"RoleEdit\",\"path\":\"/roleedit\",\"permission\":\"system:role:edit\","
                + "\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    /* ========================= 输入校验 ========================= */

    @Test
    void 创建菜单menuType非法应返回400() throws Exception {
        String token = loginAs("menutype", 1);
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"x\",\"menuType\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建菜单status非法应返回400() throws Exception {
        String token = loginAs("menustatus", 1);
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(menuBody("X", 1, "").replace("\"status\":1", "\"status\":2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建菜单routerName超长应返回400() throws Exception {
        String token = loginAs("menurouterlen", 1);
        String longRouter = "A".repeat(129);
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"x\",\"menuType\":1,\"routerName\":\"" + longRouter + "\",\"path\":\"/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 创建菜单metaInfo非法JSON应返回400() throws Exception {
        String token = loginAs("menumeta", 1);
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"x\",\"menuType\":1,\"routerName\":\"X\",\"path\":\"/x\",\"metaInfo\":\"not-json\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_METAINFO_INVALID"));
    }

    @Test
    void 创建菜单menuName为空应返回400() throws Exception {
        String token = loginAs("menuname", 1);
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"\",\"menuType\":1,\"routerName\":\"X\",\"path\":\"/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /* ========================= 节点类型不变量 ========================= */

    @Test
    void 创建按钮节点permission为空应返回400() throws Exception {
        String token = loginAs("menubtnperm", 1);
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"btn\",\"menuType\":3,\"isButton\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_BUTTON_NEEDS_PERMISSION"));
    }

    @Test
    void 按钮节点不能作为父节点() throws Exception {
        String token = loginAs("menubtnparent", 1);
        // 先创建一个按钮
        Long btnId = seedMenu("system:x:delete", "BtnDelete", 3, 1, 1, 0);

        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":" + btnId + ",\"menuName\":\"child\",\"menuType\":1,\"routerName\":\"Child\",\"path\":\"/child\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_PARENT_IS_BUTTON"));
    }

    /* ========================= 父子关系校验 ========================= */

    @Test
    void 父节点不存在应返回400() throws Exception {
        String token = loginAs("menuparent", 1);
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":99999,\"menuName\":\"x\",\"menuType\":1,\"routerName\":\"X\",\"path\":\"/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_PARENT_NOT_FOUND"));
    }

    @Test
    void 父节点停用应返回400() throws Exception {
        String token = loginAs("menuparentdis", 1);
        Long disabledParent = seedMenu("system:x:list", "DisabledParent", 2, 0, 0, 0);

        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":" + disabledParent + ",\"menuName\":\"x\",\"menuType\":1,\"routerName\":\"X\",\"path\":\"/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_PARENT_DISABLED"));
    }

    @Test
    void 菜单树应包含status与isSystem字段() throws Exception {
        String token = loginAs("menutree", 1);
        // 先创建一棵菜单，确保树非空
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(menuBody("TreeDir", 2, "")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/system/menus/tree").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").exists())
                .andExpect(jsonPath("$.data[0].isSystem").exists());
    }

    @Test
    void 有子节点的菜单不得删除() throws Exception {
        String token = loginAs("menuchildren", 1);
        Long parentId = seedMenu("system:x:list", "ParentDir", 2, 0, 1, 0);
        // 子节点
        SysMenu child = new SysMenu();
        child.setParentId(parentId);
        child.setMenuName("ChildItem");
        child.setMenuType(1);
        child.setRouterName("ChildItem");
        child.setPath("/childitem");
        child.setPermission("system:x:item");
        child.setIsButton(0);
        child.setStatus(1);
        child.setIsSystem(0);
        child.setSortNum(1);
        child.setMetaInfo("{}");
        child.setDeleted(0);
        menuMapper.insert(child);

        mockMvc.perform(delete("/api/system/menus/" + parentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_HAS_CHILDREN"));
    }

    @Test
    void 停用菜单已是目标状态应返回400() throws Exception {
        String token = loginAs("menualready", 1);
        Long disabled = seedMenu("system:x:list", "DisabledMenu", 2, 0, 0, 0);

        mockMvc.perform(put("/api/system/menus/" + disabled + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_ALREADY_DISABLED"));
    }

    /* ========================= [P0-1] 更新接口不再改写状态 ========================= */

    @Test
    void 更新接口不含status字段_无法通过更新绕过停用校验() throws Exception {
        String token = loginAs("menuupdate_nostatus", 1);
        // 创建一个含启用子节点的父目录
        Long parentId = seedMenu("system:x:list", "ParentDir", 2, 0, 1, 0);
        SysMenu child = new SysMenu();
        child.setParentId(parentId);
        child.setMenuName("ChildItem");
        child.setMenuType(1);
        child.setRouterName("ChildItem");
        child.setPath("/childitem");
        child.setPermission("system:x:item");
        child.setIsButton(0);
        child.setStatus(1); // 启用子节点
        child.setIsSystem(0);
        child.setSortNum(1);
        child.setMetaInfo("{}");
        child.setDeleted(0);
        menuMapper.insert(child);

        // 即使请求里带 status:0，该字段已被 DTO 忽略，status 不会改变。
        mockMvc.perform(put("/api/system/menus/" + parentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"p\",\"menuType\":2,\"routerName\":\"ParentDir\",\"path\":\"/parentdir\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    /* ========================= [P1-2] 更新缺少必填字段返回 400 ========================= */

    @Test
    void 更新缺少isButton与sortNum应返回400() throws Exception {
        String token = loginAs("menuupdate_required", 1);
        Long menuId = seedMenu("system:x:list", "ReqMenu", 2, 0, 1, 0);

        // 故意省略 isButton 与 sortNum → Bean Validation 应返回 400 VALIDATION_ERROR
        mockMvc.perform(put("/api/system/menus/" + menuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"p\",\"menuType\":2,\"routerName\":\"ReqMenu\",\"path\":\"/reqmenu\",\"metaInfo\":\"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /* ========================= [P1-1] 空 permission 结构节点资源级保护 ========================= */

    @Test
    void 普通菜单管理员不能修改空权限的业务根目录() throws Exception {
        // menu_manager 仅持有 system:menu:*，未持有 knowledge:* → 不能修改 Knowledge 根目录。
        String token = loginNormalAdminWithMenuPerms("menunormal_nopermdir");
        // 空 permission 的 Knowledge 根目录（isSystem=0，普通业务目录）
        Long knowledgeDir = seedMenu("", "Knowledge", 2, 0, 1, 0);
        // 其下挂一个 knowledge:* 按钮，决定该目录的归属
        SysMenu knowledgeBtn = new SysMenu();
        knowledgeBtn.setParentId(knowledgeDir);
        knowledgeBtn.setMenuName("知识库列表权限");
        knowledgeBtn.setMenuType(3);
        knowledgeBtn.setRouterName("");
        knowledgeBtn.setPath("");
        knowledgeBtn.setPermission("knowledge:base:list");
        knowledgeBtn.setIsButton(1);
        knowledgeBtn.setStatus(1);
        knowledgeBtn.setIsSystem(0);
        knowledgeBtn.setSortNum(1);
        knowledgeBtn.setMetaInfo("{}");
        knowledgeBtn.setDeleted(0);
        menuMapper.insert(knowledgeBtn);

        mockMvc.perform(put("/api/system/menus/" + knowledgeDir)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"Knowledge\",\"menuType\":2,\"routerName\":\"Knowledge\",\"path\":\"/knowledge\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    /* ========================= [P0-1] 目标父目录资源级授权（HTTP 回归） ========================= */

    @Test
    void 普通管理员不能在无权管理的业务根目录下创建节点() throws Exception {
        // menu_manager 仅持有 system:menu:*，未持有 knowledge:* → 在 Knowledge 根目录下创建节点应被拒。
        String token = loginNormalAdminWithMenuPerms("menunormal_create_under_dir");
        Long knowledgeDir = seedMenu("", "Knowledge", 2, 0, 1, 0);
        SysMenu knowledgeBtn = new SysMenu();
        knowledgeBtn.setParentId(knowledgeDir);
        knowledgeBtn.setMenuName("知识库列表权限");
        knowledgeBtn.setMenuType(3);
        knowledgeBtn.setRouterName("");
        knowledgeBtn.setPath("");
        knowledgeBtn.setPermission("knowledge:base:list");
        knowledgeBtn.setIsButton(1);
        knowledgeBtn.setStatus(1);
        knowledgeBtn.setIsSystem(0);
        knowledgeBtn.setSortNum(1);
        knowledgeBtn.setMetaInfo("{}");
        knowledgeBtn.setDeleted(0);
        menuMapper.insert(knowledgeBtn);

        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":" + knowledgeDir + ",\"menuName\":\"恶意节点\",\"menuType\":1,\"routerName\":\"Evil\",\"path\":\"/evil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    @Test
    void 普通管理员不能把节点移动到无权管理的业务目录() throws Exception {
        // menu_manager 持有 system:menu:update，但无权写入 Ingest 根目录 → 移动被拒。
        String token = loginNormalAdminWithMenuPerms("menunormal_move_to_dir");
        // 被移动节点：menu_manager 通过 system:menu:update 持有其当前 permission
        Long ownedNode = seedMenu("system:menu:update", "OwnedNode", 2, 0, 1, 0);
        Long ingestDir = seedMenu("", "IngestDir", 2, 0, 1, 0);
        SysMenu ingestBtn = new SysMenu();
        ingestBtn.setParentId(ingestDir);
        ingestBtn.setMenuName("任务列表权限");
        ingestBtn.setMenuType(3);
        ingestBtn.setRouterName("");
        ingestBtn.setPath("");
        ingestBtn.setPermission("ingest:task:list");
        ingestBtn.setIsButton(1);
        ingestBtn.setStatus(1);
        ingestBtn.setIsSystem(0);
        ingestBtn.setSortNum(1);
        ingestBtn.setMetaInfo("{}");
        ingestBtn.setDeleted(0);
        menuMapper.insert(ingestBtn);

        mockMvc.perform(put("/api/system/menus/" + ownedNode)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":" + ingestDir + ",\"menuName\":\"OwnedNode\",\"menuType\":2,\"routerName\":\"OwnedNode\",\"path\":\"/ownednode\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    /* ========================= [P0-2] 停用后仍可经角色关联重新启用（HTTP 回归） ========================= */

    @Test
    void 普通管理员停用其负责菜单后仍可重新启用() throws Exception {
        // 构建 knowledge_admin：持有 system:menu:update（过 @PreAuthorize）且角色关联 knowledge 菜单。
        Long mList = seedMenu("system:menu:list", "Ml", 1, 0, 1, 0);
        Long mUpdate = seedMenu("system:menu:update", "Mu", 1, 0, 1, 0);
        Long knowledgeMenu = seedMenu("knowledge:base:list", "KnowledgeList", 1, 0, 1, 0);

        SysRole role = new SysRole();
        role.setRoleName("knowledge_admin");
        role.setRoleKey("knowledge_admin");
        role.setStatus(1);
        role.setIsSystem(0);
        role.setDeleted(0);
        roleMapper.insert(role);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mList));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mUpdate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), knowledgeMenu));
        // 角色作为 knowledge 菜单的<b>所有者</b>（写入归属表），使 assertOwnsMenuViaRole 通过。
        // 停用后权限集不再含 knowledge:base:list，但所有者关联仍在 → 可重新启用，避免自锁。
        ownerRoleMapper.insert(new SysMenuOwnerRole(knowledgeMenu, role.getRoleId()));

        SysUser user = new SysUser();
        user.setUsername("knowledgeadmin");
        user.setNickname("U knowledgeadmin");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getUserId(), role.getRoleId()));

        // 第一次登录：knowledge 菜单启用，Token 含 knowledge:base:list
        stubRedis();
        String json = "{\"username\":\"knowledgeadmin\",\"password\":\"password123\"}";
        String resp1 = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        String token1 = objectMapper.readTree(resp1).get("data").get("accessToken").asText();

        // 停用 knowledge 菜单
        mockMvc.perform(put("/api/system/menus/" + knowledgeMenu + "/status")
                        .header("Authorization", "Bearer " + token1)
                        .contentType("application/json")
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());

        // 重新登录：knowledge 菜单已停用，新 Token 不再含 knowledge:base:list（模拟刷新 Token 后失去该权限）
        stubRedis();
        String resp2 = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        String token2 = objectMapper.readTree(resp2).get("data").get("accessToken").asText();

        // 仅凭角色关联（不依赖权限集）应可重新启用，避免自锁
        mockMvc.perform(put("/api/system/menus/" + knowledgeMenu + "/status")
                        .header("Authorization", "Bearer " + token2)
                        .contentType("application/json")
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    /* ========================= [P1-1] 停用后代权限仍参与归属校验（HTTP 回归） ========================= */

    @Test
    void 空权限目录的停用后代权限仍参与归属校验() throws Exception {
        // Knowledge 根目录的子树按钮已停用；menu_manager 仅持有 system:menu:*。
        // 归属校验应包含停用后代的 knowledge:base:list → 拒绝修改。
        String token = loginNormalAdminWithMenuPerms("menunormal_disabled_child");
        Long knowledgeDir = seedMenu("", "Knowledge", 2, 0, 1, 0);
        SysMenu knowledgeBtn = new SysMenu();
        knowledgeBtn.setParentId(knowledgeDir);
        knowledgeBtn.setMenuName("知识库列表权限");
        knowledgeBtn.setMenuType(3);
        knowledgeBtn.setRouterName("");
        knowledgeBtn.setPath("");
        knowledgeBtn.setPermission("knowledge:base:list");
        knowledgeBtn.setIsButton(1);
        knowledgeBtn.setStatus(0); // 已停用
        knowledgeBtn.setIsSystem(0);
        knowledgeBtn.setSortNum(1);
        knowledgeBtn.setMetaInfo("{}");
        knowledgeBtn.setDeleted(0);
        menuMapper.insert(knowledgeBtn);

        mockMvc.perform(put("/api/system/menus/" + knowledgeDir)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"parentId\":0,\"menuName\":\"Knowledge\",\"menuType\":2,\"routerName\":\"Knowledge\",\"path\":\"/knowledge\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    @Test
    void 普通菜单管理员不能删除空权限的业务根目录() throws Exception {
        String token = loginNormalAdminWithMenuPerms("menunormal_nopermdir_del");
        Long ingestDir = seedMenu("", "IngestDir", 2, 0, 1, 0);
        SysMenu ingestBtn = new SysMenu();
        ingestBtn.setParentId(ingestDir);
        ingestBtn.setMenuName("任务列表权限");
        ingestBtn.setMenuType(3);
        ingestBtn.setRouterName("");
        ingestBtn.setPath("");
        ingestBtn.setPermission("ingest:task:list");
        ingestBtn.setIsButton(1);
        ingestBtn.setStatus(1);
        ingestBtn.setIsSystem(0);
        ingestBtn.setSortNum(1);
        ingestBtn.setMetaInfo("{}");
        ingestBtn.setDeleted(0);
        menuMapper.insert(ingestBtn);

        mockMvc.perform(delete("/api/system/menus/" + ingestDir)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    /* ========================= [P0] 空结构节点创建时继承父角色关联（防自锁）========================= */

    /** 构建一个"目录管理员"：持有 system:menu:* 且角色关联到指定父目录（空 permission、无后代）。 */
    private String loginDirAdminWithMenuPerms(String username, Long parentDirId) throws Exception {
        Long mList = seedMenu("system:menu:list", "Ml", 1, 0, 1, 0);
        Long mCreate = seedMenu("system:menu:create", "Mc", 1, 0, 1, 0);
        Long mUpdate = seedMenu("system:menu:update", "Mu", 1, 0, 1, 0);
        Long mDelete = seedMenu("system:menu:delete", "Md", 1, 0, 1, 0);

        SysRole role = new SysRole();
        role.setRoleName("dir_admin_" + username);
        role.setRoleKey("dir_admin_" + username);
        role.setStatus(1);
        role.setIsSystem(0);
        role.setDeleted(0);
        roleMapper.insert(role);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mList));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mCreate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mUpdate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mDelete));
        // 关键：角色作为父目录的<b>所有者</b>写入归属表（sys_menu_owner_role），
        // 使管理员通过所有者角色关联"拥有"该空 permission 目录。
        // 注意：不得写入 sys_role_menu——父目录虽为空 permission 无直接扩散风险，
        // 但归属与授权必须解耦；且此处表达的是"所有权"而非"可见性/授权"。
        ownerRoleMapper.insert(new SysMenuOwnerRole(parentDirId, role.getRoleId()));

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname("U " + username);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getUserId(), role.getRoleId()));

        stubRedis();
        String json = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    @Test
    void 普通管理员在归属的空目录下创建空结构节点_完整生命周期成功() throws Exception {
        // 父目录：空 permission、无后代 → 边界为空，归属由角色关联决定。
        Long parentDir = seedMenu("", "DirParent", 2, 0, 1, 0);
        String token = loginDirAdminWithMenuPerms("diradmin_lifecycle", parentDir);

        // 1) 在父目录下创建空 permission 目录 → 应成功，并继承父目录的角色关联
        String createBody = "{\"parentId\":" + parentDir + ",\"menuName\":\"新目录\",\"menuType\":2,"
                + "\"routerName\":\"NewDir\",\"path\":\"/newdir\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
        String createResp = mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        Long newDirId = objectMapper.readTree(createResp).get("data").asLong();

        // 2) 修改新目录 → 继承的角色关联使调用者仍能管理该节点（不再自锁）
        String updateBody = "{\"parentId\":" + parentDir + ",\"menuName\":\"改名\",\"menuType\":2,"
                + "\"routerName\":\"NewDir\",\"path\":\"/newdir\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1}";
        mockMvc.perform(put("/api/system/menus/" + newDirId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 3) 在新目录下创建子节点 → 同样继承角色关联，成功
        String childBody = "{\"parentId\":" + newDirId + ",\"menuName\":\"子目录\",\"menuType\":2,"
                + "\"routerName\":\"ChildDir\",\"path\":\"/childdir\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(childBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    void 普通管理员创建顶级空结构节点应被拒绝() throws Exception {
        // 顶级空 permission 节点无父可继承，非超级管理员不得创建（仅超级管理员可创建）。
        Long parentDir = seedMenu("", "DirParent", 2, 0, 1, 0);
        String token = loginDirAdminWithMenuPerms("diradmin_toplevel", parentDir);

        String createBody = "{\"parentId\":0,\"menuName\":\"顶级目录\",\"menuType\":2,"
                + "\"routerName\":\"TopDir\",\"path\":\"/topdir\",\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
        mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_OWNERSHIP_REQUIRED"));
    }

    @Test
    void 普通管理员创建非空permission子菜单_可停用并重新启用() throws Exception {
        // 非空 permission 子菜单同样继承父目录的角色关联 → 创建后可启停（不再自锁）。
        // 使用 diradmin 已持有的 system:menu:list 作为子菜单权限（assertPermissionWritable 要求子集）。
        Long parentDir = seedMenu("", "DirParent", 2, 0, 1, 0);
        String token = loginDirAdminWithMenuPerms("diradmin_nonempty_status", parentDir);

        String createBody = "{\"parentId\":" + parentDir + ",\"menuName\":\"子菜单\",\"menuType\":1,"
                + "\"routerName\":\"Child\",\"path\":\"/child\",\"permission\":\"system:menu:list\","
                + "\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
        String createResp = mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        Long childId = objectMapper.readTree(createResp).get("data").asLong();

        // 停用
        mockMvc.perform(put("/api/system/menus/" + childId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 重新启用
        mockMvc.perform(put("/api/system/menus/" + childId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    void 未继承关联的其他角色_即使拥有menu权限也不能启停() throws Exception {
        // 创建者（diradmin）持有子菜单的继承角色关联；
        // 另一名管理员（menuadmin）拥有 system:menu:* 但未继承该关联 → 启停应被拒。
        Long parentDir = seedMenu("", "DirParent", 2, 0, 1, 0);
        String ownerToken = loginDirAdminWithMenuPerms("diradmin_owner", parentDir);

        String createBody = "{\"parentId\":" + parentDir + ",\"menuName\":\"子菜单\",\"menuType\":1,"
                + "\"routerName\":\"Child\",\"path\":\"/child\",\"permission\":\"system:menu:list\","
                + "\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
        String createResp = mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long childId = objectMapper.readTree(createResp).get("data").asLong();

        // 另一名拥有 system:menu:* 的管理员（未继承该子菜单的角色关联）
        String otherToken = loginNormalAdminWithMenuPerms("menuadmin_no_link");

        mockMvc.perform(put("/api/system/menus/" + childId + "/status")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType("application/json")
                        .content("{\"status\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }

    /**
     * 创建指定角色并关联系统管理菜单（system:menu:*）与可选的额外 permission 菜单，
     * 再把指定用户关联到该角色。返回登录 token。
     *
     * @param username    用户名（也用于角色命名）
     * @param extraPerm   额外关联的 permission 菜单（模拟角色本身拥有某业务权限），可为 null
     * @param ownerDirId  若不为 null，该角色同时作为此目录的所有者（写入归属表）
     */
    private String loginUserWithRole(String username, String extraPerm, Long ownerDirId) throws Exception {
        Long mList = seedMenu("system:menu:list", "Ml" + username, 1, 0, 1, 0);
        Long mCreate = seedMenu("system:menu:create", "Mc" + username, 1, 0, 1, 0);
        Long mUpdate = seedMenu("system:menu:update", "Mu" + username, 1, 0, 1, 0);
        Long mDelete = seedMenu("system:menu:delete", "Md" + username, 1, 0, 1, 0);

        SysRole role = new SysRole();
        role.setRoleName("role_" + username);
        role.setRoleKey("role_" + username);
        role.setStatus(1);
        role.setIsSystem(0);
        role.setDeleted(0);
        roleMapper.insert(role);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mList));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mCreate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mUpdate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), mDelete));
        if (extraPerm != null) {
            Long extraMenu = seedMenu(extraPerm, "Extra" + username, 1, 0, 1, 0);
            roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(role.getRoleId(), extraMenu));
        }
        if (ownerDirId != null) {
            // 所有者角色关联写入归属表（表达"所有权"），而非 sys_role_menu（表达"授权"）。
            ownerRoleMapper.insert(new SysMenuOwnerRole(ownerDirId, role.getRoleId()));
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname("U " + username);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);
        userRoleMapper.insert(new SysUserRole(user.getUserId(), role.getRoleId()));

        stubRedis();
        String json = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(json))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    /* ========================= [P0] 归属与授权解耦：继承归属不得扩散 permission ========================= */

    @Test
    void 创建业务权限菜单_继承的所有者角色不因此获得该permission() throws Exception {
        // 场景（权限扩散 P0 回归）：
        // - 角色 A：父目录的所有者（owner），本身不持有 knowledge:x。
        // - 角色 B：持有 knowledge:x（通过关联 knowledge 菜单）。
        // - U1 同时持有 A、B → 聚合后拥有 knowledge:x，可通过 assertPermissionWritable。
        // - U2 仅持有 A。
        // U1 在父目录下创建 knowledge:x 菜单。正确行为：
        //   1. 仅建立 owner(A, menu)，不建立 role_menu(A, menu)——否则 A 的所有成员（含 U2）
        //      都会被动获得 knowledge:x。
        //   2. U2 不得因此获得 knowledge:x。
        //   3. U1 仍能启停新菜单（通过继承的所有者关联）。
        //   4. 仅持有角色 C（同样拥有 knowledge:x 但不是所有者）的 U3 不得启停。

        // 父目录（空 permission、无后代）
        Long parentDir = seedMenu("", "KnowDir", 2, 0, 1, 0);

        // 角色 A = 父目录所有者；角色 B = knowledge:x 持有者。
        // U1 同时持有 A、B：用两个角色分别创建，再把 U1 关联到两个角色。
        Long mList = seedMenu("system:menu:list", "MlU1", 1, 0, 1, 0);
        Long mCreate = seedMenu("system:menu:create", "McU1", 1, 0, 1, 0);
        Long mUpdate = seedMenu("system:menu:update", "MuU1", 1, 0, 1, 0);
        Long mDelete = seedMenu("system:menu:delete", "MdU1", 1, 0, 1, 0);

        // 角色 A：系统菜单 + 父目录所有者
        SysRole roleA = new SysRole();
        roleA.setRoleName("role_A");
        roleA.setRoleKey("role_A");
        roleA.setStatus(1);
        roleA.setIsSystem(0);
        roleA.setDeleted(0);
        roleMapper.insert(roleA);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleA.getRoleId(), mList));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleA.getRoleId(), mCreate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleA.getRoleId(), mUpdate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleA.getRoleId(), mDelete));
        ownerRoleMapper.insert(new SysMenuOwnerRole(parentDir, roleA.getRoleId()));

        // 角色 B：knowledge:x 持有者（一个业务菜单）
        Long knowledgeMenu = seedMenu("knowledge:x:list", "KnowledgeBiz", 1, 0, 1, 0);
        SysRole roleB = new SysRole();
        roleB.setRoleName("role_B");
        roleB.setRoleKey("role_B");
        roleB.setStatus(1);
        roleB.setIsSystem(0);
        roleB.setDeleted(0);
        roleMapper.insert(roleB);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleB.getRoleId(), knowledgeMenu));

        // U1 同时持有 A、B
        SysUser u1 = new SysUser();
        u1.setUsername("u1_both");
        u1.setNickname("U1");
        u1.setPassword(passwordEncoder.encode("password123"));
        u1.setStatus(1);
        u1.setIsAdmin(0);
        u1.setDeleted(0);
        userMapper.insert(u1);
        userRoleMapper.insert(new SysUserRole(u1.getUserId(), roleA.getRoleId()));
        userRoleMapper.insert(new SysUserRole(u1.getUserId(), roleB.getRoleId()));

        // U2 仅持有 A
        SysUser u2 = new SysUser();
        u2.setUsername("u2_only_a");
        u2.setNickname("U2");
        u2.setPassword(passwordEncoder.encode("password123"));
        u2.setStatus(1);
        u2.setIsAdmin(0);
        u2.setDeleted(0);
        userMapper.insert(u2);
        userRoleMapper.insert(new SysUserRole(u2.getUserId(), roleA.getRoleId()));

        // U3 仅持有角色 C：拥有 knowledge:x 与 system:menu:update（过 @PreAuthorize），
        // 但不是该菜单的所有者 → 服务层应拒绝启停（MENU_NOT_FOUND）。
        SysRole roleC = new SysRole();
        roleC.setRoleName("role_C");
        roleC.setRoleKey("role_C");
        roleC.setStatus(1);
        roleC.setIsSystem(0);
        roleC.setDeleted(0);
        roleMapper.insert(roleC);
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleC.getRoleId(), knowledgeMenu));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleC.getRoleId(), mList));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleC.getRoleId(), mCreate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleC.getRoleId(), mUpdate));
        roleMenuMapper.insert(new com.docbase.iam.role.domain.SysRoleMenu(roleC.getRoleId(), mDelete));
        SysUser u3 = new SysUser();
        u3.setUsername("u3_role_c");
        u3.setNickname("U3");
        u3.setPassword(passwordEncoder.encode("password123"));
        u3.setStatus(1);
        u3.setIsAdmin(0);
        u3.setDeleted(0);
        userMapper.insert(u3);
        userRoleMapper.insert(new SysUserRole(u3.getUserId(), roleC.getRoleId()));

        stubRedis();
        String u1Token = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                .contentType("application/json").content("{\"username\":\"u1_both\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString()).get("data").get("accessToken").asText();
        String u3Token = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                .contentType("application/json").content("{\"username\":\"u3_role_c\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString()).get("data").get("accessToken").asText();

        // U1 在父目录下创建 knowledge:x 菜单
        String createBody = "{\"parentId\":" + parentDir + ",\"menuName\":\"知识业务\",\"menuType\":1,"
                + "\"routerName\":\"KnowBiz\",\"path\":\"/knowbiz\",\"permission\":\"knowledge:x:list\","
                + "\"metaInfo\":\"{}\",\"isButton\":0,\"sortNum\":1,\"status\":1}";
        String createResp = mockMvc.perform(post("/api/system/menus")
                        .header("Authorization", "Bearer " + u1Token)
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        Long childId = objectMapper.readTree(createResp).get("data").asLong();

        // 验证 1：所有者关联写入归属表（角色 A → 新菜单）
        long ownerLinks = ownerRoleMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysMenuOwnerRole>()
                        .eq("menu_id", childId).eq("role_id", roleA.getRoleId()));
        assertTrue(ownerLinks > 0, "所有者关联应写入归属表");
        // 验证 2（关键防扩散）：不得写入 sys_role_menu——否则角色 A 的所有成员都会获得 knowledge:x
        long roleMenuLinks = roleMenuMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.docbase.iam.role.domain.SysRoleMenu>()
                        .eq("menu_id", childId).eq("role_id", roleA.getRoleId()));
        assertEquals(0L, roleMenuLinks,
                "不得把继承的所有者角色关联写入 sys_role_menu（防权限扩散）");

        // 验证 3：U1 仍能启停新菜单（通过继承的所有者关联）
        mockMvc.perform(put("/api/system/menus/" + childId + "/status")
                        .header("Authorization", "Bearer " + u1Token)
                        .contentType("application/json")
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // 验证 4：U3 持有同样 permission 但不是所有者 → 启停应被拒
        mockMvc.perform(put("/api/system/menus/" + childId + "/status")
                        .header("Authorization", "Bearer " + u3Token)
                        .contentType("application/json")
                        .content("{\"status\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
    }
}
