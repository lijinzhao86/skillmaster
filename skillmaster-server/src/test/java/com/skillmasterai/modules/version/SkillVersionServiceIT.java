package com.skillmasterai.modules.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.support.AbstractIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ownership predicate and the published-only predicate, pinned at the layers that enforce them.
 *
 * <p><strong>Through HTTP the ownership rule cannot be tested.</strong> The use case rejects an
 * address whose namespace segment is not the caller's before the content module is ever consulted,
 * so a bug in the predicate would be invisible: every request would still answer 404, for the wrong
 * reason. That redundancy is deliberate — it is what keeps "not yours" and "not there" one answer
 * instead of two branches that could drift — but it means the predicate has to be tested where it
 * lives: M12's {@code accessFor} (ADR 0053).
 *
 * <p>So this calls M12 directly, naming a namespace the caller does not own, and expects the same
 * empty result an absent skill gives. It is the test that fails if the access statement ever loses
 * its {@code namespace_id} filter. The content side is then tested against the {@link Access} it
 * produced: {@code liveSnapshot} must not serve a draft, and {@code authorSnapshot} must.
 *
 * <p>Transactional because that is how these modules are called: the annotation belongs to the use
 * case, and every module call joins that transaction. A write path reached outside one is not a
 * shape the application has — the blob sweep says so itself by refusing to run untransacted.
 */
@Sql("/sql/truncate-business-tables.sql")
@Transactional
class SkillVersionServiceIT extends AbstractIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER_NAMESPACE_ID = "01M3HTGC79CHKDB4Q0T2JMRCWV";
    private static final String OTHER_USER_ID = "01M3HTG7GDQ71Q28CCP7J0HM8T";

    /**
     * The two accounts this test acts as (ADR 0034).
     *
     * <p>Each is a user and the namespace they own, and the tests below are all about the mismatch
     * between those two and the namespace an address names — which is the entire content of the
     * predicate.
     */
    private static final Caller DEMO = new Caller(DEMO_USER_ID, DEMO_NAMESPACE_ID);
    private static final Caller OTHER = new Caller(OTHER_USER_ID, OTHER_NAMESPACE_ID);

    @Autowired
    private SkillMetadataService metadata;

    @Autowired
    private SkillVersionService versions;

    @Test
    void aSkillIsInvisibleThroughANamespaceThatDoesNotOwnIt() {
        insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "not-mine", "1.0.0", true);

        assertThat(metadata.accessFor(OTHER_NAMESPACE_ID, "not-mine", OTHER))
                .as("the namespace that owns it reads it")
                .isPresent();
        assertThat(metadata.accessFor(DEMO_NAMESPACE_ID, "not-mine", DEMO))
                .as("and another namespace gets the same nothing an absent skill gives")
                .isEmpty();
    }

    @Test
    void aSkillCannotBeDeletedThroughANamespaceThatDoesNotOwnIt() {
        String id = insertSkill(OTHER_NAMESPACE_ID, OTHER_USER_ID, "not-mine", "1.0.0", true);

        assertThat(metadata.softDelete(DEMO_NAMESPACE_ID, "not-mine", DEMO, Timestamps.now()))
                .as("the predicate is inside the UPDATE, so it matches no row")
                .isEmpty();
        assertThat(count("SELECT count(*) FROM skill WHERE id = :id AND deleted_at IS NULL",
                Map.of("id", id)))
                .as("and the skill is untouched")
                .isEqualTo(1);
    }

    @Test
    void aPinnedVersionIsResolvedWithinItsOwnSkill() {
        // Version names are per-skill, so one skill's name must not resolve under another — which is
        // what a lookup missing its skill_id would do. The two skills carry *different* names, which
        // is what makes this an assertion rather than a restatement of the fixture.
        // Soft-deleting one, or giving both the same name, hides the thing being tested: the first
        // makes the empty answer come from `deleted_at IS NULL` without ever reaching the version
        // lookup, the second makes the mutation fail on a row-count error instead of on the name.
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "one", "1.0.0", true);
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "two", "7.0.0", true);

        Access two = metadata.accessFor(DEMO_NAMESPACE_ID, "two", DEMO).orElseThrow();
        Access one = metadata.accessFor(DEMO_NAMESPACE_ID, "one", DEMO).orElseThrow();

        assertThat(versions.liveSnapshot(two, new VersionPin.Named("7.0.0")))
                .as("its own name, found through its own skill")
                .isPresent();
        assertThat(versions.liveSnapshot(two, new VersionPin.Named("1.0.0")))
                .as("a name another skill holds resolves to nothing here")
                .isEmpty();
        assertThat(versions.liveSnapshot(one, new VersionPin.Named("7.0.0")))
                .as("and the same the other way round")
                .isEmpty();
    }

    /**
     * ADR 0031's central rule, at the layer that implements it.
     *
     * <p>All three pin forms are checked, not just the bare address, because the leak the
     * published-only predicate prevents is not the pointer's: a draft submitted <em>after</em> the
     * skill went live is reachable as {@code @2.0.0} and as {@code @sha256:…} even though nothing
     * points at it. The fixture therefore has both a published version and a draft, so that the skill
     * is readable and the only thing separating them is the version's own state.
     */
    @Test
    void aDraftIsServedToNobodyButItsAuthor() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "mine", "1.0.0", true);
        insertSkillVersion(DEMO_NAMESPACE_ID, "mine", "2.0.0", false);
        Access mine = metadata.accessFor(DEMO_NAMESPACE_ID, "mine", DEMO).orElseThrow();

        assertThat(versions.liveSnapshot(mine, new VersionPin.Named("2.0.0")))
                .as("a draft is not addressable on the consumption plane")
                .isEmpty();
        assertThat(versions.liveSnapshot(mine, new VersionPin.Named("1.0.0")))
                .as("while the published version still is")
                .isPresent();

        assertThat(versions.authorSnapshot(mine, new VersionPin.Named("2.0.0")))
                .as("the author sees their own draft")
                .isPresent();
        assertThat(versions.authorSnapshot(mine, new VersionPin.Named("2.0.0")).orElseThrow().state())
                .as("and sees what state it is in")
                .isEqualTo(VersionState.DRAFT);
    }


    /**
     * v6: a draft has no number, so publishing it must be told one (ADR 0055).
     *
     * <p>And once assigned, a number never moves: asking the same row for a different one is a
     * different request and is refused, not ignored.
     */
    @Test
    void publishingANamelessDraftNeedsAReleaseNameAndAssignsItOnce() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "nameless", "1.0.0", true);
        insertSkillVersion(DEMO_NAMESPACE_ID, "nameless", null, false);
        Access access = metadata.accessFor(DEMO_NAMESPACE_ID, "nameless", DEMO).orElseThrow();
        String draftId = jdbc.sql("""
                SELECT v.id FROM skill_version v JOIN skill s ON s.id = v.skill_id
                WHERE s.name = 'nameless' AND v.state = 'draft'
                """).query(String.class).single();
        VersionPin pin = new VersionPin.RowId(draftId);

        assertThatThrownBy(() -> versions.publishVersion(access, pin, null))
                .as("a draft with no number and no release name has nothing to call itself")
                .isInstanceOf(VersionStateException.class)
                .hasMessageContaining("release name");

        assertThat(versions.publishVersion(access, pin, "1.1.0").orElseThrow().version())
                .isEqualTo("1.1.0");
        assertThatThrownBy(() -> versions.publishVersion(access, pin, "1.2.0"))
                .as("a number is assigned once")
                .isInstanceOf(VersionStateException.class)
                .hasMessageContaining("assigned once");
    }

    /**
     * A revoked version answers 410 on the consumption plane, not 404 (ADR 0058).
     *
     * <p>The pin is one the caller holds, so "it was there and was taken down" is different information
     * from "your address is wrong" — and the row's files are still there behind it.
     */
    @Test
    void aRevokedVersionIsGoneRatherThanMissing() {
        insertSkill(DEMO_NAMESPACE_ID, DEMO_USER_ID, "revoked", "1.0.0", true);
        insertSkillVersion(DEMO_NAMESPACE_ID, "revoked", "2.0.0", true);
        Access access = metadata.accessFor(DEMO_NAMESPACE_ID, "revoked", DEMO).orElseThrow();

        // The pointer names 1.0.0, so it is the current one and 2.0.0 is history.
        versions.revokeVersion(access, new VersionPin.Named("2.0.0"), "收回归档");

        assertThatThrownBy(() -> versions.liveSnapshot(access, new VersionPin.Named("2.0.0")))
                .as("the consumer is told it existed and was withdrawn")
                .isInstanceOf(VersionRevokedException.class);
        assertThatThrownBy(() -> versions.revokeVersion(access,
                new VersionPin.Named("1.0.0"), null))
                .as("the current version cannot be taken down")
                .isInstanceOf(VersionStateException.class)
                .hasMessageContaining("current version");

        versions.restoreVersion(access, new VersionPin.Named("2.0.0"));
        assertThat(versions.liveSnapshot(access, new VersionPin.Named("2.0.0")))
                .as("restoring makes it readable again without moving the pointer")
                .isPresent();
        assertThat(count("SELECT count(*) FROM skill_version WHERE state = 'revoked'"))
                .isZero();
    }

    /** A live skill with one version, inserted directly: the write path is another test's subject. */
    private String insertSkill(String namespaceId, String userId, String name, String version,
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

        insertVersionRow(skillId, versionId, version, published, userId, at);
        return skillId;
    }

    /** A further version of a skill the fixture has already created. */
    private void insertSkillVersion(String namespaceId, String name, String version,
            boolean published) {
        String skillId = jdbc.sql("SELECT id FROM skill WHERE namespace_id = :namespace AND name = :name")
                .param("namespace", namespaceId).param("name", name)
                .query(String.class).single();
        insertVersionRow(skillId, Ulid.generate(), version, published, DEMO_USER_ID,
                Timestamps.now());
    }

    /**
     * One version row, with its internal {@code number} computed the way the write path computes it.
     *
     * <p>{@code number} is the submission order and nothing else (ADR 0033): it appears in no address
     * and in no response, so a fixture cannot set it meaningfully any more. Allocating it here with
     * the same {@code MAX(number) + 1} the real insert uses keeps this fixture shaped like the rows
     * production writes — and the per-skill subquery is what keeps each skill's version list ordered,
     * which is what {@code findNewestNotDiscarded} reads.
     */
    private void insertVersionRow(String skillId, String versionId, String version, boolean published,
            String userId, String at) {
        jdbc.sql("""
                INSERT INTO skill_version (id, skill_id, number, version, digest, file_count,
                                           total_bytes, changelog, source, submitted_by, submitted_at,
                                           state, state_at, first_published_at, title, description, frontmatter)
                VALUES (:id, :skill,
                        (SELECT COALESCE(MAX(number), 0) + 1 FROM skill_version
                         WHERE skill_id = :skill),
                        :version, :digest, 0, 0, '', 'zip', :user, :at,
                        CASE WHEN :published THEN 'published' ELSE 'draft' END,
                        CASE WHEN :published THEN :at ELSE NULL END,
                        CASE WHEN :published THEN :at ELSE NULL END,
                        '', 'a skill', '{}')
                """)
                .param("id", versionId).param("skill", skillId).param("version", version)
                // Well-formed enough for a read path that never recomputes it: this test is about
                // which row is found, not about what a digest means.
                .param("digest", Ulid.generate())
                .param("published", published)
                .param("user", userId).param("at", at)
                .update();
    }
}
