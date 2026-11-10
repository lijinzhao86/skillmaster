package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.retrieval.Embedder;
import com.skillmasterai.modules.retrieval.EmbeddingSettings;
import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import com.skillmasterai.support.CleanMigrateFlyway;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * The evidence tiers in a library search (v59 P1/P2, ADR 0180).
 *
 * <p>A stub embedder maps the exact phrase and its rewrite to one direction, so a cosine is 1.0 or
 * 0.0 rather than "close enough". Vectors are written straight into {@code skill_embedding}; the
 * personal sentence's vectors are written by the real note write, because that is the path under
 * test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "skillmaster.retrieval.enabled=true",
        "skillmaster.retrieval.api-key=stub-key",
        "skillmaster.retrieval.model=" + PersonalScenarioSearchIT.MODEL,
})
@Import({CleanMigrateFlyway.class, PersonalScenarioSearchIT.StubEmbedder.class})
@Sql("/sql/truncate-business-tables.sql")
class PersonalScenarioSearchIT extends AbstractOrganizeIT {

    static final String MODEL = "stub:personal:1024";
    private static final String QUERY = "循环审计";
    private static final String REWRITE = "反复审查";

    @Autowired
    private EmbeddingRepository embeddings;

    private static float[] direction(int position) {
        float[] vector = new float[EmbeddingSettings.COLUMN_WIDTH];
        vector[position] = 1f;
        return vector;
    }

    private void index(String skillId) {
        embeddings.remember(skillId, MODEL, "hash-" + skillId, direction(0), Timestamps.now());
    }

    @Test
    void nameThenPersonalExactThenTheAuthorsContent() {
        publicSkill("循环审计助手");
        String noted = publicSkill("pdf-tools");
        String authored = publicSkill("report-writer");
        jdbc.sql("UPDATE skill SET when_to_use = :text WHERE id = :id")
                .param("text", "循环审计时用我")
                .param("id", authored)
                .update();
        index(authored);
        String bearer = token();
        putJson("/api/v1/skills/demo/pdf-tools/my-when-to-use", bearer,
                "{\"when_to_use\":\"当我说循环审计时用它\"}");

        JsonNode answer = json(get(search(QUERY), bearer));
        List<String> names = names(answer.get("skills"));

        assertThat(names).containsExactly("循环审计助手", "pdf-tools", "report-writer");
        JsonNode noteCard = answer.get("skills").get(1);
        assertThat(noteCard.get("my_when_to_use").asText()).isEqualTo("当我说循环审计时用它");
        assertThat(noteCard.get("my_when_to_use_matched").asBoolean()).isTrue();
        assertThat(answer.get("skills").get(0).has("my_when_to_use"))
                .as("the name hit has no personal sentence, so the field is omitted").isFalse();
    }

    @Test
    void aRewriteIsS2AndStillBeatsTheAuthorsVector() {
        String noted = publicSkill("pdf-tools");
        String authored = publicSkill("report-writer");
        index(authored);
        String bearer = token();
        putJson("/api/v1/skills/demo/pdf-tools/my-when-to-use", bearer,
                "{\"when_to_use\":\"当我说反复审查时用它\"}");

        JsonNode answer = json(get(search(QUERY), bearer));

        assertThat(names(answer.get("skills"))).containsExactly("pdf-tools", "report-writer");
        assertThat(answer.get("skills").get(0).get("my_when_to_use_matched").asBoolean()).isTrue();
    }

    @Test
    void clearingTheSentenceRemovesItFromRanking() {
        publicSkill("循环审计助手");
        String noted = publicSkill("pdf-tools");
        String authored = publicSkill("report-writer");
        index(authored);
        String bearer = token();
        putJson("/api/v1/skills/demo/pdf-tools/my-when-to-use", bearer,
                "{\"when_to_use\":\"当我说循环审计时用它\"}");
        delete("/api/v1/skills/demo/pdf-tools/my-when-to-use", bearer);

        JsonNode answer = json(get(search(QUERY), bearer));

        assertThat(names(answer.get("skills"))).containsExactly("循环审计助手", "report-writer");
    }

    @Test
    void aConfidentPersonalRewriteOutranksTheAuthorsExactSentence() {
        String noted = publicSkill("pdf-tools");
        String authored = publicSkill("report-writer");
        jdbc.sql("UPDATE skill SET when_to_use = :text WHERE id = :id")
                .param("text", "循环审计时用我")
                .param("id", authored)
                .update();
        index(authored);
        String bearer = token();
        putJson("/api/v1/skills/demo/pdf-tools/my-when-to-use", bearer,
                "{\"when_to_use\":\"当我说反复审查时用它\"}");

        JsonNode answer = json(get(search(QUERY), bearer));

        // Both halves matched; ADR 0180 says rank by the personal S2, not the author's S3.
        assertThat(names(answer.get("skills"))).containsExactly("pdf-tools", "report-writer");
        assertThat(answer.get("skills").get(0).get("my_when_to_use_matched").asBoolean()).isTrue();
    }

    @Test
    void publicResultsDoNotGainThePrivateSentence() {
        String pdf = publicSkill("pdf-tools");
        index(pdf);
        String bearer = token();
        putJson("/api/v1/skills/demo/pdf-tools/my-when-to-use", bearer,
                "{\"when_to_use\":\"当我说循环审计时用它\"}");

        JsonNode answer = json(get(search(QUERY).replace("scope=library", "scope=public"), bearer));

        assertThat(names(answer.get("skills"))).contains("pdf-tools");
        assertThat(answer.get("skills").get(0).has("my_when_to_use"))
                .as("the public plane keeps the answer it had before the note").isFalse();
    }

    @Test
    void theAuthorsExactSentenceBeatsGenericSemanticEvidence() {
        String authored = publicSkill("report-writer");
        jdbc.sql("UPDATE skill SET when_to_use = :text WHERE id = :id")
                .param("text", "循环审计时用我")
                .param("id", authored)
                .update();
        index(authored);
        String generic = publicSkill("vector-only");
        index(generic);
        String bearer = token();

        JsonNode answer = json(get(search(QUERY), bearer));

        assertThat(names(answer.get("skills"))).containsExactly("report-writer", "vector-only");
    }

    @Test
    void scopeAllStillPromotesThePersonalSentenceOnLibraryRows() {
        publicSkill("pdf-tools");
        String generic = publicSkill("vector-only");
        index(generic);
        String bearer = token();
        putJson("/api/v1/skills/demo/pdf-tools/my-when-to-use", bearer,
                "{\"when_to_use\":\"当我说循环审计时用它\"}");

        JsonNode answer = json(get(search(QUERY).replace("scope=library", "scope=all"), bearer));

        assertThat(names(answer.get("skills"))).containsExactly("pdf-tools", "vector-only");
        assertThat(answer.get("skills").get(0).get("my_when_to_use_matched").asBoolean()).isTrue();
    }

    private static String search(String query) {
        return "/api/v1/skills?scope=library&semantic=true&limit=10&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEmbedder {

        @Bean
        @Primary
        Embedder stubEmbedder() {
            return new Embedder() {
                @Override
                public String model() {
                    return MODEL;
                }

                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream()
                            .map(text -> direction(
                                    text.contains(QUERY) || text.contains(REWRITE) ? 0 : 1))
                            .toList();
                }
            };
        }
    }
}
