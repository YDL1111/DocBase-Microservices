package com.docbase.knowledge.storage;

import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** MinIO access for knowledge documents. It never exposes storage implementation details to callers. */
@Service
public class KnowledgeObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeObjectStorageService.class);
    private static final long PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public KnowledgeObjectStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public void putObject(String objectKey, MultipartFile file, String contentType) {
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), PART_SIZE)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            log.warn("Knowledge document object upload failed");
            throw new BusinessException("OBJECT_STORAGE_UPLOAD_FAILED", "File upload failed");
        }
    }

    /** Best-effort compensation. The original business exception must remain authoritative. */
    public void deleteObjectBestEffort(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            log.error("Knowledge document compensation deletion failed for objectId={}", objectIdentifier(objectKey));
        }
    }

    private String objectIdentifier(String objectKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            return "unavailable";
        }
    }
}
