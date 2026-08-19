package com.docbase.iam.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AdminSetupPropertiesTest {

    @Test
    void blankKeyKeepsAnonymousSetupDisabled() {
        assertThat(new AdminSetupProperties("").enabled()).isFalse();
    }

    @Test
    void strongKeyEnablesAnonymousSetup() {
        assertThat(new AdminSetupProperties("a-secure-operator-key-with-32-chars").enabled()).isTrue();
    }

    @Test
    void weakOrOversizedKeysFailClosed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminSetupProperties("too-short"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AdminSetupProperties("x".repeat(257)));
    }
}
