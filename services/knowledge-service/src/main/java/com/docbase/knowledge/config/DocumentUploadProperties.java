package com.docbase.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties("docbase.document-upload")
public class DocumentUploadProperties {
    /** A lease shorter than this is likely to expire while a normal upload is still in progress. */
    public static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(30);

    private DataSize maxFileSize = DataSize.ofMegabytes(100);
    private Map<String, String> allowedContentTypes = new LinkedHashMap<>();
    private String internalRegistrationApiKey = "";
    private Duration leaseDuration = Duration.ofMinutes(10);

    public DataSize getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(DataSize maxFileSize) { this.maxFileSize = maxFileSize; }
    public Map<String, String> getAllowedContentTypes() { return allowedContentTypes; }
    public void setAllowedContentTypes(Map<String, String> allowedContentTypes) {
        this.allowedContentTypes = new LinkedHashMap<>(allowedContentTypes);
    }
    public String getInternalRegistrationApiKey() { return internalRegistrationApiKey; }
    public void setInternalRegistrationApiKey(String internalRegistrationApiKey) {
        this.internalRegistrationApiKey = internalRegistrationApiKey;
    }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.compareTo(MIN_LEASE_DURATION) < 0) {
            throw new IllegalArgumentException("docbase.document-upload.lease-duration must be at least "
                    + MIN_LEASE_DURATION);
        }
        this.leaseDuration = leaseDuration;
    }
}
