package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fields lifted out of the author's stored frontmatter: two sentences. A client never parses the
 * document itself, so the server has to answer these the same way on both planes.
 */
class SkillMetaResponseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void compatibilityIsLiftedFromAStoredFrontmatter() {
        assertThat(SkillMetaResponse.compatibilityOf(
                "{\"name\":\"x\",\"compatibility\":\"需要 python3\"}", JSON))
                .isEqualTo("需要 python3");
    }

    @Test
    void anAbsentOrNonTextualCompatibilityReadsAsNull() {
        // The header drops the line rather than printing `compatibility: null`: the field is
        // optional, and a shape that is not a sentence is not one this system can show.
        assertThat(SkillMetaResponse.compatibilityOf("{\"name\":\"x\"}", JSON)).isNull();
        assertThat(SkillMetaResponse.compatibilityOf("{\"compatibility\":42}", JSON)).isNull();
    }

    @Test
    void anUnreadableDocumentReadsAsNullRatherThanFailingTheRead() {
        // A version that exists always has a frontmatter M5 parsed, so this cannot happen in
        // practice; the answer keeps an optional line out rather than turning a read into a 500.
        assertThat(SkillMetaResponse.compatibilityOf("", JSON)).isNull();
    }

}
