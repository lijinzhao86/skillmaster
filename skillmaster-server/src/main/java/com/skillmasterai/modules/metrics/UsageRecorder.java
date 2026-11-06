package com.skillmasterai.modules.metrics;

/** The write edge of M15. Callers decide what to count; this module decides how it is kept. */
public interface UsageRecorder {

    /** Idempotent for the deduplicated series; every call still advances the cumulative count. */
    void record(SkillLoad load);
}
