package com.skillmasterai.api;

import com.skillmasterai.usecase.sync.SyncBusyException;
import com.skillmasterai.usecase.sync.SyncReport;
import com.skillmasterai.usecase.sync.SyncRunner;
import com.skillmasterai.usecase.sync.SyncTriggerGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The loopback trigger (ADR 0159).
 *
 * <p>No CLI and no console: the operation is "run the sync now", and the operator who needs it is
 * deploying. GitHub Actions calls it over SSH when the deployment host is the only place a
 * credential already lives. nginx answers 404 for {@code /internal/} from outside, so the endpoint
 * is reachable only from the host itself.
 *
 * <p>The controller does two things: compare the token and call the runner. Everything else — the
 * guard against a concurrent run, the report shape — belongs to the use case.
 *
 * <p><strong>Registered when sync is enabled; usable only when a token is set.</strong> The guard
 * bean exists whenever sync is enabled; it reports {@code configured() == false} for a blank
 * token, so the same class answers 404 when the token is blank and 401 when one is wrong.
 */
@RestController
@RequestMapping("/internal/sync")
@ConditionalOnProperty(name = "skillmaster.sync.enabled", havingValue = "true")
public class SyncTriggerController {

    private static final Logger LOG = LoggerFactory.getLogger(SyncTriggerController.class);

    private final SyncRunner runner;
    private final ObjectProvider<SyncTriggerGuard> guard;

    public SyncTriggerController(SyncRunner runner, ObjectProvider<SyncTriggerGuard> guard) {
        this.runner = runner;
        this.guard = guard;
    }

    @PostMapping("/run")
    ResponseEntity<?> run(@RequestHeader(value = "X-Sync-Trigger", required = false) String token) {
        SyncTriggerGuard present = guard.getIfAvailable();
        if (present == null || !present.configured()) {
            // The TD's "token empty means no endpoint": a 404 is the observable form of that.
            return ResponseEntity.notFound().build();
        }
        if (!present.permits(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));
        }
        try {
            SyncReport report = runner.runOnce();
            return ResponseEntity.ok(report);
        } catch (SyncBusyException busy) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "sync_already_running"));
        } catch (RuntimeException failure) {
            LOG.error("manual sync run failed", failure);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "sync_failed"));
        }
    }
}
