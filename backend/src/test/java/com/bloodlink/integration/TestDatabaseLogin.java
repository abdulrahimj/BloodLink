package com.bloodlink.integration;

import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The test database login as the test profile resolves it, for the ITs that open their own JDBC connections:
 * application-test.properties, with its ${NAME:default} placeholders filled in the way Spring fills them.
 */
final class TestDatabaseLogin {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+):([^}]*)}");
    private static final Properties PROPERTIES = load();

    private TestDatabaseLogin() {}

    /** The server's own "postgres" database, next to bloodlink_test, for creating and dropping throwaway ones. */
    static String serverUrl() {
        String testUrl = resolved("spring.datasource.url");
        return testUrl.substring(0, testUrl.lastIndexOf('/')) + "/postgres";
    }

    static String username() {
        return resolved("spring.datasource.username");
    }

    static String password() {
        return resolved("spring.datasource.password");
    }

    /** A system property, then an environment variable, then the default after the colon: Spring's order. */
    private static String resolved(String key) {
        String value = PROPERTIES.getProperty(key);
        Matcher placeholder = PLACEHOLDER.matcher(value);
        if (!placeholder.matches()) {
            return value;
        }
        String name = placeholder.group(1);
        return System.getProperty(name, Objects.requireNonNullElse(System.getenv(name), placeholder.group(2)));
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = TestDatabaseLogin.class.getResourceAsStream("/application-test.properties")) {
            properties.load(in);
        } catch (Exception cannotRead) {
            throw new IllegalStateException("application-test.properties is required for the integration tests", cannotRead);
        }
        return properties;
    }
}
