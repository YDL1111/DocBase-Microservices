package com.docbase.iam.user;

import com.docbase.common.core.BusinessException;
import com.docbase.iam.auth.AdminSetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Optional first-admin initialization. Disabled by default; enabled via
 * iam.init-admin.enabled=true. Reads the initial credentials from environment
 * variables. The actual creation is delegated to {@link AdminSetupService},
 * which uses the database mutex and assigns all active system roles.
 */
@Component
public class AdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final AdminSetupService adminSetupService;

    @Value("${iam.init-admin.enabled:false}")
    private boolean enabled;

    @Value("${iam.init-admin.username:admin}")
    private String username;

    @Value("${iam.init-admin.password:}")
    private String password;

    public AdminInitializer(AdminSetupService adminSetupService) {
        this.adminSetupService = adminSetupService;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        if (password == null || password.isBlank()) {
            log.warn("iam.init-admin.enabled=true but no password set; skipping admin initialization");
            return;
        }
        try {
            adminSetupService.initializeFromEnvironment(username, "Administrator", password);
            log.info("Initial admin user '{}' created. Disable iam.init-admin.enabled after first boot.", username);
        } catch (BusinessException exception) {
            if ("ADMIN_SETUP_CLOSED".equals(exception.code())) {
                log.info("An active administrator already exists; skipping initialization");
                return;
            }
            throw exception;
        }
    }
}
