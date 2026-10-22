package com.docbase.iam.security;

import com.docbase.iam.user.UserService;
import com.docbase.iam.user.mapper.SysUserMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Path;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class PersonnelScopeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sessions;
    @Autowired JwtTokenProvider tokens;
    @Autowired UserService users;
    @Autowired SysUserMapper userMapper;
    @MockitoBean TokenStore tokenStore;
    @MockitoBean StringRedisTemplate redis;
    static final long MANAGER=880001, COLLEAGUE=880002, OUTSIDER=880003, CHILD=880004, ROLE=880001;
    static final long DEPT=880001, OTHER=880002, SUBDEPT=880003;
    @TestConfiguration static class Keys {
        @Bean @Primary JwtProperties properties() throws Exception {
            Path dir=TestKeys.writeTempKeyPair(TestKeys.generate());
            return new JwtProperties(dir.resolve("private.pem").toString(),"docbase-iam","30m","7d");
        }
    }
    @BeforeEach void setup() {
        when(tokenStore.getAuthVersion(anyLong())).thenReturn(1L);
        jdbc.update("INSERT INTO sys_organization(organization_id,parent_id,organization_name,organization_code,status) VALUES(?,0,'dept','scope_dept',1)",DEPT);
        jdbc.update("INSERT INTO sys_organization(organization_id,parent_id,organization_name,organization_code,status) VALUES(?,0,'other','scope_other',1)",OTHER);
        jdbc.update("INSERT INTO sys_organization(organization_id,parent_id,organization_name,organization_code,status) VALUES(?,?,'child','scope_child',1)",SUBDEPT,DEPT);
        insertUser(MANAGER,DEPT);insertUser(COLLEAGUE,DEPT);insertUser(OUTSIDER,OTHER);insertUser(CHILD,SUBDEPT);
        jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,data_scope,status,is_system) VALUES(?,'manager','scope_manager',3,1,0)",ROLE);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",MANAGER,ROLE);
        grant("system:user:list",880001);grant("system:user:update",880002);grant("system:user:reset-password",880003);
    }
    void insertUser(long id,long org) {jdbc.update("INSERT INTO sys_user(user_id,username,password,organization_id,status,is_admin) VALUES(?,?,'unused',?,1,0)",id,"scope_"+id,org);}
    void grant(String permission,long id) {
        jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,permission,status) VALUES(?,'scope action',?,1)",id,permission);
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)",ROLE,id);
    }
    String token(String... perms) {return "Bearer "+tokens.signAccess(""+MANAGER,"manager",Set.of(perms),1);}
    @Test void departmentListFiltersActualSqlAndDetailRejectsOutsider() throws Exception {
        String auth=token("system:user:list");
        mvc.perform(get("/api/system/users").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/system/users/"+OUTSIDER).header("Authorization",auth))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATA_SCOPE_FORBIDDEN"));
    }
    @Test void descendantAndSelfAndCustomScopesUseRealRoleRows() throws Exception {
        String auth=token("system:user:list");
        jdbc.update("UPDATE sys_role SET data_scope=4 WHERE role_id=?",ROLE);
        mvc.perform(get("/api/system/users").header("Authorization",auth)).andExpect(jsonPath("$.data.total").value(3));
        jdbc.update("UPDATE sys_role SET data_scope=5 WHERE role_id=?",ROLE);
        sessions.clearCache();
        mvc.perform(get("/api/system/users").header("Authorization",auth)).andExpect(jsonPath("$.data.total").value(1));
        jdbc.update("UPDATE sys_role SET data_scope=2 WHERE role_id=?",ROLE);
        jdbc.update("INSERT INTO sys_role_organization(role_id,organization_id) VALUES(?,?)",ROLE,OTHER);
        sessions.clearCache();
        mvc.perform(get("/api/system/users").header("Authorization",auth)).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].userId").value(OUTSIDER));
    }
    @Test void editCannotDisableOrAssignRolesAndOutOfScopeWriteRollsBack() throws Exception {
        String auth=token("system:user:update","system:user:list");
        mvc.perform(put("/api/system/users/"+COLLEAGUE+"/status").header("Authorization",auth).contentType("application/json").content("{\"status\":0}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/system/users/"+COLLEAGUE).header("Authorization",auth).contentType("application/json")
            .content("{\"organizationId\":"+DEPT+",\"nickname\":\"blocked\",\"roleIds\":[]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/system/users/"+OUTSIDER).header("Authorization",auth).contentType("application/json")
            .content("{\"organizationId\":"+OTHER+",\"nickname\":\"blocked\"}"))
            .andExpect(status().isBadRequest());
        assertNotEquals("blocked",userMapper.selectById(COLLEAGUE).getNickname());
        assertNotEquals("blocked",userMapper.selectById(OUTSIDER).getNickname());
    }
    @Test void personnelOptionsDoNotRequireOrganizationListAuthority() throws Exception {
        mvc.perform(get("/api/system/users/options").header("Authorization",token("system:user:list")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.organizations.length()").value(1))
            .andExpect(jsonPath("$.data.roles.length()").value(0));
    }
    @Test void equalPermissionsWithBroaderScopeCannotBeReset() throws Exception {
        jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,data_scope,status,is_system) VALUES(880002,'global','scope_global',1,1,0)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,880002)",COLLEAGUE);
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(880002,880003)");
        mvc.perform(put("/api/system/users/"+COLLEAGUE+"/password").header("Authorization",token("system:user:reset-password"))
            .contentType("application/json").content("{\"password\":\"valid-test-password\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATA_SCOPE_FORBIDDEN"));
        assertEquals("unused",userMapper.selectById(COLLEAGUE).getPassword());
    }
    @Test void roleMenusCannotTurnAnAllScopedRoleIntoPersonnelManager() throws Exception {
        jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,data_scope,status,is_system) VALUES(880002,'global','scope_global',1,1,0)");
        mvc.perform(put("/api/system/roles/880002/menus").header("Authorization",token("system:role:update","system:user:list"))
            .contentType("application/json").content("{\"menuIds\":[880001]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATA_SCOPE_FORBIDDEN"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=880002",Integer.class));
    }

    @Test void allScopedProfileEditCannotMoveDepartmentScopedIdentityToAnotherDepartment() throws Exception {
        jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,data_scope,status,is_system) VALUES(880002,'profile editor','scope_editor',1,1,0)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,880002)",MANAGER);
        jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(880002,880002)");
        mvc.perform(put("/api/system/users/"+MANAGER).header("Authorization",token("system:user:update","system:user:list","system:user:reset-password"))
            .contentType("application/json").content("{\"organizationId\":"+OTHER+",\"nickname\":\"blocked\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATA_SCOPE_FORBIDDEN"));
        assertEquals(DEPT,userMapper.selectById(MANAGER).getOrganizationId());
    }
}
