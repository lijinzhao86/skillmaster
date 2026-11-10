package com.skillmasterai.usecase;

import com.skillmasterai.modules.activity.ActivityEvent;
import com.skillmasterai.modules.activity.ActivityRecorder;
import com.skillmasterai.modules.activity.InvokeActivity;
import com.skillmasterai.modules.activity.SearchActivity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one usage event in a transaction of its own (v58).
 *
 * <p>The read path calls this after its own transaction has closed and swallows failures: usage
 * history is a side channel and must never decide whether a body is readable (ADR 0179). The
 * {@code REQUIRES_NEW} boundary is what keeps the two apart — a failed write rolls back only its
 * own statement.
 *
 * <p><strong>The timestamp is supplied by the caller.</strong> Controllers pass
 * {@code Timestamps.now()}; tests pass a pinned instant, which is the only way to exercise the
 * 30-minute window without sleeping.
 */
@Component
public class RecordActivityUseCase {

    private final ActivityRecorder recorder;

    public RecordActivityUseCase(ActivityRecorder recorder) {
        this.recorder = recorder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ActivityEvent event) {
        recorder.record(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSearch(SearchActivity search) {
        recorder.recordSearch(search);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInvoke(InvokeActivity invoke) {
        recorder.recordInvoke(invoke);
    }
}
