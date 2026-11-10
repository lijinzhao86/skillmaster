package com.skillmasterai.modules.activity.internal;

import com.skillmasterai.modules.activity.ActivityEvent;
import com.skillmasterai.modules.activity.ActivityRecorder;
import com.skillmasterai.modules.activity.InvokeActivity;
import com.skillmasterai.modules.activity.SearchActivity;
import com.skillmasterai.modules.activity.UsageHistory;

/** M17's published edges over one repository; no policy lives here. */
public final class PgActivity implements ActivityRecorder, UsageHistory {

    private final ActivityRepository repository;

    public PgActivity(ActivityRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(ActivityEvent event) {
        repository.record(event);
    }

    @Override
    public void recordSearch(SearchActivity search) {
        repository.recordSearch(search);
    }

    @Override
    public void recordInvoke(InvokeActivity invoke) {
        repository.recordInvoke(invoke);
    }

    @Override
    public Page interactions(String actorUserId, int page, int limit, Window window) {
        return repository.interactions(actorUserId, page, limit, window);
    }

    @Override
    public boolean hasAny(String actorUserId) {
        return repository.hasAny(actorUserId);
    }

    @Override
    public Stats stats(String actorUserId, Window window) {
        return repository.stats(actorUserId, window);
    }

    @Override
    public java.util.List<ActorInvokes> collaboratorDays(String skillId, Window window) {
        return repository.collaboratorDays(skillId, window);
    }

    @Override
    public PublicRange skillRange(String skillId, Window window) {
        return repository.skillRange(skillId, window);
    }

    @Override
    public Mine mine(String skillId, String actorUserId, Window window) {
        return repository.mine(skillId, actorUserId, window);
    }
}
