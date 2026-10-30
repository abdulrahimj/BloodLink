package com.bloodlink.demo;

import java.util.ArrayList;
import java.util.List;

import com.bloodlink.common.PasswordPolicy;

/**
 * The hosted demo's two secret passwords: one for the demo administrator, one for every other demo account.
 * Pure decision logic, free of Spring and JDBC, so it is tested directly; never prints a password.
 */
record HostedDemoPasswordSettings(String password, String adminPassword) {

    static final String PASSWORD_VARIABLE = "BLOODLINK_DEMO_PASSWORD";
    static final String ADMIN_PASSWORD_VARIABLE = "BLOODLINK_DEMO_ADMIN_PASSWORD";

    /** The password V900 and V901 give every demo account, readable by anyone in the repository. */
    static final String PUBLISHED_PASSWORD = "BloodLink@2026";

    /** Neither variable set: the local demo, which keeps the published password. */
    boolean noneSet() {
        return isBlank(password) && isBlank(adminPassword);
    }

    /**
     * Every reason the settings cannot be used, empty when they can. With none set that is fine on a local-only
     * profile and refused on any other, since the published password would then open accounts on a public site.
     */
    List<String> problems(boolean localOnly) {
        List<String> problems = new ArrayList<>();
        if (noneSet()) {
            if (!localOnly) {
                problems.add("the demo data is running under a non-local profile, so " + PASSWORD_VARIABLE + " and "
                    + ADMIN_PASSWORD_VARIABLE + " must both be set to replace the published demo password");
            }
            return problems;
        }

        addProblems(problems, PASSWORD_VARIABLE, password);
        addProblems(problems, ADMIN_PASSWORD_VARIABLE, adminPassword);
        if (problems.isEmpty() && password.equals(adminPassword)) {
            problems.add(PASSWORD_VARIABLE + " and " + ADMIN_PASSWORD_VARIABLE
                + " must differ, so the shared demo password does not also open the administrator account");
        }
        return problems;
    }

    private static void addProblems(List<String> problems, String variable, String value) {
        if (isBlank(value)) {
            problems.add(variable + " is not set");
            return;
        }
        String policyProblem = PasswordPolicy.problemWith(value);
        if (policyProblem != null) {
            problems.add(variable + " is refused: " + policyProblem);
        } else if (PUBLISHED_PASSWORD.equals(value)) {
            problems.add(variable + " is the published demo password, which anyone can read in the repository");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "HostedDemoPasswordSettings[password=" + (isBlank(password) ? "unset" : "set")
            + ", adminPassword=" + (isBlank(adminPassword) ? "unset" : "set") + "]";
    }
}
