package com.bloodlink.integration;

import java.util.Arrays;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start any application under the "test" profile whose database is not
 * one of the integration tests' own: bloodlink_dev holds the project owner's real
 * data, and a test run must never be able to reach it (CLAUDE.md, Conventions).
 *
 * <h2>The gap this closes</h2>
 * The integration tests find bloodlink_test through application-test.properties.
 * But an environment variable outranks every properties file, so a developer who
 * set {@code SPRING_DATASOURCE_URL} permanently (to run the server against
 * bloodlink_dev, say) would send {@code mvnw test "-Dtest=*IT"} to that database
 * instead, silently. The tests roll back what they write, but Flyway would still
 * migrate it, and a database the owner did not choose would be the one under test.
 * This check turns that into a refusal before any connection is opened.
 *
 * Runs after Spring Boot has loaded every properties file and resolved the active
 * profiles (lowest precedence among post-processors), so it sees the URL the
 * application would really use. Registered in src/test/resources/META-INF/
 * spring.factories, so it exists only on the test classpath and never in the jar.
 *
 * PartialFulfilmentRaceIT's own throwaway database is set later, by
 * {@code @DynamicPropertySource}; at this point that class still resolves to
 * bloodlink_test, and its database is allowed here in case that ever changes.
 */
public class IntegrationDatabaseGuard implements EnvironmentPostProcessor, Ordered {

    /** The only databases a "test" profile start may use. */
    static final Set<String> ALLOWED_DATABASES = Set.of("bloodlink_test", "bloodlink_race_test");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            return;
        }
        String url = environment.getProperty("spring.datasource.url");
        if (url == null) {
            return; // nothing will connect to a database at all
        }

        String database = databaseName(url);
        if (!ALLOWED_DATABASES.contains(database)) {
            throw new IllegalStateException(
                "Refusing to run under the \"test\" profile against database \"" + database + "\". "
                    + "The integration tests may only use " + ALLOWED_DATABASES + ". Something outside "
                    + "application-test.properties is overriding the datasource URL: most likely a "
                    + "SPRING_DATASOURCE_URL environment variable. Remove it from this terminal "
                    + "(PowerShell: Remove-Item Env:SPRING_DATASOURCE_URL) and run the tests again.");
        }
    }

    /** The database name in a JDBC URL: what follows the last "/", before any "?". */
    static String databaseName(String jdbcUrl) {
        String path = jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1);
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
