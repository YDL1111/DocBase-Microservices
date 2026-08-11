package com.docbase.iam.user;

import com.docbase.iam.security.JwtProperties;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理 Controller 输入校验测试（P1）。
 *
 * 验证状态 / 密码接口在收到非法输入时返回受控 400（VALIDATION_ERROR），
 * 而非 500 或进入 Service 层。需要真实登录以通过 @PreAuthorize。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerValidationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SysUserMapper userMapper;

    @Autowired
    TokenStore tokenStore;

    @MockitoBean
    StringRedisTemplate redisTemplate;

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

    /**
     * 创建超级管理员并返回其 access token。
     *
     * 测试共享内存 H2（跨测试类复用同一 Spring 上下文），每个测试使用唯一用户名，
     * 避免唯一索引冲突。
     */
    private String loginAsAdmin(String username) throws Exception {
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setNickname("Val Admin");
        admin.setPassword(passwordEncoder.encode("password123"));
        admin.setStatus(1);
        admin.setIsAdmin(1);
        admin.setDeleted(0);
        userMapper.insert(admin);

        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("docbase:iam:token:session:" + admin.getUserId())).thenReturn(1L);
        when(redisTemplate.opsForSet()).thenReturn(
                org.mockito.Mockito.mock(org.springframework.data.redis.core.SetOperations.class));

        String json = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data").get("accessToken").asText();
    }

    @Test
    void 状态为null应返回400() throws Exception {
        String token = loginAsAdmin("valnull");
        mockMvc.perform(put("/api/system/users/1/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 状态为非法值2应返回400() throws Exception {
        String token = loginAsAdmin("valinv");
        mockMvc.perform(put("/api/system/users/1/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 状态为负数应返回400() throws Exception {
        String token = loginAsAdmin("valneg");
        mockMvc.perform(put("/api/system/users/1/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void 密码为null应返回400() throws Exception {
        String token = loginAsAdmin("valpwdnull");
        mockMvc.perform(put("/api/system/users/1/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 密码为空白应返回400() throws Exception {
        String token = loginAsAdmin("valpwdblank");
        mockMvc.perform(put("/api/system/users/1/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"password\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void 密码过短应返回400() throws Exception {
        String token = loginAsAdmin("valpwdshort");
        mockMvc.perform(put("/api/system/users/1/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"password\": \"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
