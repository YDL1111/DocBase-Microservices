package com.docbase.knowledge.document.service;

import com.docbase.common.core.BusinessException;
import com.docbase.knowledge.config.DocumentUploadProperties;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class DocumentUploadValidator {
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern CLIENT_REQUEST_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Set<String> DANGEROUS_PENULTIMATE_EXTENSIONS = Set.of(
            "exe", "dll", "bat", "cmd", "com", "msi", "sh", "ps1", "js", "jar", "zip", "rar", "7z");

    private final DocumentUploadProperties properties;
    private final Clock clock;

    @Autowired
    public DocumentUploadValidator(DocumentUploadProperties properties) {
        this(properties, Clock.systemUTC());
    }

    DocumentUploadValidator(DocumentUploadProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public UploadMetadata validateMetadata(MultipartFile file, String requestedTitle, Long folderId,
                                           Integer visibility, String clientRequestId, Long knowledgeBaseId) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException("EMPTY_FILE", "File must not be empty");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new BusinessException("FILE_TOO_LARGE", "File exceeds the configured size limit");
        }

        String originalFilename = validateFilename(file.getOriginalFilename());
        String extension = extensionOf(originalFilename);
        String contentType = normalizeContentType(file.getContentType());
        String configuredContentType = properties.getAllowedContentTypes().get(extension);
        if (configuredContentType == null) {
            throw new BusinessException("UNSUPPORTED_FILE_TYPE", "Unsupported file extension");
        }
        if (!configuredContentType.equalsIgnoreCase(contentType)) {
            throw new BusinessException("CONTENT_TYPE_MISMATCH", "File content type does not match its extension");
        }
        if (!CLIENT_REQUEST_ID.matcher(clientRequestId == null ? "" : clientRequestId).matches()) {
            throw new BusinessException("INVALID_CLIENT_REQUEST_ID", "clientRequestId is invalid");
        }
        if (visibility != null && (visibility < 1 || visibility > 3)) {
            throw new BusinessException("INVALID_VISIBILITY", "visibility is invalid");
        }

        String title = normalizeTitle(requestedTitle, originalFilename);
        String safeFilename = safeFilename(originalFilename, extension);
        int actualVisibility = visibility != null ? visibility : 1;
        long actualFolderId = folderId != null ? folderId : 0L;
        return new UploadMetadata(title, actualFolderId, actualVisibility, originalFilename, safeFilename,
                contentType, file.getSize(), clientRequestId, knowledgeBaseId);
    }

    /** Must only run after resource authorization, because it reads the upload stream. */
    public ValidatedUpload completeWithChecksum(MultipartFile file, UploadMetadata metadata) {
        String checksum = sha256(file);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        String objectKey = "knowledge/%d/%04d/%02d/%s/%s".formatted(
                metadata.knowledgeBaseId(), today.getYear(), today.getMonthValue(), UUID.randomUUID(), metadata.safeFilename());
        String fingerprint = fingerprint(metadata.title(), metadata.folderId(), metadata.visibility(), metadata.originalFilename(),
                metadata.contentType(), metadata.fileSize(), checksum);
        return new ValidatedUpload(metadata.title(), metadata.folderId(), metadata.visibility(), metadata.originalFilename(),
                metadata.safeFilename(), metadata.contentType(), metadata.fileSize(), checksum, metadata.clientRequestId(), objectKey, fingerprint);
    }

    private String validateFilename(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("INVALID_FILENAME", "File name is required");
        }
        String filename = value.trim();
        if (filename.length() > 200 || filename.startsWith("/") || filename.startsWith("\\")
                || WINDOWS_ABSOLUTE_PATH.matcher(filename).matches() || filename.contains("../")
                || filename.contains("..\\") || CONTROL_CHARACTER.matcher(filename).find()
                || filename.contains("/") || filename.contains("\\")) {
            throw new BusinessException("INVALID_FILENAME", "File name is invalid");
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new BusinessException("INVALID_FILENAME", "File name is invalid");
        }
        String extension = extensionOf(filename);
        String stem = filename.substring(0, dot);
        int penultimateDot = stem.lastIndexOf('.');
        if (penultimateDot >= 0 && DANGEROUS_PENULTIMATE_EXTENSIONS.contains(
                stem.substring(penultimateDot + 1).toLowerCase(Locale.ROOT))) {
            throw new BusinessException("INVALID_FILENAME", "File name is invalid");
        }
        if (extension.isBlank() || stem.isBlank()) {
            throw new BusinessException("INVALID_FILENAME", "File name is invalid");
        }
        return filename;
    }

    private String normalizeContentType(String rawContentType) {
        if (!StringUtils.hasText(rawContentType)) {
            throw new BusinessException("CONTENT_TYPE_MISMATCH", "File content type is required");
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(rawContentType);
            return mediaType.getType().toLowerCase(Locale.ROOT) + "/" + mediaType.getSubtype().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("CONTENT_TYPE_MISMATCH", "File content type is invalid");
        }
    }

    private String normalizeTitle(String title, String filename) {
        String candidate = StringUtils.hasText(title) ? title.trim() : filename.substring(0, filename.lastIndexOf('.')).trim();
        if (!StringUtils.hasText(candidate) || candidate.length() > 256 || CONTROL_CHARACTER.matcher(candidate).find()) {
            throw new BusinessException("INVALID_TITLE", "title is invalid");
        }
        return candidate;
    }

    private String safeFilename(String originalFilename, String extension) {
        String stem = originalFilename.substring(0, originalFilename.length() - extension.length() - 1)
                .replaceAll("[^\\p{L}\\p{N}._ -]", "_")
                .replaceAll("[ .]+$", "");
        if (!StringUtils.hasText(stem)) {
            stem = "document";
        }
        return stem + "." + extension;
    }

    private String extensionOf(String filename) {
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = file.getInputStream(); DigestInputStream digestStream = new DigestInputStream(inputStream, digest)) {
                byte[] buffer = new byte[8192];
                while (digestStream.read(buffer) != -1) {
                    // Stream the multipart temporary file; never buffer its content in memory.
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        } catch (Exception exception) {
            throw new BusinessException("FILE_READ_FAILED", "File could not be read");
        }
    }

    private String fingerprint(String title, long folderId, int visibility, String filename,
                               String contentType, long fileSize, String checksum) {
        String canonical = String.join("\n", title, Long.toString(folderId), Integer.toString(visibility),
                filename, contentType, Long.toString(fileSize), checksum);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    public record ValidatedUpload(String title, long folderId, int visibility, String originalFilename,
                                  String safeFilename, String contentType, long fileSize, String checksum,
                                  String clientRequestId, String objectKey, String fingerprint) {
    }

    public record UploadMetadata(String title, long folderId, int visibility, String originalFilename,
                                 String safeFilename, String contentType, long fileSize, String clientRequestId,
                                 Long knowledgeBaseId) {
    }
}
