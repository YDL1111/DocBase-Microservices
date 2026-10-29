package com.docbase.knowledge.search;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.config.import=","spring.cloud.nacos.config.enabled=false","spring.cloud.nacos.discovery.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeSearchControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean KnowledgeSearchService service;
    private UsernamePasswordAuthenticationToken auth(String permission) {
        var p = new KnowledgeUserPrincipal(7L,"user",2L,false,List.of(new SimpleGrantedAuthority(permission)));
        return new UsernamePasswordAuthenticationToken(p,null,p.authorities());
    }
    @Test void requiresLoginAndPermission() throws Exception {
        mvc.perform(post("/api/knowledge/search").contentType(MediaType.APPLICATION_JSON)
            .content("{\"knowledgeBaseId\":1,\"query\":\"部署\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/knowledge/search").with(authentication(auth("knowledge:base:list")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"knowledgeBaseId\":1,\"query\":\"部署\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void validatesInput() throws Exception {
        for (String body : List.of("{\"knowledgeBaseId\":-1,\"query\":\"部署\"}",
                "{\"knowledgeBaseId\":1,\"query\":\"   \"}",
                "{\"knowledgeBaseId\":1,\"query\":\"" + "x".repeat(501) + "\"}")) {
            mvc.perform(post("/api/knowledge/search").with(authentication(auth("knowledge:document:list")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void ignoresClientSuppliedDocumentScope() throws Exception {
        when(service.search(eq(1L),eq("部署"),any())).thenReturn(List.of());
        mvc.perform(post("/api/knowledge/search").with(authentication(auth("knowledge:document:list")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"knowledgeBaseId\":1,\"query\":\"部署\",\"visible_document_ids\":[999]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(service).search(eq(1L),eq("部署"),argThat(p -> p.userId()==7L && !p.admin()));
    }
}
