package com.bloodlink.admin.bootstrap;

import java.util.ArrayList;
import java.util.List;

/**
 * The four startup settings that create the first administrator (Business Rule 27), as read from the environment.
 * toString never shows the password, so the settings can never leak into a log by being printed.
 */
public record BootstrapAdminSettings(String phone, String fullName, String email, String password) {

    public static final String PHONE_VARIABLE = "BLOODLINK_BOOTSTRAP_ADMIN_PHONE";
    public static final String NAME_VARIABLE = "BLOODLINK_BOOTSTRAP_ADMIN_NAME";
    public static final String EMAIL_VARIABLE = "BLOODLINK_BOOTSTRAP_ADMIN_EMAIL";
    public static final String PASSWORD_VARIABLE = "BLOODLINK_BOOTSTRAP_ADMIN_PASSWORD";

    /** The variables left unset or blank, in a fixed order; empty when all four are present. */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (isBlank(phone)) missing.add(PHONE_VARIABLE);
        if (isBlank(fullName)) missing.add(NAME_VARIABLE);
        if (isBlank(email)) missing.add(EMAIL_VARIABLE);
        if (isBlank(password)) missing.add(PASSWORD_VARIABLE);
        return missing;
    }

    /** True when at least one of the four is set, so ignoring them is worth mentioning. */
    public boolean anySet() {
        return missing().size() < 4;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "BootstrapAdminSettings[phone=" + phone + ", fullName=" + fullName + ", email=" + email
            + ", password=" + (isBlank(password) ? "(unset)" : "(set, hidden)") + "]";
    }
}
