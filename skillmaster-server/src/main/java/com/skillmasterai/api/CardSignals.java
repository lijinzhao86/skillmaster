package com.skillmasterai.api;

import com.skillmasterai.modules.metrics.UsageQuery;
import com.skillmasterai.modules.metrics.UsageScope;
import com.skillmasterai.modules.skill.SourceService;
import org.springframework.stereotype.Component;

/**
 * Adds the compact selection signals to a listing. The full blocks stay on the metadata endpoint;
 * a list carries only the three facts a reader compares rows by.
 *
 * <p>One enrichment pass per response, not one lookup per card on the client. The use series and
 * the library count come from different owners (M15 and M12) and are merged here, on the read side
 * — neither module knows about the other.
 */
@Component
class CardSignals {

    private final SourceService sources;
    private final UsageQuery metrics;

    CardSignals(SourceService sources, UsageQuery metrics) {
        this.sources = sources;
        this.metrics = metrics;
    }

    SearchResponse enrich(SearchResponse response) {
        var snapshots = metrics.snapshots(
                response.skills().stream().map(SearchResponse.Card::id).toList(),
                UsageScope.PUBLIC);
        return new SearchResponse(response.skills().stream().map(card -> {
            var origin = sources.origin(card.id());
            String versionId = sources.currentVersionId(card.id());
            var license = versionId == null
                    ? java.util.Optional.<com.skillmasterai.modules.skill.SkillLicense>empty()
                    : sources.license(versionId);
            var usage = snapshots.get(card.id());
            int joins = sources.joins(card.id());
            boolean noUsage = usage == null && joins == 0;
            return card.withSignals(
                    origin.map(com.skillmasterai.modules.skill.SkillSource::label).orElse(null),
                    origin.map(com.skillmasterai.modules.skill.SkillSource::kind).orElse(null),
                    origin.map(com.skillmasterai.modules.skill.SkillSource::locator).orElse(null),
                    origin.map(com.skillmasterai.modules.skill.SkillSource::url).orElse(null),
                    license.map(com.skillmasterai.modules.skill.SkillLicense::spdx).orElse(null),
                    license.map(entry -> entry.status().wire()).orElse(null),
                    usage == null ? null
                            : SkillMetaResponse.accountsLabel(usage.invokers30dBucketFloor()),
                    usage == null || !usage.hasCalls() ? null
                            : SkillMetaResponse.callsLabel(usage.callsBucketFloor()),
                    usage == null || usage.lastUsed() == null ? null
                            : usage.lastUsed().toString(),
                    noUsage ? null : joins);
        }).toList(), response.nextCursor(), response.note());
    }
}
