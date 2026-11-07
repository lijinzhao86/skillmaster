package com.skillmasterai.usecase.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.skill.SourceAdapter;
import com.skillmasterai.modules.skill.SourceAdapter.FetchedSource;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import com.skillmasterai.modules.skill.SourceRefreshJob;
import com.skillmasterai.modules.skill.SourceRegistry;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.skill.SourceSnapshot;
import com.skillmasterai.modules.skill.internal.SourceRepository;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.support.Zips;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The source state is settled when the draft is created (ADR 0161): the snapshot carries the
 * signals, one read serves every skill of a repository, and neither publishing nor the daily job
 * re-reads a settled version.
 */
class SyncSignalsIT extends SyncPipelineFixture {

    private static final String SIGNALS = "[{\"key\":\"stars\",\"label\":\"Stars\",\"kind\":\"count\","
            + "\"value\":\"1234\",\"display\":\"1.2k\"},"
            + "{\"key\":\"forks\",\"label\":\"Forks\",\"kind\":\"count\",\"value\":\"56\","
            + "\"display\":\"56\"},"
            + "{\"key\":\"last_commit\",\"label\":\"最近提交\",\"kind\":\"date\","
            + "\"value\":\"2026-11-06\",\"display\":\"2026-11-06\"}]";

    @Test
    void aDraftCarriesItsSnapshotAndOneReadServesTheRepository() {
        String repo = "acme/repo-" + suffix;
        String other = "acme/other-" + suffix;
        String a = "a-" + suffix;
        String b = "b-" + suffix;
        String c = "c-" + suffix;
        String o = "other-" + suffix;
        fake.signals.put(repo, SIGNALS);
        fake.signals.put(other, SIGNALS);
        fake.put(repo, "skills/a", "r1", List.of(file("skills/a/SKILL.md", skillMd(a, null))),
                license("MIT"));
        fake.put(repo, "skills/b", "r1", List.of(file("skills/b/SKILL.md", skillMd(b, null))),
                license("MIT"));
        fake.put(repo, "skills/c", "r1", List.of(file("skills/c/SKILL.md", skillMd(c, null))),
                license("MIT"));
        fake.put(other, "", "r1", List.of(file("SKILL.md", skillMd(o, null))), license("MIT"));
        List<SyncEntry> entries = List.of(entry(repo, "skills/a"), entry(repo, "skills/b"),
                entry(repo, "skills/c"), entry(other, ""));

        SyncReport report = pipeline(entries, 2).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result).containsOnly("created");
        assertThat(fake.signalsCalls).hasValue(2);
        for (String name : List.of(a, b, c, o)) {
            String versionId = latestVersionId(name);
            assertThat(snapshotStatus(versionId)).isEqualTo("ok");
            assertThat(snapshotMarked(versionId)).isTrue();
            assertThat(snapshotRevisionOf(versionId)).isEqualTo("r1");
            assertThat(snapshotSignals(versionId)).contains("\"key\":\"stars\"")
                    .contains("\"key\":\"forks\"").contains("\"key\":\"last_commit\"")
                    .contains("1234");
        }
        assertThat(report.entries()).allSatisfy(result ->
                assertThat(result.warnings()).doesNotContain("signals_pending"));

        SyncReport second = pipeline(entries, 2).run();

        assertThat(second.entries()).extracting(SyncEntryResult::result)
                .containsOnly("unchanged");
        assertThat(fake.signalsCalls).hasValue(2);

        String d = "d-" + suffix;
        fake.failingSignals.add(repo);
        fake.put(repo, "skills/d", "r1", List.of(file("skills/d/SKILL.md", skillMd(d, null))),
                license("MIT"));

        SyncReport third = pipeline(List.of(entry(repo, "skills/d")), 1).run();

        assertThat(third.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("created");
            assertThat(result.warnings()).contains("signals_pending");
        });
        assertThat(snapshotStatus(latestVersionId(d))).isEqualTo("pending");
        assertThat(snapshotSignals(latestVersionId(d))).isEqualTo("[]");
    }

    @Test
    void aReplayThatWritesTheFirstSnapshotReportsPendingSignals() {
        String name = "replay-" + suffix;
        String locator = "acme/replay-" + suffix;
        String md = skillMd(name, null);
        /* A hand-uploaded draft with the same content and no snapshot: the sync replays into it,
           which is still a write of the version's first snapshot. */
        submit.submit(Zips.ofText(Map.of(name + "/SKILL.md", md)), "hand", "hand", null,
                AuthenticatedSubject.ofService(OFFICIAL_USER));
        fake.failingSignals.add(locator);
        fake.put(locator, "", "r1", List.of(file("SKILL.md", md)), license("MIT"));

        SyncReport report = pipeline(List.of(entry(locator, "")), 1).run();

        assertThat(report.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("unchanged");
            assertThat(result.warnings()).contains("signals_pending");
        });
        assertThat(snapshotStatus(latestVersionId(name))).isEqualTo("pending");
    }

    @Test
    void onlyTheDirectoryThatChangedIsFetchedAndDrafted() {
        String repo = "acme/bump-" + suffix;
        String a = "a-" + suffix;
        String b = "b-" + suffix;
        fake.put(repo, "skills/a", "r1", List.of(file("skills/a/SKILL.md", skillMd(a, null))),
                license("MIT"));
        fake.put(repo, "skills/b", "r1", List.of(file("skills/b/SKILL.md", skillMd(b, null))),
                license("MIT"));
        List<SyncEntry> entries = List.of(entry(repo, "skills/a"), entry(repo, "skills/b"));
        pipeline(entries, 1).run();
        String bVersion = latestVersionId(b);
        String bFetched = snapshotFetchedAt(bVersion);
        int fetches = fake.fetchCalls.get();

        fake.put(repo, "skills/a", "r2", List.of(file("skills/a/SKILL.md", skillMd(a, null)),
                file("skills/a/run.py", "print(2)")), license("MIT"));

        SyncReport second = pipeline(entries, 1).run();

        assertThat(second.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "unchanged");
        assertThat(fake.fetchCalls.get()).isEqualTo(fetches + 1);
        /* One signals read for this repository in this run: only `a` reached the write. */
        assertThat(fake.signalsCalls).hasValue(2);
        assertThat(snapshotRevisionOf(latestVersionId(a))).isEqualTo("r2");
        assertThat(snapshotRevisionOf(bVersion)).isEqualTo("r1");
        assertThat(snapshotFetchedAt(bVersion)).isEqualTo(bFetched);
    }

    @Test
    void publishingAndRollingBackLeaveTheSyncedSnapshotAlone() {
        String name = "pub-" + suffix;
        String repo = "acme/pub-" + suffix;
        String md = skillMd(name, null);
        fake.put(repo, "", "r1", List.of(file("SKILL.md", md)), license("MIT"));
        pipeline(List.of(entry(repo, "")), 1).run();
        String first = latestVersionId(name);
        publishTo(name, first, "1.0.0");
        Map<String, String> firstSnapshot = snapshotRow(first);

        fake.put(repo, "", "r2", List.of(file("SKILL.md", md), file("run.py", "print(2)")),
                license("MIT"));
        pipeline(List.of(entry(repo, "")), 1).run();
        String second = latestVersionId(name);
        publishTo(name, second, "1.0.1");
        Map<String, String> secondSnapshot = snapshotRow(second);

        publish.publish("skillmaster", name, new VersionPin.RowId(first), null,
                AuthenticatedSubject.ofService(OFFICIAL_USER));

        assertThat(snapshotRow(first)).isEqualTo(firstSnapshot);
        assertThat(snapshotRow(second)).isEqualTo(secondSnapshot);
        assertThat(secondSnapshot.get("status")).isEqualTo("ok");
        assertThat(secondSnapshot.get("signals")).contains("\"key\":\"stars\"");

        String manual = "manual-" + suffix;
        SubmittedSkill hand = submit.submit(
                Zips.ofText(Map.of(manual + "/SKILL.md", skillMd(manual, null))), "hand", "hand",
                null, AuthenticatedSubject.ofService(OFFICIAL_USER));

        publish.publish("skillmaster", manual, new VersionPin.RowId(hand.draftId()), "1.0.0",
                AuthenticatedSubject.ofService(OFFICIAL_USER));

        /* No network on the publish path: the manual version's snapshot is written pending and the
           daily job fills it later. */
        assertThat(snapshotStatus(hand.draftId())).isEqualTo("pending");
        assertThat(snapshotSignals(hand.draftId())).isEqualTo("[]");
    }

    @Test
    void theDailyJobOnlyFillsGaps() {
        String pendingName = "pending-" + suffix;
        String okName = "settled-" + suffix;
        String errorName = "broken-" + suffix;
        String pendingVersion = publishManual(pendingName);
        String okVersion = publishManual(okName);
        String errorVersion = publishManual(errorName);
        jdbc.sql("UPDATE skill_source_snapshot SET status = 'ok', signals = :signals,"
                        + " fetched_at = '2020-01-01T00:00:00Z' WHERE skill_version_id = :version")
                .param("signals", SIGNALS)
                .param("version", okVersion)
                .update();
        jdbc.sql("UPDATE skill_source_snapshot SET status = 'error'"
                        + " WHERE skill_version_id = :version")
                .param("version", errorVersion)
                .update();
        String settledFetchedAt = snapshotFetchedAt(okVersion);

        CountingAdapter adapter = new CountingAdapter();
        SourceRepository repository = new SourceRepository(jdbc);
        SourceService service = new SourceService(repository,
                new SourceRegistry(List.of(adapter)), json);

        new SourceRefreshJob(repository, service).refresh();

        /* The query is database-wide, and other tests in this context leave their own current
           versions behind; only this test's three skills say anything about this run. */
        List<String> mine = adapter.fetched.stream()
                .filter(locator -> locator.endsWith("-" + suffix))
                .toList();
        assertThat(mine).hasSize(2);
        assertThat(mine).noneMatch(locator -> locator.endsWith("/" + okName));
        assertThat(snapshotStatus(pendingVersion)).isEqualTo("ok");
        assertThat(snapshotStatus(errorVersion)).isEqualTo("ok");
        assertThat(snapshotFetchedAt(okVersion)).isEqualTo(settledFetchedAt);
        assertThat(snapshotSignals(okVersion)).isEqualTo(SIGNALS);
    }

    private String publishManual(String name) {
        SubmittedSkill hand = submit.submit(
                Zips.ofText(Map.of(name + "/SKILL.md", skillMd(name, null))), "hand", "hand",
                null, AuthenticatedSubject.ofService(OFFICIAL_USER));
        publish.publish("skillmaster", name, new VersionPin.RowId(hand.draftId()), "1.0.0",
                AuthenticatedSubject.ofService(OFFICIAL_USER));
        return hand.draftId();
    }

    private void publishTo(String name, String versionId, String releaseName) {
        publish.publish("skillmaster", name, new VersionPin.RowId(versionId), releaseName,
                AuthenticatedSubject.ofService(OFFICIAL_USER));
    }

    private Map<String, String> snapshotRow(String versionId) {
        return jdbc.sql("SELECT revision, signals, status, fetched_at"
                        + " FROM skill_source_snapshot WHERE skill_version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> Map.of(
                        "revision", rs.getString("revision"),
                        "signals", rs.getString("signals"),
                        "status", rs.getString("status"),
                        "fetched_at", rs.getString("fetched_at")))
                .single();
    }

    /** Counts the versions the daily job asks for; claims `official`, the origin a manual upload gets. */
    private static final class CountingAdapter implements SourceAdapter {

        private final List<String> fetched = new ArrayList<>();

        @Override
        public String kind() {
            return "official";
        }

        @Override
        public SourceBinding parse(String locator, String path) {
            return new SourceBinding("official", locator, path, "官方", null);
        }

        @Override
        public FetchedSource fetch(SourceBinding binding, String revisionHint) {
            fetched.add(binding.locator());
            return new FetchedSource("head-1", SIGNALS, SourceSnapshot.OK, null);
        }
    }
}
