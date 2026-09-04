package com.bloodlink.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void healthReturnsOkStatus() {
        assertThat(new HealthController().health())
            .containsEntry("status", "ok");
    }
}
