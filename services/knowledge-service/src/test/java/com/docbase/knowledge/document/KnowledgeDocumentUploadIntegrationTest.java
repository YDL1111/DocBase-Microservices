package com.docbase.knowledge.document;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.base.domain.KnowledgeBase;
import com.docbase.knowledge.base.service.KnowledgeBaseService;
import com.docbase.knowledge.document.domain.KnowledgeDocument;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentMapper;
import com.docbase.knowledge.document.mapper.KnowledgeDocumentVersionMapper;
import com.docbase.knowledge.document.mapper.KnowledgeUploadRequestMapper;
import com.docbase.knowledge.document.service.DocumentUploadValidator;
import com.docbase.knowledge.document.service.KnowledgeDocumentUploadService;
import com.docbase.knowledge.document.domain.KnowledgeUploadRequest;
import com.docbase.knowledge.event.OutboxEntity;
import com.docbase.knowledge.event.OutboxEventMapper;
import com.docbase.knowledge.event.OutboxService;
import com.docbase.knowledge.folder.domain.KnowledgeFolder;
import com.docbase.knowledge.folder.mapper.KnowledgeFolderMapper;
import com.docbase.knowledge.member.domain.KnowledgeMember;
import com.docbase.knowledge.member.mapper.KnowledgeMemberMapper;
import com.docbase.knowledge.permission.KnowledgeUserPrincipal;
import com.docbase.knowledge.storage.KnowledgeObjectStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "docbase.document-upload.max-file-size=4B",
        "docbase.document-upload.internal-registration-api-key=test-internal-key"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KnowledgeDocumentUploadIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired KnowledgeBaseService knowledgeBaseService;
    @Autowired KnowledgeMemberMapper memberMapper;
    @Autowired KnowledgeFolderMapper folderMapper;
    @Autowired KnowledgeDocumentMapper documentMapper;
    @Autowired KnowledgeDocumentVersionMapper documentVersionMapper;
    @Autowired KnowledgeUploadRequestMapper uploadRequestMapper;
    @Autowired KnowledgeDocumentUploadService uploadService;
    @Autowired OutboxEventMapper outboxMapper;
    @Autowired ObjectMapper objectMapper;

    @MockBean KnowledgeObjectStorageService objectStorageService;
    @SpyBean OutboxService outboxService;
    @SpyBean DocumentUploadValidator uploadValidator;

    @BeforeEach
    void resetCollaborators() {
        reset(objectStorageService, outboxService, uploadValidator);
    }

    @Test
    void upload_EnforcesAuthenticationMethodPermissionAndEditorRole() throws Exception {
        Long baseId = createBase(11L);
        addMember(baseId, 12L, 4); // VIEWER

        mockMvc.perform(upload(baseId, "auth-1")).andExpect(status().isUnauthorized());

        mockMvc.perform(upload(baseId, "auth-2").with(authentication(auth(11L))))
                .andExpect(status().isForbidden());

        mockMvc.perform(upload(baseId, "auth-3").with(authentication(authWithCreate(12L))))
                .andExpect(status().isForbidden());
        verify(uploadValidator, never()).completeWithChecksum(any(), any());
        verify(objectStorageService, never()).putObject(anyString(), any(MultipartFile.class), anyString());
        reset(uploadValidator, objectStorageService);

        mockMvc.perform(upload(baseId, "auth-4").with(authentication(authWithCreate(11L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void upload_AdminStillRequiresExistingBaseAndValidFolderOwnership() throws Exception {
        mockMvc.perform(upload(999999L, "admin-missing").with(authentication(admin(99L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_BASE_NOT_FOUND"));

        Long ownerBase = createBase(21L);
        Long otherBase = createBase(22L);
        KnowledgeFolder otherFolder = new KnowledgeFolder();
        otherFolder.setKnowledgeBaseId(otherBase);
        otherFolder.setParentId(0L);
        otherFolder.setName("other-folder-" + UUID.randomUUID());
        otherFolder.setSortNum(0);
        otherFolder.setCreatedBy(22L);
        otherFolder.setDeleted(0);
        folderMapper.insert(otherFolder);

        mockMvc.perform(upload(ownerBase, "cross-folder").param("folderId", otherFolder.getId().toString())
                        .with(authentication(admin(99L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FOLDER_NOT_IN_BASE"));
        verify(uploadValidator, never()).completeWithChecksum(any(), any());
        verify(objectStorageService, never()).putObject(anyString(), any(MultipartFile.class), anyString());
    }

    @Test
    void upload_RejectsInvalidFilesAndMismatchedContentType() throws Exception {
        Long baseId = createBase(31L);
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        mockMvc.perform(multipart("/api/knowledge/bases/{baseId}/documents/upload", baseId)
                        .file(empty).param("clientRequestId", "empty-file").with(authentication(authWithCreate(31L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_FILE"));

        mockMvc.perform(uploadFile(baseId, "unsupported", "bad.exe", "application/octet-stream", new byte[] {1})
                        .with(authentication(authWithCreate(31L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));

        mockMvc.perform(uploadFile(baseId, "type-mismatch", "file.pdf", "text/plain", new byte[] {1})
                        .with(authentication(authWithCreate(31L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTENT_TYPE_MISMATCH"));

        mockMvc.perform(uploadFile(baseId, "path-traversal", "../file.pdf", "application/pdf", new byte[] {1})
                        .with(authentication(authWithCreate(31L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILENAME"));

        for (String filename : List.of("file", ".pdf", "file.", "file.exe.pdf")) {
            mockMvc.perform(uploadFile(baseId, "bad-name-" + filename.hashCode(), filename, "application/pdf", new byte[] {1})
                            .with(authentication(authWithCreate(31L))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FILENAME"));
        }

        mockMvc.perform(uploadFile(baseId, "large", "large.pdf", "application/pdf", new byte[] {1, 2, 3, 4, 5})
                        .with(authentication(authWithCreate(31L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void upload_IsIdempotentGeneratesObjectKeyAndWritesCompleteEvent() throws Exception {
        Long baseId = createBase(41L);
        int documentsBefore = documentCount(baseId);
        int eventsBefore = eventCount();

        String response = mockMvc.perform(upload(baseId, "same-request").param("title", "My Document")
                        .with(authentication(authWithCreate(41L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
        Long documentId = objectMapper.readTree(response).path("data").asLong();

        mockMvc.perform(upload(baseId, "same-request").param("title", "My Document")
                        .with(authentication(authWithCreate(41L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(documentId));

        KnowledgeDocument document = documentMapper.selectById(documentId);
        assertThat(document.getStatus()).isEqualTo(2);
        assertThat(document.getObjectKey()).matches("knowledge/" + baseId + "/\\d{4}/\\d{2}/[0-9a-f-]{36}/file\\.pdf");
        assertThat(document.getChecksum()).hasSize(64);
        assertThat(documentCount(baseId)).isEqualTo(documentsBefore + 1);
        assertThat(eventCount()).isEqualTo(eventsBefore + 1);
        assertThat(documentVersionMapper.selectCount(new QueryWrapper<com.docbase.knowledge.document.domain.KnowledgeDocumentVersion>()
                .eq("document_id", documentId))).isEqualTo(1);
        assertThat(uploadRequestMapper.selectCount(new QueryWrapper<KnowledgeUploadRequest>()
                .eq("knowledge_base_id", baseId).eq("user_id", 41L).eq("client_request_id", "same-request")
                .eq("status", "COMPLETED"))).isEqualTo(1);
        verify(objectStorageService, times(1)).putObject(anyString(), any(MultipartFile.class), anyString());

        OutboxEntity event = outboxMapper.selectOne(new QueryWrapper<OutboxEntity>()
                .eq("aggregate_id", documentId.toString()).eq("event_type", "knowledge.document.registered"));
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.path("knowledgeBaseId").asLong()).isEqualTo(baseId);
        assertThat(payload.path("documentId").asLong()).isEqualTo(documentId);
        assertThat(payload.path("versionId").asLong()).isPositive();
        assertThat(payload.path("objectKey").asText()).isEqualTo(document.getObjectKey());
        assertThat(payload.path("fileName").asText()).isEqualTo("file.pdf");
        assertThat(payload.path("contentType").asText()).isEqualTo("application/pdf");
    }

    @Test
    void upload_CanExplicitlyKeepDocumentAsDraftAndPublishChoiceIsIdempotentMetadata() throws Exception {
        Long baseId = createBase(42L);
        String response = mockMvc.perform(upload(baseId, "draft-request").param("publishForChat", "false")
                        .with(authentication(authWithCreate(42L))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Long documentId = objectMapper.readTree(response).path("data").asLong();
        assertThat(documentMapper.selectById(documentId).getStatus()).isEqualTo(1);

        mockMvc.perform(upload(baseId, "draft-request").param("publishForChat", "true")
                        .with(authentication(authWithCreate(42L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void upload_RejectsReusedClientRequestIdWithDifferentMetadata() throws Exception {
        Long baseId = createBase(51L);
        mockMvc.perform(upload(baseId, "conflict-key").param("title", "first").with(authentication(authWithCreate(51L))))
                .andExpect(status().isOk());

        mockMvc.perform(upload(baseId, "conflict-key").param("title", "second").with(authentication(authWithCreate(51L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void upload_StorageAndRegistrationFailuresLeaveNoDocumentAndCompensate() throws Exception {
        Long baseId = createBase(61L);
        int documentsBefore = documentCount(baseId);
        doThrow(new BusinessException("OBJECT_STORAGE_UPLOAD_FAILED", "File upload failed"))
                .when(objectStorageService).putObject(anyString(), any(MultipartFile.class), anyString());
        mockMvc.perform(upload(baseId, "storage-failure").with(authentication(authWithCreate(61L))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OBJECT_STORAGE_UPLOAD_FAILED"));
        assertThat(documentCount(baseId)).isEqualTo(documentsBefore);

        reset(objectStorageService);
        doThrow(new RuntimeException("outbox unavailable")).when(outboxService).writeEvent(any());
        doThrow(new RuntimeException("cleanup unavailable")).when(objectStorageService).deleteObjectBestEffort(anyString());
        mockMvc.perform(upload(baseId, "registration-failure").with(authentication(authWithCreate(61L))))
                .andExpect(status().isInternalServerError());
        assertThat(documentCount(baseId)).isEqualTo(documentsBefore);
        verify(objectStorageService).deleteObjectBestEffort(anyString());
    }

    @Test
    void legacyJsonRegistrationRequiresInternalAuthorityAndKey() throws Exception {
        Long baseId = createBase(71L);
        mockMvc.perform(post("/api/knowledge/bases/{baseId}/documents", baseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"unsafe\",\"objectKey\":\"knowledge/other/object.pdf\"}")
                        .with(authentication(authWithCreate(71L))))
                .andExpect(status().isForbidden());

        mockMvc.perform(internalRegistration(baseId).with(authentication(internalAuth(71L))))
                .andExpect(status().isForbidden());
        mockMvc.perform(internalRegistration(baseId).header("X-Knowledge-Internal-Key", "wrong")
                        .with(authentication(internalAuth(71L))))
                .andExpect(status().isForbidden());
        mockMvc.perform(internalRegistration(baseId).header("X-Knowledge-Internal-Key", "test-internal-key")
                        .with(authentication(internalAuth(71L))))
                .andExpect(status().isOk());
        mockMvc.perform(internalRegistration(baseId).header("X-Knowledge-Internal-Key", "test-internal-key")
                        .with(authentication(admin(72L))))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentSameClientRequestPerformsOneUploadAndOneRegistration() throws Exception {
        Long baseId = createBase(81L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = List.of(executor.submit(() -> concurrentUpload(baseId, ready, start)),
                    executor.submit(() -> concurrentUpload(baseId, ready, start)));
            ready.await();
            start.countDown();
            for (Future<Object> future : futures) {
                Object result = future.get();
                assertThat(result).isInstanceOfAny(Long.class, BusinessException.class);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(documentCount(baseId)).isEqualTo(1);
        KnowledgeDocument document = documentMapper.selectOne(new QueryWrapper<KnowledgeDocument>().eq("knowledge_base_id", baseId));
        assertThat(documentVersionMapper.selectCount(new QueryWrapper<com.docbase.knowledge.document.domain.KnowledgeDocumentVersion>()
                .eq("document_id", document.getId()))).isEqualTo(1);
        assertThat(outboxMapper.selectCount(new QueryWrapper<OutboxEntity>().eq("aggregate_id", document.getId().toString())
                .eq("event_type", "knowledge.document.registered"))).isEqualTo(1);
        assertThat(uploadRequestMapper.selectCount(new QueryWrapper<KnowledgeUploadRequest>()
                .eq("knowledge_base_id", baseId).eq("user_id", 81L).eq("client_request_id", "concurrent-key")
                .eq("status", "COMPLETED"))).isEqualTo(1);
        verify(objectStorageService, times(1)).putObject(anyString(), any(MultipartFile.class), anyString());
    }

    private Long createBase(Long ownerId) {
        KnowledgeBase base = new KnowledgeBase();
        base.setName("upload-base-" + UUID.randomUUID());
        return knowledgeBaseService.create(base, ownerId);
    }

    private void addMember(Long baseId, Long userId, int role) {
        KnowledgeMember member = new KnowledgeMember();
        member.setKnowledgeBaseId(baseId);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setCreatedBy(userId);
        member.setDeleted(0);
        memberMapper.insert(member);
    }

    private int documentCount(Long baseId) {
        return Math.toIntExact(documentMapper.selectCount(new QueryWrapper<KnowledgeDocument>().eq("knowledge_base_id", baseId)));
    }

    private int eventCount() {
        return Math.toIntExact(outboxMapper.selectCount(new QueryWrapper<>()));
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(Long baseId, String requestId) {
        return uploadFile(baseId, requestId, "file.pdf", "application/pdf", new byte[] {1, 2, 3});
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder uploadFile(
            Long baseId, String requestId, String filename, String contentType, byte[] content) {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder builder =
                multipart("/api/knowledge/bases/{baseId}/documents/upload", baseId)
                        .file(new MockMultipartFile("file", filename, contentType, content));
        builder.param("clientRequestId", requestId);
        return builder;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder internalRegistration(Long baseId) {
        return post("/api/knowledge/bases/{baseId}/documents", baseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folderId\":0,\"title\":\"internal\",\"originalFilename\":\"file.pdf\","
                        + "\"objectKey\":\"knowledge/internal/file.pdf\",\"contentType\":\"application/pdf\",\"fileSize\":3}");
    }

    private Object concurrentUpload(Long baseId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            return uploadService.upload(baseId,
                    new MockMultipartFile("file", "file.pdf", "application/pdf", new byte[] {1, 2, 3}),
                    null, 0L, null, true, "concurrent-key", 81L, false);
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(new KnowledgeUserPrincipal(userId, "u" + userId, false, List.of()), null, List.of());
    }

    private UsernamePasswordAuthenticationToken authWithCreate(Long userId) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("knowledge:document:create");
        return new UsernamePasswordAuthenticationToken(new KnowledgeUserPrincipal(userId, "u" + userId, false, List.of(authority)), null, List.of(authority));
    }

    private UsernamePasswordAuthenticationToken internalAuth(Long userId) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("knowledge:document:register:internal");
        return new UsernamePasswordAuthenticationToken(new KnowledgeUserPrincipal(userId, "internal" + userId, false, List.of(authority)), null, List.of(authority));
    }

    private UsernamePasswordAuthenticationToken admin(Long userId) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("admin:all");
        return new UsernamePasswordAuthenticationToken(new KnowledgeUserPrincipal(userId, "admin" + userId, true, List.of(authority)), null, List.of(authority));
    }
}
