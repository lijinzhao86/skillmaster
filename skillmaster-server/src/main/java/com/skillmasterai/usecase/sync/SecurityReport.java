package com.skillmasterai.usecase.sync;

import java.util.List;

/** What the static scan found; only {@code blocked} stops a draft. */
public record SecurityReport(List<SecurityFinding> blocked, List<SecurityFinding> warnings) {

    public SecurityReport {
        blocked = List.copyOf(blocked);
        warnings = List.copyOf(warnings);
    }

    public boolean hasBlocking() {
        return !blocked.isEmpty();
    }
}
