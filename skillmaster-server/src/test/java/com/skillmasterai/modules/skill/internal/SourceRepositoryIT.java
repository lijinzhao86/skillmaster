package com.skillmasterai.modules.skill.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.SkillSource;
import com.skillmasterai.modules.skill.SourceSnapshot;
import com.skillmasterai.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * The library relation's count against a real PostgreSQL: joins are read from the library table
 * rather than copied into a second one. The use series moved to M15 (`UsageRepositoryIT`).
 */
@Sql("/sql/truncate-business-tables.sql")
class SourceRepositoryIT extends AbstractIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER_USER_ID = "01M3HTG7GDZGTDME9B136ZVAW4";

    @Autowired
    private SourceRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void joinsComeFromTheLibraryTable() {
        String skillId = insertSkill("usage-joins");
        insertJoin(DEMO_USER_ID, skillId);
        insertJoin(OTHER_USER_ID, skillId);

        assertThat(repository.joins(skillId)).isEqualTo(2);
        assertThat(repository.joins(insertSkill("no-joins"))).isZero();
    }

    @Test
    void aRefreshNeverMovesTheRevisionOfAVersion() {
        String skillId = insertSkill("snapshot-revision");
        String versionId = insertVersion(skillId);
        SkillSource source = repository.putOrigin(skillId, "github", "owner/repo", "skills/x",
                "GitHub", "https://github.com/owner/repo");

        // Publishing writes the snapshot pending with no revision; the first successful fetch is
        // what records the commit this version came from.
        repository.putSnapshot(source.id(), versionId, null, "[]", SourceSnapshot.PENDING, false);
        repository.putSnapshot(source.id(), versionId, "commit-b", "[{\"key\":\"stars\"}]",
                SourceSnapshot.OK, false);
        assertThat(repository.findSnapshot(source.id(), versionId).orElseThrow().revision())
                .isEqualTo("commit-b");

        // A later refresh sees upstream HEAD move; the version still came from commit-b.
        repository.putSnapshot(source.id(), versionId, "commit-c", "[{\"key\":\"stars\"}]",
                SourceSnapshot.OK, false);
        assertThat(repository.findSnapshot(source.id(), versionId).orElseThrow().revision())
                .isEqualTo("commit-b");
    }

    @Test
    void onlyASnapshotTheSyncWroteIsMarkedAndARefreshDoesNotClearIt() {
        String skillId = insertSkill("snapshot-mark");
        String versionId = insertVersion(skillId);
        SkillSource source = repository.putOrigin(skillId, "github", "owner/repo", "skills/x",
                "GitHub", "https://github.com/owner/repo");

        repository.putSnapshot(source.id(), versionId, null, "[]", SourceSnapshot.PENDING, false);
        assertThat(repository.hasSyncSnapshot(versionId)).isFalse();

        // A replay can turn a version the sync found into one it wrote, and the mark only grows.
        repository.putSnapshot(source.id(), versionId, "commit-a", "[]",
                SourceSnapshot.PENDING, true);
        assertThat(repository.hasSyncSnapshot(versionId)).isTrue();

        repository.putSnapshot(source.id(), versionId, "commit-a", "[]", SourceSnapshot.OK, false);
        assertThat(repository.hasSyncSnapshot(versionId)).isTrue();
    }

    private String insertVersion(String skillId) {
        String versionId = Ulid.generate();
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
        return versionId;
    }

    private String insertSkill(String name) {
        String skillId = Ulid.generate();
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, :namespaceId, :name, '', 'a probe', '{}', 'private', :userId,
                        '2026-11-05T00:00:00Z', '2026-11-05T00:00:00Z')
                """)
                .param("id", skillId)
                .param("namespaceId", DEMO_NAMESPACE_ID)
                .param("name", name)
                .param("userId", DEMO_USER_ID)
                .update();
        return skillId;
    }

    private void insertJoin(String userId, String skillId) {
        jdbc.sql("""
                INSERT INTO skill_library_join (user_id, skill_id, created_at)
                VALUES (:userId, :skillId, '2026-11-05T00:00:00Z')
                """)
                .param("userId", userId)
                .param("skillId", skillId)
                .update();
    }
}
