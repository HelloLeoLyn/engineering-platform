package com.acme.core.common.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorTest {

    @Test
    void ofBuildsErrorBody() {
        ApiError error = ApiError.of(400, "VALIDATION_FAILED", "bad request");
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.message()).isEqualTo("bad request");
        assertThat(error.violations()).isEmpty();
    }
}
