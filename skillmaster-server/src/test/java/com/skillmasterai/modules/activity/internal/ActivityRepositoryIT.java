package com.skillmasterai.modules.activity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.activity.ActivityEvent;
import com.skillmasterai.modules.activity.ActivityKinds;
import com.skillmasterai.modules.activity.InvokeActivity;
import com.skillmasterai.modules.activity.SearchActivity;
import com.skillmasterai.modules.activity.SearchHit;
import com.skillmasterai.modules.activity.UsageHistory;
import com.skillmasterai.support.AbstractIT;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.ObjectMapper;

/**
 * M17's write rules against a real PostgreSQL (v58): attribution picks the newest matching search
 * inside the 30-minute window, a continuation merges instead of inserting, and the two directions
 * of "not the same question" — a different query, a page after the window — stay apart.
 */
@Sql("/sql/truncate-business-tables.sql")
class ActivityRepositoryIT extends AbstractIT {

    private static final String ACTOR = "01M3HTG7GCCVBGRPAFFSVSF12W";
    private static final String OTHER = "01M3HTG7GDZGTDME9B136ZVAW4";

    @Autowired
    private ActivityRepository repository;

    @Autowired
    private ObjectMapper json;

    @Test
    void aCallInsideTheWindowAttachesToTheSearchThatReturnedIt() {
        String at = now();
        search(at, "pdf", List.of(hit("skill-x", "demo/x")), false);

        invoke("skill-x", later(at, 5 * 60));

        Map<String, Object> row = onlyRow(ACTOR, ActivityKinds.INVOKE);
        assertThat(row.get("search_event_id")).isNotNull();
        assertThat(row.get("search_rank")).isEqualTo(1);
    }

    @Test
    void theNewestSearchThatContainedTheSkillWins() {
        String at = now();
        search(at, "pdf", List.of(hit("skill-x", "demo/x")), false);
        search(later(at, 60), "report", List.of(hit("skill-x", "demo/x")), false);

        invoke("skill-x", later(at, 120));

        long second = idOfSearch("report");
        assertThat(onlyRow(ACTOR, ActivityKinds.INVOKE).get("search_event_id"))
                .isEqualTo(second);
    }

    @Test
    void aCallAfterThirtyMinutesIsDirect() {
        String at = now();
        search(at, "pdf", List.of(hit("skill-x", "demo/x")), false);

        invoke("skill-x", later(at, 30 * 60 + 1));

        assertThat(onlyRow(ACTOR, ActivityKinds.INVOKE).get("search_event_id")).isNull();
    }

    @Test
    void theWindowIsClosedAtExactlyThirtyMinutes() {
        String at = now();
        search(at, "pdf", List.of(hit("skill-x", "demo/x")), false);

        invoke("skill-x", later(at, 30 * 60));

        assertThat(onlyRow(ACTOR, ActivityKinds.INVOKE).get("search_event_id")).isNotNull();
    }

    @Test
    void anotherAccountsSearchIsNeverUsed() {
        String at = now();
        repository.recordSearch(new SearchActivity(OTHER, "pdf", Map.of(), 1L,
                List.of(hit("skill-x", "demo/x")), false, at));

        invoke("skill-x", later(at, 60));

        assertThat(onlyRow(ACTOR, ActivityKinds.INVOKE).get("search_event_id")).isNull();
    }

    @Test
    void aSecondPageMergesIntoTheFirstSearch() {
        String at = now();
        repository.recordSearch(new SearchActivity(ACTOR, "pdf", Map.of(), 3L,
                List.of(hit("skill-a", "demo/a")), false, at));

        repository.recordSearch(new SearchActivity(ACTOR, "pdf", Map.of(), 3L,
                List.of(hit("skill-b", "demo/b"), hit("skill-c", "demo/c")), true,
                later(at, 60)));

        List<Map<String, Object>> searches = rows(ACTOR, ActivityKinds.SEARCH);
        assertThat(searches).as("one query, one row").hasSize(1);
        SearchDetail detail = detail(searches.get(0));
        assertThat(detail.returned()).isEqualTo(3);
        assertThat(detail.pages()).isEqualTo(2);
        assertThat(detail.skillIds()).containsExactly("skill-a", "skill-b", "skill-c");
        assertThat(detail.total()).isEqualTo(3L);

        invoke("skill-c", later(at, 120));
        assertThat(onlyRow(ACTOR, ActivityKinds.INVOKE).get("search_rank")).isEqualTo(3);
    }

    @Test
    void aPageAfterTheWindowStartsItsOwnSearch() {
        String at = now();
        search(at, "pdf", List.of(hit("skill-a", "demo/a")), false);

        repository.recordSearch(new SearchActivity(ACTOR, "pdf", Map.of(), 2L,
                List.of(hit("skill-b", "demo/b")), true, later(at, 31 * 60)));

        assertThat(rows(ACTOR, ActivityKinds.SEARCH)).hasSize(2);
    }

    @Test
    void aDifferentQueryIsNeverMerged() {
        String at = now();
        search(at, "pdf", List.of(hit("skill-a", "demo/a")), false);
        repository.recordSearch(new SearchActivity(ACTOR, "report", Map.of(), 1L,
                List.of(hit("skill-b", "demo/b")), true, later(at, 60)));

        assertThat(rows(ACTOR, ActivityKinds.SEARCH)).hasSize(2);
    }

    @Test
    void aDifferentFilterIsNeverMerged() {
        String at = now();
        repository.recordSearch(new SearchActivity(ACTOR, "pdf", Map.of("scope", "public"), 1L,
                List.of(hit("skill-a", "demo/a")), false, at));
        repository.recordSearch(new SearchActivity(ACTOR, "pdf", Map.of("scope", "library"), 1L,
                List.of(hit("skill-b", "demo/b")), true, later(at, 60)));

        assertThat(rows(ACTOR, ActivityKinds.SEARCH)).hasSize(2);
    }

    @Test
    void aCallWhoseSearchLeftTheWindowIsShownAsDirect() {
        // Viewed from 90 days ahead: the search is outside the window, the call 29 minutes later
        // is inside it. Both rows still live in this week's partition.
        String searchAt = now();
        String callAt = later(searchAt, 29 * 60);
        String viewedAt = Timestamps.format(
                Instant.parse(searchAt).plusSeconds(90 * 24 * 3600L + 60));
        search(searchAt, "pdf", List.of(hit("skill-boundary", "demo/boundary")), false);
        invoke("skill-boundary", callAt);

        UsageHistory.Page page = repository.interactions(ACTOR, 1, 50, viewedAt);

        // The search is outside the 90-day window; the call it produced is inside it. Hiding the
        // call would make the page disagree with the stats that count it.
        assertThat(page.items()).hasSize(1);
        UsageHistory.Direct item = (UsageHistory.Direct) page.items().get(0);
        assertThat(item.skill()).isEqualTo("demo/skill-boundary");
        UsageHistory.Stats stats = repository.stats(ACTOR, viewedAt);
        assertThat(stats.calls()).isEqualTo(1);
        // The call cannot count as "this search led to a call": that search is not in the window,
        // and the detail list shows the call as direct. Stats and detail must agree.
        assertThat(stats.searches()).isZero();
        assertThat(stats.converted()).isZero();
        assertThat(stats.missed()).isZero();
    }

    @Test
    void threeCallsToTheSameSearchAreAllAttached() {
        String at = now();
        search(at, "pdf", List.of(hit("skill-a", "demo/a"), hit("skill-b", "demo/b"),
                hit("skill-c", "demo/c")), false);
        invoke("skill-a", later(at, 120));
        invoke("skill-b", later(at, 180));
        invoke("skill-c", later(at, 240));

        UsageHistory.Page page = repository.interactions(ACTOR, 1, 50, later(at, 300));
        assertThat(page.items()).hasSize(1);
        UsageHistory.Search item = (UsageHistory.Search) page.items().get(0);
        assertThat(item.invocations()).extracting(UsageHistory.Invocation::skill)
                .containsExactly("demo/skill-a", "demo/skill-b", "demo/skill-c");
        UsageHistory.Stats stats = repository.stats(ACTOR, later(at, 300));
        assertThat(stats.calls()).isEqualTo(3);
        assertThat(stats.converted()).isEqualTo(1);
    }

    @Test
    void aGenericEventKeepsItsDetail() {
        repository.record(new ActivityEvent(ACTOR, null, ActivityKinds.BROWSE, null, null, null,
                null, null, Map.of("returned", 20), now()));

        Map<String, Object> row = onlyRow(ACTOR, ActivityKinds.BROWSE);
        assertThat(row.get("detail").toString()).contains("\"returned\":20");
    }

    @Test
    void theDailyRollupKeepsBothMeasuresAcrossRepeatedRuns() {
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String skill = insertSkill("activity-summary");
        repository.upsertInvokes(new ActivityRepository.InvokeDay(skill, ACTOR, ACTOR, day,
                2, day + "T10:00:00Z"));
        repository.upsertInvokes(new ActivityRepository.InvokeDay(skill, ACTOR, ACTOR, day,
                3, day + "T11:00:00Z"));
        repository.upsertVersions(skill, ACTOR, day, 2, day + "T09:00:00Z");

        List<UsageHistory.UserDay> rows = repository.userDays(skill, now());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).invoke()).as("absolute, not added twice").isEqualTo(3);
        assertThat(rows.get(0).submitted()).isEqualTo(2);
        assertThat(rows.get(0).last()).isEqualTo(day + "T11:00:00Z");
    }

    @Test
    void mineSplitsCallsByVersion() {
        String at = now();
        invoke("skill-a", at);
        repository.recordInvoke(new InvokeActivity(ACTOR, ACTOR, "skill-a", "demo/skill-a",
                "version-2", "2.0.0", later(at, 60)));

        UsageHistory.Mine mine = repository.mine("skill-a", ACTOR, later(at, 120));
        assertThat(mine.calls()).isEqualTo(2);
        assertThat(mine.versions()).isEqualTo(2);
        assertThat(mine.byVersion()).extracting(UsageHistory.MineVersion::version)
                .containsExactlyInAnyOrder("1.0.0", "2.0.0");
    }

    private String insertSkill(String name) {
        String skillId = com.skillmasterai.common.Ulid.generate();
        jdbc.sql("""
                INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                                   visibility, created_by, created_at, updated_at)
                VALUES (:id, '01M3HTGC79VYJGM8BFXHX2QYNH', :name, '', 'a probe', '{}', 'private',
                        :userId, '2026-11-05T00:00:00Z', '2026-11-05T00:00:00Z')
                """)
                .param("id", skillId)
                .param("name", name)
                .param("userId", ACTOR)
                .update();
        return skillId;
    }

    private void search(String at, String query, List<SearchHit> hits, boolean continuation) {
        repository.recordSearch(new SearchActivity(ACTOR, query, Map.of("scope", "public"),
                (long) hits.size(), hits, continuation, at));
    }

    private void invoke(String skillId, String at) {
        repository.recordInvoke(new InvokeActivity(ACTOR, ACTOR, skillId, "demo/" + skillId,
                "version-1", "1.0.0", at));
    }

    private static SearchHit hit(String id, String address) {
        return new SearchHit(id, address);
    }

    private List<Map<String, Object>> rows(String actor, String kind) {
        return jdbc.sql("SELECT id, at, detail, search_event_id, search_rank"
                        + " FROM activity_event WHERE actor_user_id = :actor AND kind = :kind"
                        + " ORDER BY at, id")
                .param("actor", actor)
                .param("kind", kind)
                .query()
                .listOfRows();
    }

    private Map<String, Object> onlyRow(String actor, String kind) {
        List<Map<String, Object>> rows = rows(actor, kind);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private long idOfSearch(String query) {
        return jdbc.sql("SELECT id FROM activity_event WHERE actor_user_id = :actor"
                        + " AND kind = :kind AND target = :query ORDER BY id DESC LIMIT 1")
                .param("actor", ACTOR)
                .param("kind", ActivityKinds.SEARCH)
                .param("query", query)
                .query(Long.class)
                .single();
    }

    private SearchDetail detail(Map<String, Object> row) {
        return json.readValue((String) row.get("detail"), SearchDetail.class);
    }

    private static String now() {
        return Timestamps.now();
    }

    private static String later(String at, int seconds) {
        return Timestamps.format(Instant.parse(at).plusSeconds(seconds));
    }
}
