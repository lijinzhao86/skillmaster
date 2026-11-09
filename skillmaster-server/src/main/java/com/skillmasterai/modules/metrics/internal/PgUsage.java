package com.skillmasterai.modules.metrics.internal;

import com.skillmasterai.modules.metrics.SkillLoad;
import com.skillmasterai.modules.metrics.UsageErasure;
import com.skillmasterai.modules.metrics.UsageQuery;
import com.skillmasterai.modules.metrics.UsageRecorder;
import com.skillmasterai.modules.metrics.UsageScope;
import com.skillmasterai.modules.metrics.UsageSnapshot;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;

/** Both published edges over one repository; no policy lives here. */
public final class PgUsage implements UsageRecorder, UsageQuery, UsageErasure {

    private final UsageRepository repository;

    public PgUsage(UsageRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(SkillLoad load) {
        repository.record(load);
    }

    @Override
    public Map<String, UsageSnapshot> snapshots(Collection<String> skillIds, UsageScope scope) {
        return repository.snapshots(skillIds, scope, LocalDate.now(ZoneOffset.UTC));
    }

    @Override
    public void deleteAllFor(String accountId) {
        repository.deleteAllFor(accountId);
    }
}
