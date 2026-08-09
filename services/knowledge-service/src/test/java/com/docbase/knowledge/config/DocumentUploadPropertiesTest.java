package com.docbase.knowledge.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentUploadPropertiesTest {

    @Test
    void leaseDurationRejectsZeroNegativeAndUnsafeShortValues() {
        DocumentUploadProperties properties = new DocumentUploadProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setLeaseDuration(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setLeaseDuration(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setLeaseDuration(Duration.ofSeconds(29)));
    }

    @Test
    void leaseDurationAcceptsConfiguredMinimum() {
        DocumentUploadProperties properties = new DocumentUploadProperties();

        properties.setLeaseDuration(DocumentUploadProperties.MIN_LEASE_DURATION);

        assertThat(properties.getLeaseDuration()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void invalidLeaseDurationFailsConfigurationBinding() {
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(Map.of(
                "docbase.document-upload.lease-duration", "PT0S"))).bind(
                "docbase.document-upload", Bindable.of(DocumentUploadProperties.class)))
                .isInstanceOf(BindException.class);
    }
}
