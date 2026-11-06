package com.skillmasterai.modules.metrics;

import java.util.Collection;
import java.util.Map;

/** The read edge of M15. Values are already reduced to what the scope allows to leave. */
public interface UsageQuery {

    /** Skills with no row in this scope are absent from the map rather than present as zeroes. */
    Map<String, UsageSnapshot> snapshots(Collection<String> skillIds, UsageScope scope);
}
