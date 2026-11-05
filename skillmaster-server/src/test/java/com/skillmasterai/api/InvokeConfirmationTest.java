package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.distribution.SkillDetail;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wire answer to "must the CLI ask before loading this" (v47, ADR 0143).
 *
 * <p>Only the public-only standing asks. Everything else — created, shared, joined — loads without
 * a question, because the caller already has a relationship with the skill.
 */
class InvokeConfirmationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aPublicSkillWithNoLibraryRelationAsksForConfirmation() {
        assertThat(SkillMetaResponse.of(detailWithSource(null), JSON, true)
                .invokeRequiresConfirmation()).isTrue();
        assertThat(SkillDetailResponse.of(detailWithSource(null), JSON, true)
                .invokeRequiresConfirmation()).isTrue();
    }

    @Test
    void everyLibraryRelationLoadsWithoutAsking() {
        for (String source : List.of("created", "shared", "joined")) {
            SkillDetail detail = detailWithSource(source);
            assertThat(SkillMetaResponse.of(detail, JSON, true).invokeRequiresConfirmation())
                    .as("metadata source=%s", source)
                    .isFalse();
            assertThat(SkillDetailResponse.of(detail, JSON, true).invokeRequiresConfirmation())
                    .as("detail source=%s", source)
                    .isFalse();
        }
    }

    private static SkillDetail detailWithSource(String source) {
        return new SkillDetail("id", "name", "Name", "description", "demo", "Demo", "public",
                source, "{}", "1.0.0", "abc", "2026-11-05T00:00:00Z", 0, 0, true, false,
                "invoke", List.of(), List.of(), List.of());
    }
}
