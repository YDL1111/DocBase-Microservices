package com.docbase.iam.auth;

import com.docbase.iam.security.JwtProperties;
import com.docbase.iam.security.TokenStore;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

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

    @Test
    void anonymousLoginSucceedsWithValidCredentials() throws Exception {
        // Insert a real test user into H2
        SysUser user = new SysUser();
        user.setUsername("testadmin");
        user.setNickname("Test Admin");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);

        // Mock TokenStore since Redis is unavailable in tests
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("docbase:iam:token:session:" + user.getUserId())).thenReturn(1L);
        when(redisTemplate.opsForSet()).thenReturn(org.mockito.Mockito.mock(org.springframework.data.redis.core.SetOperations.class));

        String json = "{\"username\":\"testadmin\",\"password\":\"password123\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andExpect(jsonPath("$.data.userInfo.username").value("testadmin"));
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("testuser2");
        user.setNickname("Test User 2");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(1);
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);

        String json = "{\"username\":\"testuser2\",\"password\":\"wrongpassword\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginFailsForDisabledUser() throws Exception {
        SysUser user = new SysUser();
        user.setUsername("disableduser");
        user.setNickname("Disabled");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setStatus(0); // disabled
        user.setIsAdmin(0);
        user.setDeleted(0);
        userMapper.insert(user);

        String json = "{\"username\":\"disableduser\",\"password\":\"password123\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pingIsAnonymous() throws Exception {
        mockMvc.perform(get("/api/auth/ping"))
                .andExpect(status().isOk());
    }
}
