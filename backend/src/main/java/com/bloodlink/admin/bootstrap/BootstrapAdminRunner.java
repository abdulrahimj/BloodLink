package com.bloodlink.admin.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs the first-administrator bootstrap once at every startup (Business Rule 27); a failure is logged, never fatal.
 * Switched off by bloodlink.bootstrap-admin.enabled=false, which the integration tests set.
 */
@Component
@ConditionalOnProperty(name = "bloodlink.bootstrap-admin.enabled", havingValue = "true", matchIfMissing = true)
public class BootstrapAdminRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdminService bootstrapAdminService;
    private final BootstrapAdminSettings settings;

    // Each falls back to its environment variable, so a local application.properties without these lines still works.
    BootstrapAdminRunner(
        BootstrapAdminService bootstrapAdminService,
        @Value("${bloodlink.bootstrap-admin.phone:${BLOODLINK_BOOTSTRAP_ADMIN_PHONE:}}") String phone,
        @Value("${bloodlink.bootstrap-admin.name:${BLOODLINK_BOOTSTRAP_ADMIN_NAME:}}") String fullName,
        @Value("${bloodlink.bootstrap-admin.email:${BLOODLINK_BOOTSTRAP_ADMIN_EMAIL:}}") String email,
        @Value("${bloodlink.bootstrap-admin.password:${BLOODLINK_BOOTSTRAP_ADMIN_PASSWORD:}}") String password
    ) {
        this.bootstrapAdminService = bootstrapAdminService;
        this.settings = new BootstrapAdminSettings(phone, fullName, email, password);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createFirstAdminIfNeeded() {
        try {
            bootstrapAdminService.createFirstAdminIfNeeded(settings);
        } catch (RuntimeException ex) {
            // Only the exception type: a database error's text can quote the row, password hash included.
            log.error("Creating the first administrator from the BLOODLINK_BOOTSTRAP_ADMIN_* settings failed ({}), "
                + "so none was created. The application has started normally; check the settings and restart.",
                ex.getClass().getSimpleName());
        }
    }
}
