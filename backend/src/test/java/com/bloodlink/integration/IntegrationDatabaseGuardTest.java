package com.bloodlink.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests the bloodlink_dev guard without starting anything; runs in the ordinary mvnw test.
 */
class IntegrationDatabaseGuardTest {

    private final IntegrationDatabaseGuard guard = new IntegrationDatabaseGuard();

    /** An environment as the guard would see it: the given profiles and datasource URL. */
    private static MockEnvironment environment(String url, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        if (url != null) {
            environment.setProperty("spring.datasource.url", url);
        }
        return environment;
    }

    @Test
    void theTestProfileOnBloodlinkTestIsAllowed() {
        assertThatCode(() -> guard.postProcessEnvironment(
            environment("jdbc:postgresql://localhost:5432/bloodlink_test", "test"), null))
            .doesNotThrowAnyException();
    }

    @Test
    void theRaceTestsOwnThrowawayDatabaseIsAllowed() {
        assertThatCode(() -> guard.postProcessEnvironment(
            environment("jdbc:postgresql://localhost:5432/bloodlink_race_test", "test"), null))
            .doesNotThrowAnyException();
    }

    @Test
    void theBootstrapRaceTestsOwnThrowawayDatabaseIsAllowed() {
        assertThatCode(() -> guard.postProcessEnvironment(
            environment("jdbc:postgresql://localhost:5432/bloodlink_bootstrap_race_test", "test"), null))
            .doesNotThrowAnyException();
    }

    @Test
    void theTestProfileOnBloodlinkDevIsRefused() {
        // The case this exists for: SPRING_DATASOURCE_URL left set to the real database.
        assertThatThrownBy(() -> guard.postProcessEnvironment(
            environment("jdbc:postgresql://localhost:5432/bloodlink_dev", "test"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("\"bloodlink_dev\"")
            .hasMessageContaining("SPRING_DATASOURCE_URL");
    }

    @Test
    void anyOtherDatabaseIsRefusedToo() {
        // An allowed list, not a blocked one: bloodlink_demo or a renamed real database
        // is refused just the same.
        assertThatThrownBy(() -> guard.postProcessEnvironment(
            environment("jdbc:postgresql://db:5432/bloodlink_demo?sslmode=disable", "test"), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("\"bloodlink_demo\"");
    }

    @Test
    void startsWithoutTheTestProfileAreNotItsBusiness() {
        // The server itself runs against bloodlink_dev on purpose; only test-profile
        // starts are checked.
        assertThatCode(() -> guard.postProcessEnvironment(
            environment("jdbc:postgresql://localhost:5432/bloodlink_dev"), null))
            .doesNotThrowAnyException();
        assertThatCode(() -> guard.postProcessEnvironment(
            environment("jdbc:postgresql://localhost:5432/bloodlink_dev", "demo"), null))
            .doesNotThrowAnyException();
    }

    @Test
    void noDatasourceAtAllIsNothingToGuard() {
        assertThatCode(() -> guard.postProcessEnvironment(environment(null, "test"), null))
            .doesNotThrowAnyException();
    }

    @Test
    void theDatabaseNameIsReadFromTheUrlPath() {
        assertThat(IntegrationDatabaseGuard.databaseName("jdbc:postgresql://localhost:5432/bloodlink_test"))
            .isEqualTo("bloodlink_test");
        assertThat(IntegrationDatabaseGuard.databaseName("jdbc:postgresql://h/bloodlink_dev?user=x&ssl=true"))
            .isEqualTo("bloodlink_dev");
    }
}
