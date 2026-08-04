package com.docbase.iam.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Optional first-admin initialization. Disabled by default; enabled via
 * iam.init-admin.enabled=true. Reads the initial credentials from environment
 * variables. Idempotent: does not overwrite an existing admin account.
 */
@Component
public class AdminInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${iam.init-admin.enabled:false}")
    private boolean enabled;

    @Value("${iam.init-admin.username:admin}")
    private String username;

    @Value("${iam.init-admin.password:}")
    private String password;

    public AdminInitializer(SysUserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
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
        long count = userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", username));
        if (count > 0) {
            log.info("Admin user '{}' already exists; skipping initialization", username);
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setNickname("Administrator");
        admin.setPassword(passwordEncoder.encode(password));
        admin.setStatus(1);
        admin.setIsAdmin(1);
        admin.setRemark("auto-initialized admin");
        userMapper.insert(admin);
        log.info("Initial admin user '{}' created. Disable iam.init-admin.enabled after first boot.", username);
    }
}
