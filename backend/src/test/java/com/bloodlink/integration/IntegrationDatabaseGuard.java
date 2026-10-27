package com.bloodlink.integration;

import java.util.Arrays;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses any "test" profile start whose database is not an integration-test one, so bloodlink_dev can't be reached
 * even if SPRING_DATASOURCE_URL is set. Test classpath only, registered in META-INF/spring.factories.
 */
public class IntegrationDatabaseGuard implements EnvironmentPostProcessor, Ordered {

    /** The only databases a "test" profile start may use. */
    static final Set<String> ALLOWED_DATABASES =
        Set.of("bloodlink_test", "bloodlink_race_test", "bloodlink_bootstrap_race_test");

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
