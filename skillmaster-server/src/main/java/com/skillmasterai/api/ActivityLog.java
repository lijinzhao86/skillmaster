package com.skillmasterai.api;

import com.skillmasterai.modules.activity.ActivityEvent;
import com.skillmasterai.modules.activity.InvokeActivity;
import com.skillmasterai.modules.activity.SearchActivity;
import com.skillmasterai.usecase.RecordActivityUseCase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Best-effort recording for the HTTP planes (v58).
 *
 * <p>Every controller call goes through here so the failure rule has one home: a write that fails
 * loses one row and is otherwise invisible. The timeout of a read is decided by the read, never
 * by the history beside it.
 *
 * <p><strong>Invisible to the caller, not to the operator.</strong> "Best effort" is a promise
 * about the response, not a licence to fail silently: a missing partition or a broken connection
 * would otherwise turn the whole page empty with no line in any log. One warning a minute is
 * enough to notice it without letting a broken database flood the log with one stack per request.
 */
@Component
public class ActivityLog {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityLog.class);

    private static final long WARN_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final RecordActivityUseCase useCase;

    // Initialised to "one interval ago", not 0: `System.nanoTime()` has an arbitrary origin and may
    // be negative, and then `now - 0` would look inside the throttle window and swallow the very
    // first failure.
    private long lastWarnAt = System.nanoTime() - WARN_INTERVAL_NANOS;

    public ActivityLog(RecordActivityUseCase useCase) {
        this.useCase = useCase;
    }

    public void record(ActivityEvent event) {
        try {
            useCase.record(event);
        } catch (RuntimeException e) {
            warn(event.kind(), e);
        }
    }

    public void search(SearchActivity search) {
        try {
            useCase.recordSearch(search);
        } catch (RuntimeException e) {
            warn("search", e);
        }
    }

    public void invoke(InvokeActivity invoke) {
        try {
            useCase.recordInvoke(invoke);
        } catch (RuntimeException e) {
            warn("invoke", e);
        }
    }

    private synchronized void warn(String what, RuntimeException failure) {
        long now = System.nanoTime();
        if (now - lastWarnAt < WARN_INTERVAL_NANOS) {
            return;
        }
        lastWarnAt = now;
        LOG.warn("activity write ({}) failed; that row is lost, the request is unaffected", what,
                failure);
    }
}
