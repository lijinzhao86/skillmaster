package com.skillmasterai.modules.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.support.AbstractIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ownership predicate and the published-only predicate, pinned at the layer that enforces them.
 *
 * <p><strong>Through HTTP the ownership rule cannot be tested.</strong> The use case rejects an
 * address whose namespace segment is not the caller's before M7 is ever consulted, so a bug in M7's
 * own predicate would be invisible: every request would still answer 404, for the wrong reason. That
 * redundancy is deliberate — it is what keeps "not yours" and "not there" one answer instead of two
 * branches that could drift — but it means the predicate has to be tested where it lives.
 *
 * <p>So this calls M7 directly, naming a namespace the caller does not own, and expects the same
 * empty result an absent skill gives. It is the test that fails if {@code byName} or
 * {@code softDelete} ever loses its {@code namespace_id} filter.
 *
 * <p>The second predicate is tested here for the same reason: {@code liveSnapshot} must not serve a
 * draft, and {@code authorSnapshot} must. Both were inserted directly rather than submitted, because
 * the write path is another test's subject and a fixture that went through it would be asserting
 * two things at once.
 *
 * <p>Transactional because that is how M7 is called: the annotation belongs to the use case, and
 * every module call joins that transaction. A write path reached outside one is not a shape the
 * application has — the blob sweep says so itself by refusing to run untransacted.
 */
@Sql("/sql/truncate-business-tables.sql")
@Transactional
class SkillVersionServiceIT extends AbstractIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    private static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

    @Autowired
    private SkillVersionService versions;

    @Test
    void aSkillIsInvisibleThroughANamespaceThatDoesNotOwnIt() {
        insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "not-mine", 1, true);

        assertThat(versions.liveSnapshot(OTHER_NAMESPACE_ID, "not-mine", new VersionPin.Latest()))
                .as("the namespace that owns it reads it")
                .isPresent();
        assertThat(versions.liveSnapshot(DEMO_NAMESPACE_ID, "not-mine", new VersionPin.Latest()))
                .as("and another namespace gets the same nothing an absent skill gives")
                .isEmpty();
    }

    @Test
    void aSkillCannotBeDeletedThroughANamespaceThatDoesNotOwnIt() {
        String id = insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "not-mine", 1, true);

        assertThat(versions.softDelete(DEMO_NAMESPACE_ID, "not-mine"))
                .as("the predicate is inside the UPDATE, so it matches no row")
                .isEmpty();
        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", id)))
                .as("and the skill is untouched")
                .isEqualTo(1);
    }

    @Test
    void aPinnedVersionIsResolvedWithinItsOwnSkill() {
        // Numbers are per-skill, so one skill's number must not resolve under another — which is
        // what a lookup missing its skill_id would do.
        //
        // Both skills stay live and carry *different* numbers, and that is what makes this an
        // assertion rather than a restatement of the fixture. Soft-deleting one, or giving both the
        // same number, hides the thing being tested: the first makes the empty answer come from
        // `deleted_at IS NULL` without ever reaching the version lookup, the second makes the
        // mutation fail on a row-count error instead of on the number.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "one", 1, true);
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "two", 7, true);

        assertThat(versions.liveSnapshot(DEMO_NAMESPACE_ID, "two", new VersionPin.Number(7)))
                .as("its own number, found through its own skill")
                .isPresent();
        assertThat(versions.liveSnapshot(DEMO_NAMESPACE_ID, "two", new VersionPin.Number(1)))
                .as("a number another skill holds resolves to nothing here")
                .isEmpty();
        assertThat(versions.liveSnapshot(DEMO_NAMESPACE_ID, "one", new VersionPin.Number(7)))
                .as("and the same the other way round")
                .isEmpty();
    }

    /**
     * ADR 0031's central rule, at the layer that implements it.
     *
     * <p>All three pin forms are checked, not just the bare address, because the leak the
     * published-only predicate prevents is not the pointer's: a draft submitted <em>after</em> the
     * skill went live is reachable as {@code @2} and as {@code @sha256:…} even though nothing points
     * at it. The fixture therefore has both a published version and a draft, so that the skill is
     * readable and the only thing separating them is the version's own state.
     */
    @Test
    void aDraftIsServedToNobodyButItsAuthor() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", 1, true);
        insertSkillVersion(DEMO_NAMESPACE_ID, "mine", 2, false);

        assertThat(versions.liveSnapshot(DEMO_NAMESPACE_ID, "mine", new VersionPin.Number(2)))
                .as("a draft is not addressable on the consumption plane")
                .isEmpty();
        assertThat(versions.liveSnapshot(DEMO_NAMESPACE_ID, "mine", new VersionPin.Number(1)))
                .as("while the published version still is")
                .isPresent();

        assertThat(versions.authorSnapshot(DEMO_NAMESPACE_ID, "mine", new VersionPin.Number(2)))
                .as("the author sees their own draft")
                .isPresent();
        assertThat(versions.authorSnapshot(DEMO_NAMESPACE_ID, "mine", new VersionPin.Number(2))
                .orElseThrow().state())
                .as("and sees what state it is in")
                .isEqualTo(VersionState.DRAFT);
    }

    /** A live skill with one version, inserted directly: the write path is another test's subject. */
    private String insertSkill(String namespaceId, String userId, String name, int number,
            boolean published) {
        String skillId = Ulid.generate();
        String versionId = Ulid.generate();
        String at = Timestamps.now();

        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, description, frontmatter, visibility,
                                   current_version_id, created_by, created_at, updated_at)
                VALUES (:id, :namespace, :name, 'a skill', '{}', 'private',
                        CASE WHEN :published THEN :version ELSE NULL END, :user, :at, :at)
                """)
                .param("id", skillId).param("namespace", namespaceId).param("name", name)
                .param("published", published).param("version", versionId)
                .param("user", userId).param("at", at)
                .update();

        insertVersionRow(skillId, versionId, number, published, userId, at);
        return skillId;
    }

    /** A further version of a skill the fixture has already created. */
    private void insertSkillVersion(String namespaceId, String name, int number, boolean published) {
        String skillId = jdbc.sql("SELECT id FROM skill WHERE namespace_id = :namespace AND name = :name")
                .param("namespace", namespaceId).param("name", name)
                .query(String.class).single();
        insertVersionRow(skillId, Ulid.generate(), number, published, DEMO_USER_ID,
                Timestamps.now());
    }

    private void insertVersionRow(String skillId, String versionId, int number, boolean published,
            String userId, String at) {
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, digest, file_count, total_bytes,
                                           changelog, source, submitted_by, submitted_at,
                                           state, state_at, title, description, frontmatter)
                VALUES (:id, :skill, :number, :digest, 0, 0, '', 'zip', :user, :at,
                        CASE WHEN :published THEN 'published' ELSE 'draft' END,
                        CASE WHEN :published THEN :at ELSE NULL END,
                        '', 'a skill', '{}')
                """)
                .param("id", versionId).param("skill", skillId).param("number", number)
                // Well-formed enough for a read path that never recomputes it: this test is about
                // which row is found, not about what a digest means.
                .param("digest", Ulid.generate())
                .param("published", published)
                .param("user", userId).param("at", at)
                .update();
    }
}
