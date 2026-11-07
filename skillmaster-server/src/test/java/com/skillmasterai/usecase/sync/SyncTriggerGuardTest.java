package com.skillmasterai.usecase.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one gate between loopback and a write path that publishes into the official namespace. */
class SyncTriggerGuardTest {

    @Test
    void onlyTheExactTokenPermits() {
        SyncTriggerGuard guard = new SyncTriggerGuard("s3cret");

        assertThat(guard.permits("s3cret")).isTrue();
        assertThat(guard.permits("s3cres")).isFalse();
        assertThat(guard.permits("s3cret ")).isFalse();
        assertThat(guard.permits(null)).isFalse();
    }

    @Test
    void aBlankTokenMeansNotConfigured() {
        SyncTriggerGuard guard = new SyncTriggerGuard(" ");

        assertThat(guard.configured()).isFalse();
        assertThat(guard.permits(" ")).isFalse();
        assertThat(guard.permits(null)).isFalse();
    }
}
