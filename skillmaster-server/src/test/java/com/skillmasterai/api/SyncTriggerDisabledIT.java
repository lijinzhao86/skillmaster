package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.support.AbstractIT;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The off switch is the absence of a route, not a route that refuses (ADR 0159). */
class SyncTriggerDisabledIT extends AbstractIT {

    @Test
    void theEndpointDoesNotExistWhenSyncIsOff() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/internal/sync/run"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("X-Sync-Trigger", "anything")
                .build());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
