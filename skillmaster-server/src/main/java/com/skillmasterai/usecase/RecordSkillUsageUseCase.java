package com.skillmasterai.usecase;

import com.skillmasterai.modules.metrics.SkillLoad;
import com.skillmasterai.modules.metrics.UsageRecorder;
import com.skillmasterai.modules.metrics.UsageScope;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one public use signal in a transaction of its own.
 *
 * <p>The read path calls this and swallows failures: metrics are a side channel and must never
 * decide whether a body is readable (ADR 0153). {@code REQUIRES_NEW} is what keeps the two cases
 * apart — a metrics failure rolls back only its own statement, and the read transaction is never
 * the one that writes.
 */
@Component
public class RecordSkillUsageUseCase {

    private final UsageRecorder recorder;

    public RecordSkillUsageUseCase(UsageRecorder recorder) {
        this.recorder = recorder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String skillId, String accountId) {
        recorder.record(new SkillLoad(skillId, accountId,
                LocalDate.now(ZoneOffset.UTC), UsageScope.PUBLIC));
    }
}
