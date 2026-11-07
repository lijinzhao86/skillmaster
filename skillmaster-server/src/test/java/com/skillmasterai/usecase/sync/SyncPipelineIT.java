package com.skillmasterai.usecase.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.support.Zips;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pipeline against the real database and a fake source: expansion, gates, idempotency, writes,
 * cleanup, licences, scans and provenance.
 *
 * <p>Naming, prefixes and address stability live in {@link SyncNamingIT}; the fixture is shared.
 */
class SyncPipelineIT extends SyncPipelineFixture {

    @Test
    void aFirstRunCreatesOfficialDraftsAndNothingIsPublished() {
        String one = "one-" + suffix;
        String ab = "ab-" + suffix;
        fake.put("acme/one-" + suffix, "", "a1", List.of(file("SKILL.md", skillMd(one, null))), null);
        fake.put("acme/tools-" + suffix, "skills/ab", "a1", List.of(
                file("skills/ab/SKILL.md", skillMd(ab, null)),
                file("skills/ab/run.py", "print(1)")), license("MIT"));

        SyncReport report = pipeline(List.of(
                entry("acme/one-" + suffix, ""), entry("acme/tools-" + suffix, "skills/ab")), 2).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "created");
        assertThat(skillCount(one)).isEqualTo(1);
        assertThat(skillCount(ab)).isEqualTo(1);
        assertThat(draftCount(one)).isEqualTo(1);
        assertThat(draftCount(ab)).isEqualTo(1);
        assertThat(versionFiles(ab)).isEqualTo(2);
        assertThat(licenseStatus(ab)).isEqualTo("ok");
        assertThat(currentVersion(one)).isNull();
        Map<String, Object> origin = origin(ab);
        assertThat(origin.get("kind")).isEqualTo("fake");
        assertThat(origin.get("locator")).isEqualTo("acme/tools-" + suffix);
        assertThat(origin.get("path")).isEqualTo("skills/ab");
    }

    @Test
    void theSameRevisionIsSkippedBeforeTheDownload() {
        String one = "one-" + suffix;
        fake.put("acme/one-" + suffix, "", "a1", List.of(file("SKILL.md", skillMd(one, null))), null);
        SyncPipeline pipeline = pipeline(List.of(entry("acme/one-" + suffix, "")), 1);

        pipeline.run();
        SyncReport second = pipeline.run();

        assertThat(second.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("unchanged"));
        assertThat(draftCount(one)).isEqualTo(1);
        assertThat(fake.fetchCalls).hasValue(1);
    }

    @Test
    void aNewRevisionProducesANewDraftAndLeavesTheOldOneAlone() {
        String ab = "ab-" + suffix;
        fake.put("acme/tools-" + suffix, "skills/ab", "a1", List.of(
                file("skills/ab/SKILL.md", skillMd(ab, null)),
                file("skills/ab/run.py", "print(1)")), license("MIT"));
        SyncPipeline pipeline = pipeline(List.of(entry("acme/tools-" + suffix, "skills/ab")), 1);
        pipeline.run();

        fake.put("acme/tools-" + suffix, "skills/ab", "b2", List.of(
                file("skills/ab/SKILL.md", skillMd(ab, null)),
                file("skills/ab/run.py", "print(2)")), license("MIT"));
        SyncReport second = pipeline.run();

        assertThat(second.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("created"));
        assertThat(versionCount(ab)).isEqualTo(2);
    }

    @Test
    void aNonPermissiveLicenseBlocksTheDraft() {
        String gpl = "gpl-" + suffix;
        fake.put("acme/gpl-" + suffix, "", "a1", List.of(file("SKILL.md", skillMd(gpl, null))),
                license("GPL-3.0"));

        SyncReport report = pipeline(List.of(entry("acme/gpl-" + suffix, "")), 1).run();

        assertThat(report.entries()).singleElement()
                .satisfies(result -> {
                    assertThat(result.result()).isEqualTo("blocked");
                    assertThat(result.reason()).contains("license_not_allowed");
                });
        assertThat(skillCount(gpl)).isZero();
    }

    @Test
    void aMissingLicenseStillSyncsAndRecordsMissing() {
        String none = "none-" + suffix;
        fake.put("acme/none-" + suffix, "", "a1", List.of(file("SKILL.md", skillMd(none, null))), null);

        pipeline(List.of(entry("acme/none-" + suffix, "")), 1).run();

        assertThat(licenseStatus(none)).isEqualTo("missing");
        assertThat(licenseSpdx(none)).isEqualTo("NOASSERTION");
        assertThat(changelog(none)).contains("未声明");
    }

    @Test
    void aFileThatDisagreesWithTheDeclarationWinsAndSaysPendingCalibration() {
        String conflict = "conflict-" + suffix;
        fake.put("acme/conflict-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(conflict, "GPL-3.0"))), license("MIT"));

        pipeline(List.of(entry("acme/conflict-" + suffix, "")), 1).run();

        assertThat(licenseStatus(conflict)).isEqualTo("ok");
        assertThat(licenseSpdx(conflict)).isEqualTo("MIT");
        assertThat(changelog(conflict)).contains("待校准").contains("GPL-3.0");
    }

    @Test
    void aLicenseRemovedByANewCommitSaysCancelled() {
        String removed = "removed-" + suffix;
        fake.put("acme/removed-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(removed, null)),
                        file("run.py", "print(1)")), license("MIT"));
        SyncPipeline pipeline = pipeline(List.of(entry("acme/removed-" + suffix, "")), 1);
        pipeline.run();

        /* The license is gone *and* the content moved: a license-only change has no content commit
           in the skill directory to trigger on (PRD 不做 #15), so the test changes both. */
        fake.put("acme/removed-" + suffix, "", "b2",
                List.of(file("SKILL.md", skillMd(removed, null)),
                        file("run.py", "print(2)")), null);
        pipeline.run();

        assertThat(licenseStatus(removed)).isEqualTo("missing");
        assertThat(latestChangelog(removed)).contains("已取消");
    }

    @Test
    void oneBrokenEntryDoesNotStopTheOthers() {
        String good = "good-" + suffix;
        fake.put("acme/one-" + suffix, "", "a1", List.of(file("SKILL.md", skillMd(good, null))), null);
        fake.failing.add("acme/broken-" + suffix);

        SyncReport report = pipeline(List.of(entry("acme/broken-" + suffix, ""), entry("acme/one-" + suffix, "")), 2)
                .run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .contains("failed", "created");
        assertThat(skillCount(good)).isEqualTo(1);
    }

    @Test
    void aSyncedNameAlreadyHeldByAnotherOriginIsBlockedBeforeAnythingIsWritten() {
        String clash = "clash-" + suffix;
        metadata.findOrCreate(OFFICIAL_NAMESPACE,
                new SkillMetadata(clash, clash, "d", "{}"), OFFICIAL_USER, Timestamps.now());
        jdbc.sql("INSERT INTO skill_source (id, skill_id, role, kind, locator, path, label, url,"
                        + " created_at) SELECT :id, id, 'origin', 'official', :locator, '', '官方',"
                        + " NULL, :now FROM skill WHERE namespace_id = :ns AND name = :name")
                .param("id", Ulid.generate())
                .param("locator", "skillmaster/" + clash)
                .param("now", Timestamps.now())
                .param("ns", OFFICIAL_NAMESPACE)
                .param("name", clash)
                .update();
        fake.put("acme/clash-" + suffix, "", "a1", List.of(file("SKILL.md", skillMd(clash, null))), null);

        SyncReport report = pipeline(List.of(entry("acme/clash-" + suffix, "")), 1).run();

        assertThat(report.entries()).singleElement()
                .satisfies(result -> assertThat(result.reason()).isEqualTo("origin_conflict"));
        assertThat(versionCount(clash)).isZero();
    }

    @Test
    void aSoftDeletedSkillIsSkippedWithItsOwnReason() {
        String deleted = "deleted-" + suffix;
        metadata.findOrCreate(OFFICIAL_NAMESPACE,
                new SkillMetadata(deleted, deleted, "d", "{}"), OFFICIAL_USER, Timestamps.now());
        jdbc.sql("UPDATE skill SET deleted_at = :at WHERE name = :name")
                .param("at", Timestamps.now()).param("name", deleted).update();
        fake.put("acme/deleted-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(deleted, null))), license("MIT"));

        SyncReport report = pipeline(List.of(entry("acme/deleted-" + suffix, "")), 1).run();

        assertThat(report.entries()).singleElement()
                .satisfies(result -> assertThat(result.reason()).isEqualTo("skill_deleted"));
        assertThat(versionCount(deleted)).isZero();
    }

    @Test
    void aNewRevisionDiscardsThePreviousUnpublishedSyncDraft() {
        String ab = "ab-" + suffix;
        fake.put("acme/tools-" + suffix, "skills/ab", "a1",
                List.of(file("skills/ab/SKILL.md", skillMd(ab, null)),
                        file("skills/ab/run.py", "print(1)")), license("MIT"));
        SyncPipeline pipeline = pipeline(List.of(entry("acme/tools-" + suffix, "skills/ab")), 1);
        pipeline.run();
        jdbc.sql("UPDATE skill_version SET changelog = 'hand edit' WHERE state = 'draft'"
                        + " AND skill_id = (SELECT id FROM skill WHERE name = :name)")
                .param("name", ab).update();

        fake.put("acme/tools-" + suffix, "skills/ab", "b2",
                List.of(file("skills/ab/SKILL.md", skillMd(ab, null)),
                        file("skills/ab/run.py", "print(2)")), license("MIT"));
        pipeline.run();

        assertThat(draftCount(ab)).isEqualTo(1);
        assertThat(discardedCount(ab)).isEqualTo(1);
    }

    @Test
    void aDiscardedRevisionIsNotRebuilt() {
        String ab = "ab-" + suffix;
        fake.put("acme/tools-" + suffix, "skills/ab", "a1",
                List.of(file("skills/ab/SKILL.md", skillMd(ab, null))), license("MIT"));
        SyncPipeline pipeline = pipeline(List.of(entry("acme/tools-" + suffix, "skills/ab")), 1);
        pipeline.run();
        jdbc.sql("UPDATE skill_version SET state = 'discarded', state_at = CURRENT_TIMESTAMP"
                        + " WHERE skill_id = (SELECT id FROM skill WHERE name = :name)")
                .param("name", ab).update();

        SyncReport second = pipeline.run();

        assertThat(second.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("unchanged"));
        assertThat(versionCount(ab)).isEqualTo(1);
    }

    @Test
    void aWarningRidesAlongWithoutBlockingTheDraft() {
        String warned = "warned-" + suffix;
        fake.put("acme/warned-" + suffix, "", "a1", List.of(
                file("SKILL.md", skillMd(warned, null)),
                file("README.md", "See https://example.test/docs\n")), license("MIT"));

        SyncReport report = pipeline(List.of(entry("acme/warned-" + suffix, "")), 1).run();

        assertThat(report.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("created");
            assertThat(result.warnings()).anyMatch(warning -> warning.startsWith("external_url:"));
        });
        assertThat(skillCount(warned)).isEqualTo(1);
    }

    @Test
    void aWriteThatTheValidatorRefusesFailsOnlyItsOwnEntry() {
        String good = "good-" + suffix;
        fake.put("acme/bad-name-" + suffix, "skills/bad", "a1", List.of(
                file("skills/bad/SKILL.md", skillMd("bad-" + suffix, null)),
                file("skills/bad/notes?.md", "a question mark")), license("MIT"));
        fake.put("acme/good-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(good, null))), license("MIT"));

        SyncReport report = pipeline(List.of(
                entry("acme/bad-name-" + suffix, "skills/bad"),
                entry("acme/good-" + suffix, "")), 2).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .contains("failed", "created");
        assertThat(report.entries()).filteredOn(result -> "failed".equals(result.result()))
                .singleElement()
                .satisfies(result -> assertThat(result.reason()).isEqualTo("write_failed"));
        assertThat(skillCount(good)).isEqualTo(1);
    }

    @Test
    void theBaseRefusesAFilesetOverTheConfiguredCeiling() {
        String big = "big-" + suffix;
        fake.put("acme/big-" + suffix, "", "a1", List.of(
                file("SKILL.md", skillMd(big, null)),
                new FetchedFile("huge.bin", new byte[1_048_577])), license("MIT"));

        SyncReport report = pipeline(List.of(entry("acme/big-" + suffix, "")), 1).run();

        assertThat(report.entries()).singleElement()
                .satisfies(result -> assertThat(result.reason()).isEqualTo("too_large"));
        assertThat(skillCount(big)).isZero();
    }

    @Test
    void publishingASyncedDraftKeepsItsOriginSnapshotAndLicense() {
        String ab = "ab-" + suffix;
        fake.put("acme/tools-" + suffix, "skills/ab", "a1", List.of(
                file("skills/ab/SKILL.md", skillMd(ab, null)),
                file("skills/ab/run.py", "print(1)")), license("MIT"));
        pipeline(List.of(entry("acme/tools-" + suffix, "skills/ab")), 1).run();
        String versionId = latestVersionId(ab);

        var promoted = publish.publish("skillmaster", ab, new VersionPin.RowId(versionId),
                "1.0.0", AuthenticatedSubject.ofService(OFFICIAL_USER));

        assertThat(promoted).isPresent();
        assertThat(origin(ab).get("kind")).isEqualTo("fake");
        assertThat(snapshotRevision(ab)).isEqualTo("a1");
        assertThat(licenseStatus(ab)).isEqualTo("ok");
    }

    @Test
    void aRunProcessesMoreEntriesThanTheConcurrency() {
        List<SyncEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = "many-" + i + "-" + suffix;
            fake.put("acme/many-" + i + "-" + suffix, "", "a1",
                    List.of(file("SKILL.md", skillMd(name, null))), license("MIT"));
            entries.add(entry("acme/many-" + i + "-" + suffix, ""));
        }

        SyncReport report = pipeline(entries, 2).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsOnly("created");
        assertThat(report.entries()).hasSize(5);
    }

    @Test
    void twoEntriesThatResolveToTheSameAddressDoNotCreateTwoSkills() {
        String clash = "same-" + suffix;
        fake.put("acme/left-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(clash, null))), license("MIT"));
        fake.put("acme/right-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(clash, null))), license("MIT"));

        SyncReport report = pipeline(List.of(entry("acme/left-" + suffix, ""),
                entry("acme/right-" + suffix, "")), 2).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "blocked");
        assertThat(report.entries().get(1).reason()).isEqualTo("duplicate_address");
        assertThat(skillCount(clash)).isEqualTo(1);
    }

    @Test
    void aRootLicenseChangeWithNoContentCommitIsNotFetched() {
        String unchanged = "licence-" + suffix;
        fake.put("acme/licence-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(unchanged, null))), license("MIT"));
        SyncPipeline pipeline = pipeline(List.of(entry("acme/licence-" + suffix, "")), 1);
        pipeline.run();

        fake.put("acme/licence-" + suffix, "", "a1",
                List.of(file("SKILL.md", skillMd(unchanged, null))), license("GPL-3.0"));
        SyncReport second = pipeline.run();

        assertThat(second.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("unchanged"));
        assertThat(licenseStatus(unchanged)).isEqualTo("ok");
        assertThat(licenseSpdx(unchanged)).isEqualTo("MIT");
    }

    @Test
    void selectAllExpandsEveryChildAndWritesEachOne() {
        String a = "a-" + suffix;
        String b = "b-" + suffix;
        fake.children.put("acme/many-" + suffix + "|skills", List.of("a", "b"));
        fake.put("acme/many-" + suffix, "skills/a", "a1",
                List.of(file("skills/a/SKILL.md", skillMd(a, null))), license("MIT"));
        fake.put("acme/many-" + suffix, "skills/b", "a1",
                List.of(file("skills/b/SKILL.md", skillMd(b, null))), license("MIT"));

        SyncReport report = pipeline(List.of(new SyncEntry("fake", "acme/many-" + suffix, "skills",
                SyncEntry.Mode.ALL, List.of(), "")), 2).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "created");
        assertThat(skillCount(a)).isEqualTo(1);
        assertThat(skillCount(b)).isEqualTo(1);
    }

    @Test
    void aReplayedManualDraftKeepsItsProvenanceAndThePublishKeepsTheOrigin() {
        String replay = "replay-" + suffix;
        String locator = "acme/replay-" + suffix;
        /* The declared source differs from the fake binding: without the sync mark, publish
           would let this declaration replace the origin and cascade the sync's snapshot away. */
        String md = skillMdDeclaringSource(replay, "official", "skillmaster/" + replay);
        /* The official account uploads the same content by hand first. The sync's submission then
           replays into that draft instead of creating another one. */
        submit.submit(Zips.ofText(Map.of(replay + "/SKILL.md", md)), "manual", "hand", null,
                AuthenticatedSubject.ofService(OFFICIAL_USER));

        fake.put(locator, "", "a1", List.of(file("SKILL.md", md)), license("MIT"));

        SyncReport report = pipeline(List.of(entry(locator, "")), 1).run();

        /* The submission replayed, which the report calls `unchanged`: the revision is new to the
           sync but the content is already a draft. The origin below proves the write path ran. */
        assertThat(report.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("unchanged"));
        assertThat(draftCount(replay)).isEqualTo(1);
        /* The replayed draft stays a `zip` draft: flipping its source would also make the next
           run's cleanup eligible to discard a draft a person owns. */
        assertThat(versionSource(replay)).isEqualTo("zip");
        assertThat(origin(replay).get("kind")).isEqualTo("fake");

        var promoted = publish.publish("skillmaster", replay,
                new VersionPin.RowId(latestVersionId(replay)), "1.0.0",
                AuthenticatedSubject.ofService(OFFICIAL_USER));

        assertThat(promoted).isPresent();
        assertThat(origin(replay).get("kind")).isEqualTo("fake");
        assertThat(snapshotRevision(replay)).isEqualTo("a1");
    }

    @Test
    void aReplayMarksAnExistingSnapshotAsSyncWritten() {
        String name = "marked-" + suffix;
        String locator = "acme/marked-" + suffix;
        fake.put(locator, "", "a1", List.of(file("SKILL.md", skillMd(name, null))), license("MIT"));
        SyncPipeline pipeline = pipeline(List.of(entry(locator, "")), 1);
        pipeline.run();

        // An unmarked row is what a pre-V39 database holds, and what a replay can meet after a
        // refresh: without the mark the next run would download the same commit again forever.
        jdbc.sql("UPDATE skill_source_snapshot SET written_by_sync = FALSE"
                        + " WHERE skill_version_id = :version")
                .param("version", latestVersionId(name))
                .update();
        assertThat(snapshotMarked(latestVersionId(name))).isFalse();

        SyncReport second = pipeline.run();

        assertThat(second.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("unchanged"));
        assertThat(snapshotMarked(latestVersionId(name))).isTrue();
    }

    @Test
    void aManualPublishDoesNotHideANewerCommitFromTheSync() {
        String name = "manual-" + suffix;
        String locator = "acme/manual-" + suffix;
        fake.put(locator, "", "a1", List.of(file("SKILL.md", skillMd(name, null))), license("MIT"));
        pipeline(List.of(entry(locator, "")), 1).run();

        String manualMd = "---\nname: " + name + "\ndescription: a hand written version\n---\n";
        SubmittedSkill manual = submit.submit(
                Zips.ofText(Map.of(name + "/SKILL.md", manualMd)), "manual", "hand", null,
                AuthenticatedSubject.ofService(OFFICIAL_USER));
        publish.publish("skillmaster", name, new VersionPin.RowId(manual.draftId()), "1.0.0",
                AuthenticatedSubject.ofService(OFFICIAL_USER));

        /* The refresh that follows a publish writes upstream HEAD into the published version's
           snapshot. That is not a revision the sync has downloaded, so it must not move the anchor
           past a commit the sync still owes a draft for. */
        String sourceId = text("SELECT src.id FROM skill_source src JOIN skill s ON"
                + " s.id = src.skill_id WHERE s.name = :name AND src.role = 'origin'", name);
        jdbc.sql("UPDATE skill_source_snapshot SET revision = 'b2' WHERE source_id = :source"
                        + " AND skill_version_id = :version")
                .param("source", sourceId)
                .param("version", manual.draftId())
                .update();

        fake.put(locator, "", "b2", List.of(file("SKILL.md", skillMd(name, "MIT"))),
                license("MIT"));
        SyncReport second = pipeline(List.of(entry(locator, "")), 1).run();

        assertThat(second.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("created"));
        /* The new draft's own snapshot, not any snapshot of the skill: the manual version's row
           already says b2, so a skill-wide lookup could not tell the two apart. */
        assertThat(snapshotRevisionOf(latestVersionId(name))).isEqualTo("b2");
    }

    @Test
    void theNestedLicenseDeclarationWinsLikeTheV49Reader() {
        String name = "nested-" + suffix;
        String locator = "acme/nested-" + suffix;
        fake.put(locator, "", "a1", List.of(file("SKILL.md",
                skillMdWithLicenses(name, "GPL-3.0", "MIT"))), null);

        SyncReport report = pipeline(List.of(entry(locator, "")), 1).run();

        assertThat(report.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("created"));
        assertThat(licenseStatus(name)).isEqualTo("declared-only");
        assertThat(licenseSpdx(name)).isEqualTo("MIT");
    }

    @Test
    void aNonTextLicenseDeclarationIsBlockedRatherThanTreatedAsMissing() {
        String name = "list-license-" + suffix;
        String locator = "acme/list-license-" + suffix;
        fake.put(locator, "", "a1", List.of(file("SKILL.md",
                "---\nname: " + name + "\ndescription: " + name + " skill\n"
                        + "license: [GPL-3.0]\n---\n")), null);

        SyncReport report = pipeline(List.of(entry(locator, "")), 1).run();

        assertThat(report.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("blocked");
            assertThat(result.reason()).contains("license_not_allowed");
        });
    }

    @Test
    void aConfiguredLicensePathThatIsNotInTheCommitBlocksTheEntry() {
        String name = "bad-license-" + suffix;
        String locator = "acme/bad-license-" + suffix;
        fake.put(locator, "", "a1", List.of(file("SKILL.md", skillMd(name, null))), license("MIT"));
        fake.licenseMissing.add(locator);

        SyncReport report = pipeline(List.of(new SyncEntry("fake", locator, "", SyncEntry.Mode.ONE,
                List.of(), "legal/LICENSE-MIT")), 1).run();

        assertThat(report.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("blocked");
            assertThat(result.reason()).isEqualTo("license_path_missing");
        });
        assertThat(skillCount(name)).isZero();
    }

    @Test
    void selectAllSkipsAChildWithoutASkillFile() {
        String a = "mixed-" + suffix;
        String locator = "acme/mixed-" + suffix;
        fake.children.put(locator + "|skills", List.of("a", "no-skill"));
        fake.put(locator, "skills/a", "a1",
                List.of(file("skills/a/SKILL.md", skillMd(a, null))), license("MIT"));
        fake.put(locator, "skills/no-skill", "a1",
                List.of(file("skills/no-skill/README.md", "not a skill")), license("MIT"));

        SyncReport report = pipeline(List.of(new SyncEntry("fake", locator, "skills",
                SyncEntry.Mode.ALL, List.of(), "")), 2).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "skipped");
        assertThat(report.entries().get(1).reason()).isEqualTo("missing_skill_md");
        assertThat(skillCount(a)).isEqualTo(1);
    }

    @Test
    void aSelectedNameThatDoesNotExistIsSkipped() {
        String a = "named-" + suffix;
        String locator = "acme/named-" + suffix;
        fake.put(locator, "skills/a", "a1",
                List.of(file("skills/a/SKILL.md", skillMd(a, null))), license("MIT"));

        SyncReport report = pipeline(List.of(new SyncEntry("fake", locator, "skills",
                SyncEntry.Mode.NAMES, List.of("a", "missing"), "")), 1).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "skipped");
        assertThat(report.entries().get(1).reason()).isEqualTo("not_found");
        assertThat(skillCount(a)).isEqualTo(1);
    }
}
