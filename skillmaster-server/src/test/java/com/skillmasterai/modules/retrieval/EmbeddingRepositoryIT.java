package com.skillmasterai.modules.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import com.skillmasterai.support.AbstractIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * The statements M13 writes against its own table, run against a real PostgreSQL.
 *
 * <p><strong>Why this exists.</strong> The first real publish failed on its first attempt:
 * {@code updated_at} was declared {@code TIMESTAMPTZ} while the application binds an RFC3339
 * string, so every insert died with "expression is of type character varying" and the publish
 * answered 500. Nothing caught it — no test touched this table, and a column type is invisible to
 * a reader of the DDL who assumes the convention the rest of the schema follows. This test inserts
 * through the same method the publish path calls, so that class of mistake now fails here.
 *
 * <p>It also pins the behaviours the rest of the design leans on: the upsert is keyed by
 * {@code (skill_id, model)} so a second model coexists instead of overwriting, the score is
 * {@code 1 - cosine distance}, and a hash that is already indexed is recognisable — which is what
 * lets a republish skip the provider call.
 */
@Sql("/sql/truncate-business-tables.sql")
class EmbeddingRepositoryIT extends AbstractIT {

    /** Seeded by the test database's fixture, the same pair every API test uses. */
    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";

    @Autowired
    private EmbeddingRepository embeddings;

    @Autowired
    private JdbcClient jdbc;

    /** A unit vector with a 1 in one position — the cheapest thing that is a real 1024-dim vector. */
    private static float[] unit(int position) {
        float[] vector = new float[EmbeddingSettings.COLUMN_WIDTH];
        vector[position] = 1f;
        return vector;
    }

    private String insertSkill(String name) {
        String skillId = Ulid.generate();
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, :namespaceId, :name, '', 'a probe', '{}', 'private', :userId, :at, :at)
                """)
                .param("id", skillId)
                .param("namespaceId", DEMO_NAMESPACE_ID)
                .param("name", name)
                .param("userId", DEMO_USER_ID)
                .param("at", Timestamps.now())
                .update();
        return skillId;
    }

    @Test
    void aVectorIsStoredAndFoundByItsOwnScore() {
        String skillId = insertSkill(uniqueName("one-vector"));

        embeddings.remember(skillId, "probe:model:1024", "hash-a", unit(0), Timestamps.now());

        List<EmbeddingRepository.Hit> same = embeddings.nearest("probe:model:1024", unit(0), 10);
        assertThat(same).singleElement().satisfies(hit -> {
            assertThat(hit.skillId()).isEqualTo(skillId);
            assertThat(hit.score()).isCloseTo(1.0, within(0.001));
        });

        List<EmbeddingRepository.Hit> orthogonal =
                embeddings.nearest("probe:model:1024", unit(1), 10);
        assertThat(orthogonal).singleElement().satisfies(hit ->
                assertThat(hit.score()).isCloseTo(0.0, within(0.001)));
    }

    @Test
    void twoModelsCoexistAndTheSameOneIsOverwritten() {
        String skillId = insertSkill(uniqueName("two-models"));

        embeddings.remember(skillId, "probe:model-a:1024", "hash-a", unit(0), Timestamps.now());
        embeddings.remember(skillId, "probe:model-b:1024", "hash-b", unit(1), Timestamps.now());
        embeddings.remember(skillId, "probe:model-a:1024", "hash-a2", unit(2), Timestamps.now());

        assertThat(countFor(skillId)).isEqualTo(2);
        // The re-embed replaced model-a's vector rather than adding a row: model-a now points at
        // the third direction, and model-b still points at the second.
        assertThat(embeddings.nearest("probe:model-a:1024", unit(2), 10))
                .singleElement().satisfies(hit -> assertThat(hit.skillId()).isEqualTo(skillId));
        assertThat(embeddings.nearest("probe:model-b:1024", unit(1), 10))
                .singleElement().satisfies(hit -> assertThat(hit.skillId()).isEqualTo(skillId));
    }

    @Test
    void aKnownHashIsRecognisedSoARepublishCanSkipTheProvider() {
        String skillId = insertSkill(uniqueName("known-hash"));

        embeddings.remember(skillId, "probe:hash:1024", "known", unit(0), Timestamps.now());

        assertThat(embeddings.hasVectorFor(skillId, "probe:hash:1024", "known")).isTrue();
        assertThat(embeddings.hasVectorFor(skillId, "probe:hash:1024", "other")).isFalse();
        assertThat(embeddings.hasVectorFor(skillId, "probe:elsewhere:1024", "known")).isFalse();
    }

    @Test
    void deletingTheSkillTakesTheVectorWithIt() {
        String skillId = insertSkill(uniqueName("cascade"));
        embeddings.remember(skillId, "probe:cascade:1024", "hash", unit(0), Timestamps.now());

        jdbc.sql("DELETE FROM skill WHERE id = :id").param("id", skillId).update();

        assertThat(countFor(skillId)).isZero();
    }

    /** A name unique within the fixture namespace, since `skill` is unique on (namespace, name). */
    private static String uniqueName(String prefix) {
        return prefix + "-" + Ulid.generate().toLowerCase();
    }

    private int countFor(String skillId) {
        return jdbc.sql("SELECT count(*) FROM skill_embedding WHERE skill_id = :id")
                .param("id", skillId).query(Integer.class).single();
    }
}
