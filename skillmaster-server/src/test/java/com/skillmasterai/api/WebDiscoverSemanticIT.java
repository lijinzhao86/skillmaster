package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.retrieval.Embedder;
import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import com.skillmasterai.modules.retrieval.EmbeddingSettings;
import com.skillmasterai.modules.retrieval.EmbeddingUnavailable;
import com.skillmasterai.support.CleanMigrateFlyway;
import java.net.http.HttpResponse;
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
 * What the browser plane's discovery search answers when it is a search by meaning (v19).
 *
 * <p><strong>Why a stub embedder.</strong> The ranking, the threshold, the two shapes on the wire and
 * the degradation are all this class's business — none of them is about the provider. A stub that
 * maps one word to one direction makes the cosine exactly 1.0 or exactly 0.0, so a threshold test
 * cannot pass by accident, and the suite never calls a paid API.
 *
 * <p>Vectors are written straight into {@code skill_embedding} rather than through a publish: the
 * publish path writes its own vectors and has its own tests, and a publish here would make a publish
 * bug look like a discovery bug.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "skillmaster.retrieval.enabled=true",
        "skillmaster.retrieval.api-key=stub-key",
        "skillmaster.retrieval.model=" + WebDiscoverSemanticIT.MODEL,
})
@Import({CleanMigrateFlyway.class, WebDiscoverSemanticIT.StubEmbedder.class})
@Sql("/sql/truncate-business-tables.sql")
class WebDiscoverSemanticIT extends AbstractOrganizeIT {

    static final String MODEL = "stub:model:1024";

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
    void aKeywordIsASearchByMeaningAndHasNoPages() {
        String id = publicSkill("pdf-tools");
        index(id);
        token();

        JsonNode answer = json(webGet("/web/discover?q=pdf"));

        assertThat(answer.get("skills")).hasSize(1);
        assertThat(answer.get("skills").get(0).get("name").asText()).isEqualTo("pdf-tools");
        // The score rides on the card, the same field the API plane's semantic answer uses.
        assertThat(answer.get("skills").get(0).get("score").asDouble()).isCloseTo(1.0, within(0.001));
        // **No page, no total**: "the most similar" is not "page 1 of N".
        assertThat(answer.get("p")).isNull();
        assertThat(answer.get("page_size")).isNull();
        assertThat(answer.get("total")).isNull();
        assertThat(answer.get("note")).isNull();
    }

    /**
     * **The console addresses a category by key; the API addresses it by display name.** Both have to
     * reach the same shelf: before this test existed, `q` plus a category came back empty with
     * "分类或标签的名字不在词表里" — the resolver only knew the name spelling, and the console only
     * sends the key.
     */
    @Test
    void aCategoryNarrowsTheSearchAndIsUnderstoodInTheConsolesSpelling() {
        // v65: the shelves are the 13 new words; `doc` / `dev` are retired and would resolve to
        // nothing (the V51 migration maps them, but this fixture is written after it runs).
        index(skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-in-doc", "public", Ulid.generate(),
                null, null, "office-efficiency"));
        index(skill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "pdf-in-dev", "public", Ulid.generate(),
                null, null, "dev-programming"));
        token();

        JsonNode answer = json(webGet("/web/discover?q=pdf&tag=office-efficiency"));

        assertThat(names(answer.get("skills"))).containsExactly("pdf-in-doc");
        assertThat(answer.get("note")).isNull();
    }

    /**
     * v65 (test-plan T16): an attribute filter narrows the id set before the ranking, on the
     * semantic path too. Both skills are equally similar, so only the label can separate them.
     */
    @Test
    void anAttributeNarrowsTheSemanticAnswerBeforeRanking() {
        String python = publicSkill("pdf-in-python");
        String other = publicSkill("pdf-in-other");
        String pythonVersion = jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", python).query(String.class).single();
        String otherVersion = jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", other).query(String.class).single();
        versionRow(python, pythonVersion, 1);
        versionRow(other, otherVersion, 1);
        jdbc.sql("""
                INSERT INTO skill_label
                  (id, skill_id, definition_id, source, confidence, evidence, vocabulary_version,
                   review_state, evaluated_version_id, created_at, updated_at)
                VALUES (:id, :skill, 'runtime:python', 'agent', 0.95, '{}'::jsonb, 'v65-test',
                        'auto', :version, :at, :at)
                """).param("id", "lbl-" + python).param("skill", python)
                .param("version", pythonVersion).param("at", AT).update();
        index(python);
        index(other);
        token();

        JsonNode answer = json(webGet("/web/discover?q=pdf&label=runtime:python"));

        assertThat(names(answer.get("skills"))).containsExactly("pdf-in-python");
        assertThat(answer.get("note")).isNull();
    }

    /** One `skill_version` row: `skill_label.evaluated_version_id` is a foreign key. */
    private void versionRow(String skillId, String versionId, int number) {
        String name = jdbc.sql("SELECT name FROM skill WHERE id = :id")
                .param("id", skillId).query(String.class).single();
        jdbc.sql("""
                INSERT INTO skill_version
                  (id, skill_id, number, digest, file_count, total_bytes, submitted_by,
                   submitted_at, state, state_at, title, description, frontmatter, version,
                   first_published_at)
                VALUES (:id, :skill, :number, :digest, 0, 0, :user, :at, 'published', :at,
                        :name, :name, '{}', :versionName, :at)
                ON CONFLICT (id) DO NOTHING
                """).param("id", versionId).param("skill", skillId).param("number", number)
                .param("digest", "digest-" + versionId).param("user", DEMO_USER_ID)
                .param("name", name).param("versionName", "1.0." + number)
                .param("at", AT).update();
    }

    /** v65: an unknown attribute is a typo on every path, so the semantic path is a 400 too. */
    @Test
    void anUnknownAttributeOnTheSemanticPathIsAFourHundred() {
        index(publicSkill("pdf-tools"));
        token();

        HttpResponse<String> answer = webGet("/web/discover?q=pdf&label=runtime:nope");

        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(answer.body()).contains("runtime:nope");
    }

    @Test
    void nothingSimilarEnoughIsSaidOutLoudRatherThanShownAsTenRows() {
        index(publicSkill("pdf-tools"));
        token();

        // The stub answers any other text with an orthogonal direction: cosine 0, under the 0.40
        // threshold, so the answer is empty and says why rather than offering the nearest thing.
        JsonNode answer = json(webGet("/web/discover?q=zzz"));

        assertThat(answer.get("skills")).isEmpty();
        assertThat(answer.get("total")).isNull();
        // **No note here on purpose.** "Nothing was similar enough" is the page's sentence, written
        // once in the design; a second spelling from the server is a second thing to keep in step.
        assertThat(answer.get("note")).isNull();
    }

    @Test
    void whenTheRankerIsDownTheLiteralSearchAnswersAndThePageSaysSo() {
        publicSkill("boom-tools");
        token();

        JsonNode answer = json(webGet("/web/discover?q=boom"));

        // The stub fails on "boom", which is what a provider outage looks like from here.
        assertThat(answer.get("note").asText())
                .isEqualTo("语义检索暂不可用。下面是按字面找到的结果。");
        // The fallback is the keyword search, so the paged shape comes back with it.
        assertThat(answer.get("total").asLong()).isPositive();
        assertThat(answer.get("p").asInt()).isEqualTo(1);
        assertThat(answer.get("skills").get(0).get("score")).isNull();
    }

    @Test
    void aFilterWordThatNamesNothingIsSaidRatherThanAnsweredWithAnEmptyPage() {
        index(publicSkill("pdf-tools"));
        token();

        // Only the server can tell "nothing was similar enough" (the page's own sentence) from "that
        // word names no shelf" (this one). The console shows whichever comes back.
        JsonNode answer = json(webGet("/web/discover?q=pdf&tag=nosuch"));

        assertThat(answer.get("skills")).isEmpty();
        assertThat(answer.get("note").asText()).isEqualTo("分类或标签的名字不在词表里，没有可检索的范围。");
    }

    /** The API plane is the other caller of the same use case; it must answer and degrade the same way. */
    @Test
    void theApiPlaneAlsoAnswersByMeaningAndExplainsADegradedAnswer() {
        index(publicSkill("pdf-tools"));
        String bearer = token();

        JsonNode answer = json(send(request(
                "/api/v1/skills?q=pdf&scope=public&semantic=true&limit=10", bearer).GET().build()));
        assertThat(names(answer.get("skills"))).contains("pdf-tools");
        assertThat(answer.get("skills").get(0).get("score").asDouble()).isCloseTo(1.0, within(0.001));

        // "boom" makes the stub fail; nothing matches it literally either, so the note has to carry
        // both facts — the sentence claiming rows below it would be a lie.
        JsonNode down = json(send(request(
                "/api/v1/skills?q=boom&scope=public&semantic=true", bearer).GET().build()));
        assertThat(down.get("skills")).isEmpty();
        assertThat(down.get("note").asText())
                .isEqualTo("语义检索暂不可用。按字面找也没有匹配的 skill。");
    }

    /**
     * The semantic answer is a card list like any other, so it carries the same v49 fields. When
     * only the literal paths enriched, `skills search --verbose` lost its source and usage lines
     * exactly when the product's default (semantic) was in effect.
     */
    @Test
    void aSemanticAnswerCarriesTheSameSourceAndUsageSignalsAsALiteralOne() {
        String skillId = publicSkill("semantic-signals");
        index(skillId);
        String versionId = jdbc.sql("SELECT current_version_id FROM skill WHERE id = :id")
                .param("id", skillId)
                .query((rs, rowNum) -> rs.getString(1))
                .single();
        // The organizer fixture points the skill at a version id it does not insert; the license
        // and snapshot tables are keyed by it.
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, digest, file_count, total_bytes,
                                           submitted_by, submitted_at)
                VALUES (:id, :skill, 1, :digest, 1, 10, :user, '2026-11-05T00:00:00Z')
                """)
                .param("id", versionId)
                .param("skill", skillId)
                .param("digest", "sha256:" + versionId)
                .param("user", DEMO_USER_ID)
                .update();
        jdbc.sql("""
                INSERT INTO skill_source (id, skill_id, role, kind, locator, path, label, url,
                                          created_at)
                VALUES ('source-semantic', :skill, 'origin', 'github',
                        'coreyhaines31/marketingskills', 'skills/ab-testing', 'GitHub',
                        'https://github.com/coreyhaines31/marketingskills',
                        '2026-11-05T00:00:00Z')
                """).param("skill", skillId).update();
        jdbc.sql("""
                INSERT INTO skill_source_snapshot (source_id, skill_version_id, revision, signals,
                                                   status, fetched_at)
                VALUES ('source-semantic', :version, '8f3c2a1d4e5f60718293a4b5c6d7e8f901234567',
                        '[]', 'ok', '2026-11-05T00:00:00Z')
                """).param("version", versionId).update();
        jdbc.sql("""
                INSERT INTO skill_version_license (version_id, spdx, name, copyright, status,
                        license_text, source_path, source_url, detail, fetched_at)
                VALUES (:version, 'MIT', 'MIT License', NULL, 'ok', NULL, 'LICENSE', NULL, '{}',
                        '2026-11-05T00:00:00Z')
                """).param("version", versionId).update();
        jdbc.sql("""
                INSERT INTO skill_usage_total (skill_id, scope, calls)
                VALUES (:skill, 'public', 1)
                """).param("skill", skillId).update();

        JsonNode answer = json(send(request(
                "/api/v1/skills?q=pdf&scope=public&semantic=true", token())
                .GET().build()));

        JsonNode card = answer.get("skills").get(0);
        assertThat(card.get("score").asDouble()).isCloseTo(1.0, within(0.001));
        assertThat(card.get("origin_label").asText()).isEqualTo("GitHub");
        assertThat(card.get("origin_locator").asText())
                .isEqualTo("coreyhaines31/marketingskills");
        assertThat(card.get("license_spdx").asText()).isEqualTo("MIT");
        assertThat(card.get("license_status").asText()).isEqualTo("ok");
        assertThat(card.get("calls_bucket").asText()).isEqualTo("少于 10 次调用");

        // The console plane is a second wrapper around the same cards; it has to carry them too.
        JsonNode webCard = json(webGet("/web/discover?q=pdf")).get("skills").get(0);
        assertThat(webCard.get("origin_label").asText()).isEqualTo("GitHub");
        assertThat(webCard.get("license_status").asText()).isEqualTo("ok");
        assertThat(webCard.get("calls_bucket").asText()).isEqualTo("少于 10 次调用");
    }

    @Test
    void aSemanticSearchRefusesACursor() {
        String bearer = token();

        // Similarity is not a page boundary, so a cursor is a request that cannot be honoured — a
        // usage error, not an empty page the caller would read as "no results".
        var response = send(request(
                "/api/v1/skills?q=pdf&scope=public&semantic=true&cursor=abc", bearer).GET().build());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void whenTheRankerIsDownAndNothingMatchesLiterallyTheSentenceSaysBoth() {
        index(publicSkill("pdf-tools"));
        token();

        // Nothing is called "boom", so the literal search finds nothing either: saying "下面是按字面
        // 找到的结果" here would describe rows that are not there.
        JsonNode answer = json(webGet("/web/discover?q=boom"));

        assertThat(answer.get("skills")).isEmpty();
        assertThat(answer.get("note").asText())
                .isEqualTo("语义检索暂不可用。按字面找也没有匹配的 skill。");
    }

    /** Two directions and one failure: enough to tell a match, a miss and an outage apart. */
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
                    return texts.stream().map(text -> {
                        if (text.contains("boom")) {
                            throw new EmbeddingUnavailable("the stub was asked to fail", null);
                        }
                        return direction(text.contains("pdf") ? 0 : 1);
                    }).toList();
                }
            };
        }
    }
}
