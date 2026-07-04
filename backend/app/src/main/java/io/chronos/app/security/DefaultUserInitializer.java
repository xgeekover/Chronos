package io.chronos.app.security;

import io.chronos.app.persistence.AppUserEntity;
import io.chronos.app.persistence.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds default users on first start when {@code app_user} is empty (§11). The admin credentials
 * come from config ({@code chronos.security.user/password}, default admin/admin); operator/viewer
 * demo accounts are added so the three RBAC roles can be exercised. Change/disable in production.
 */
@Component
public class DefaultUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserInitializer.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String adminUser;
    private final String adminPassword;

    public DefaultUserInitializer(AppUserRepository users, PasswordEncoder encoder,
            @Value("${chronos.security.user:admin}") String adminUser,
            @Value("${chronos.security.password:admin}") String adminPassword) {
        this.users = users;
        this.encoder = encoder;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        create(adminUser, adminPassword, "ADMIN");
        create("operator", "operator", "OPERATOR");
        create("viewer", "viewer", "VIEWER");
        log.info("Seeded default users: {} (ADMIN), operator (OPERATOR), viewer (VIEWER)", adminUser);
    }

    private void create(String username, String password, String role) {
        AppUserEntity u = new AppUserEntity();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u.setEnabled(true);
        users.save(u);
    }
}
