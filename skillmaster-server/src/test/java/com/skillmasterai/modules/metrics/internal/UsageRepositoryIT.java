package com.skillmasterai.modules.metrics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.metrics.SkillLoad;
import com.skillmasterai.modules.metrics.UsageScope;
import com.skillmasterai.modules.metrics.UsageSnapshot;
import com.skillmasterai.support.AbstractIT;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * M15's two series against a real PostgreSQL: the day row deduplicates by account, the cumulative
 * count advances on every load, the account window drops older days, and one scope's rows never
 * leak into another's (v49 A5/A6, ADR 0149/0152/0153).
 */
@Sql("/sql/truncate-business-tables.sql")
class UsageRepositoryIT extends AbstractIT {

    private static final String DEMO_NAMESPACE_ID = "01M3HTGC79VYJGM8BFXHX2QYNH";
    private static final String DEMO_USER_ID = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER_USER_ID = "01M3HTG7GDZGTDME9B136ZVAW4";

    @Autowired
    private UsageRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void oneDayRowButEveryLoadCounts() {
        String skillId = insertSkill("usage-dedupe");
        LocalDate today = LocalDate.of(2026, 11, 5);

        repository.record(new SkillLoad(skillId, DEMO_USER_ID, today, UsageScope.PUBLIC));
        repository.record(new SkillLoad(skillId, DEMO_USER_ID, today, UsageScope.PUBLIC));
        repository.record(new SkillLoad(skillId, DEMO_USER_ID, today.minusDays(1),
                UsageScope.PUBLIC));

        assertThat(dayRows(skillId)).as("one row per account per day").isEqualTo(2);

        UsageSnapshot snapshot = repository.snapshots(List.of(skillId), UsageScope.PUBLIC, today)
                .get(skillId);
        assertThat(snapshot.hasCalls()).isTrue();
        assertThat(snapshot.callsBucketFloor()).isZero();
        assertThat(snapshot.lastUsed()).isEqualTo(today);
    }

    @Test
    void theAccountCountIsWindowed() {
        String skillId = insertSkill("usage-window");
        LocalDate today = LocalDate.of(2026, 11, 5);
        List<String> inside = users(9, "inside-");
        String outside = users(1, "outside-").get(0);

        inside.forEach(user -> repository.record(
                new SkillLoad(skillId, user, today, UsageScope.PUBLIC)));
        repository.record(new SkillLoad(skillId, outside, today.minusDays(40),
                UsageScope.PUBLIC));

        UsageSnapshot snapshot = repository.snapshots(List.of(skillId), UsageScope.PUBLIC, today)
                .get(skillId);
        assertThat(snapshot.invokers30dBucketFloor())
                .as("nine inside the 30-day window; the fortieth-day account would make ten")
                .isZero();
    }

    @Test
    void anotherScopesRowsDoNotLeakIntoThePublicSeries() {
        String skillId = insertSkill("usage-scope");
        LocalDate today = LocalDate.of(2026, 11, 5);

        repository.record(new SkillLoad(skillId, DEMO_USER_ID, today, UsageScope.PUBLIC));
        jdbc.sql("INSERT INTO skill_usage_day (skill_id, day, account_id, scope)"
                        + " VALUES (:skill, :day, :account, 'owner')")
                .param("skill", skillId)
                .param("day", today.toString())
                .param("account", OTHER_USER_ID)
                .update();

        UsageSnapshot snapshot = repository.snapshots(List.of(skillId), UsageScope.PUBLIC, today)
                .get(skillId);
        assertThat(snapshot.invokers30dBucketFloor())
                .as("the owner-scope row is not a public account").isZero();
    }

    @Test
    void aSkillWithNoLoadsIsAbsentRatherThanZero() {
        assertThat(repository.snapshots(List.of(insertSkill("usage-empty")), UsageScope.PUBLIC,
                LocalDate.of(2026, 11, 5))).isEmpty();
        assertThat(repository.snapshots(List.of(), UsageScope.PUBLIC, LocalDate.of(2026, 11, 5)))
                .isEqualTo(Map.of());
    }

    private int dayRows(String skillId) {
        return jdbc.sql("SELECT count(*) AS n FROM skill_usage_day WHERE skill_id = :skill")
                .param("skill", skillId)
                .query((rs, rowNum) -> rs.getInt("n"))
                .single();
    }

    private List<String> users(int count, String prefix) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = Ulid.generate();
            jdbc.sql("INSERT INTO app_user (id, handle, display_name, status, created_at)"
                            + " VALUES (:id, :handle, '', 'active', '2026-09-28T00:00:00Z')")
                    .param("id", id)
                    .param("handle", prefix + i)
                    .update();
            ids.add(id);
        }
        return ids;
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
}
