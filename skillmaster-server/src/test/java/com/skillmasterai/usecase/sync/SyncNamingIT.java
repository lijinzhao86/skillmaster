package com.skillmasterai.usecase.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Import-time naming (ADR 0160): cross-source duplicates, an optional prefix or explicit name,
 * address stability, and the one field a sync may rewrite.
 *
 * <p>The base is {@link SyncPipelineFixture}; the other pipeline tests are in {@link SyncPipelineIT}.
 */
class SyncNamingIT extends SyncPipelineFixture {

    @Test
    void twoSourcesWithTheSameNameNeedAPrefixAndOtherwiseOneIsBlocked() {
        String shared = "tdd-" + suffix;
        String alpha = "alpha-" + suffix;
        String beta = "beta-" + suffix;
        fake.put("acme/" + alpha, "skills/tdd", "a1", List.of(
                file("skills/tdd/SKILL.md", skillMd(shared, null)),
                file("skills/tdd/run.py", "print('alpha')")), license("MIT"));
        fake.put("acme/" + beta, "skills/tdd", "a1", List.of(
                file("skills/tdd/SKILL.md", skillMd(shared, null)),
                file("skills/tdd/run.py", "print('beta')")), license("MIT"));

        SyncReport bare = pipeline(List.of(
                entry("acme/" + alpha, "skills/tdd"),
                entry("acme/" + beta, "skills/tdd")), 1).run();

        assertThat(bare.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "blocked");
        assertThat(bare.entries().get(1).reason()).isEqualTo("origin_conflict");
        assertThat(skillCount(shared)).isEqualTo(1);

        String shared2 = "tdd2-" + suffix;
        String alpha2 = "alpha2-" + suffix;
        String beta2 = "beta2-" + suffix;
        fake.put("acme/" + alpha2, "skills/tdd", "a1", List.of(
                file("skills/tdd/SKILL.md", skillMd(shared2, null)),
                file("skills/tdd/run.py", "print('alpha')")), license("MIT"));
        fake.put("acme/" + beta2, "skills/tdd", "a1", List.of(
                file("skills/tdd/SKILL.md", skillMd(shared2, null)),
                file("skills/tdd/run.py", "print('beta')")), license("MIT"));

        SyncReport prefixed = pipeline(List.of(
                entry("acme/" + alpha2, "skills/tdd"),
                entry("acme/" + beta2, "skills/tdd", new SyncEntry.Naming("beta", ""))), 1).run();

        assertThat(prefixed.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "created");
        assertThat(skillCount(shared2)).isEqualTo(1);
        assertThat(skillCount("beta-" + shared2)).isEqualTo(1);
        /* Byte-for-byte: the name line is the only difference from upstream. */
        assertThat(new String(fileContent("beta-" + shared2, "SKILL.md"), StandardCharsets.UTF_8))
                .isEqualTo("---\nname: beta-" + shared2 + "\ndescription: " + shared2
                        + " skill\n---\n");
        assertThat(new String(fileContent("beta-" + shared2, "run.py"), StandardCharsets.UTF_8))
                .isEqualTo("print('beta')");
        assertThat(changelog("beta-" + shared2)).contains(shared2 + " → beta-" + shared2);
        assertThat(origin("beta-" + shared2)).containsEntry("path", "skills/tdd");
    }

    @Test
    void twoPrefixedSourcesCanBothImportTheSameUpstreamName() {
        String shared = "tdd-" + suffix;
        String alpha = "alpha-" + suffix;
        String beta = "beta-" + suffix;
        fake.put("acme/" + alpha, "skills/tdd", "a1",
                List.of(file("skills/tdd/SKILL.md", skillMd(shared, null))), license("MIT"));
        fake.put("acme/" + beta, "skills/tdd", "a1",
                List.of(file("skills/tdd/SKILL.md", skillMd(shared, null))), license("MIT"));

        SyncReport report = pipeline(List.of(
                entry("acme/" + alpha, "skills/tdd", new SyncEntry.Naming("alpha", "")),
                entry("acme/" + beta, "skills/tdd", new SyncEntry.Naming("beta", ""))), 1).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsExactly("created", "created");
        assertThat(skillCount("alpha-" + shared)).isEqualTo(1);
        assertThat(skillCount("beta-" + shared)).isEqualTo(1);
    }

    @Test
    void anExplicitNameIsUsedAndACollisionBlocks() {
        String shared = "tdd-" + suffix;
        String first = "first-" + suffix;
        String second = "second-" + suffix;
        fake.put("acme/" + first, "", "a1",
                List.of(file("SKILL.md", skillMd(shared, null))), license("MIT"));
        fake.put("acme/" + second, "", "a1",
                List.of(file("SKILL.md", skillMd(shared, null))), license("MIT"));
        String curated = "curated-" + suffix;

        SyncReport firstRun = pipeline(List.of(
                entry("acme/" + first, "", new SyncEntry.Naming("", curated))), 1).run();
        assertThat(firstRun.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("created"));
        assertThat(skillCount(curated)).isEqualTo(1);

        SyncReport secondRun = pipeline(List.of(
                entry("acme/" + second, "", new SyncEntry.Naming("", curated))), 1).run();
        assertThat(secondRun.entries()).singleElement()
                .satisfies(result -> {
                    assertThat(result.result()).isEqualTo("blocked");
                    assertThat(result.reason()).isEqualTo("origin_conflict");
                });
    }

    @Test
    void aComposedNameThatIsTooLongIsBlockedWithoutTruncation() {
        String shared = "tdd-" + suffix;
        String locator = "acme/long-" + suffix;
        fake.put(locator, "", "a1",
                List.of(file("SKILL.md", skillMd(shared, null))), license("MIT"));

        SyncReport report = pipeline(List.of(
                entry(locator, "", new SyncEntry.Naming("a".repeat(60), ""))), 1).run();

        assertThat(report.entries()).singleElement()
                .satisfies(result -> {
                    assertThat(result.result()).isEqualTo("blocked");
                    assertThat(result.reason()).isEqualTo("name_too_long");
                });
        assertThat(skillCount(shared)).isZero();
    }

    @Test
    void aStableAddressSurvivesAnUpstreamNameChangeAndAPrefixChange() {
        String shared = "tdd-" + suffix;
        String renamed = "renamed-" + suffix;
        String locator = "acme/stable-" + suffix;
        String platform = "alpha-" + shared;
        fake.put(locator, "skills/tdd", "a1",
                List.of(file("skills/tdd/SKILL.md", skillMd(shared, null)),
                        file("skills/tdd/run.py", "print(1)")), license("MIT"));

        SyncPipeline pipeline = pipeline(List.of(
                entry(locator, "skills/tdd", new SyncEntry.Naming("alpha", ""))), 1);
        pipeline.run();

        fake.put(locator, "skills/tdd", "b2",
                List.of(file("skills/tdd/SKILL.md", skillMd(renamed, null)),
                        file("skills/tdd/run.py", "print(2)")), license("MIT"));
        SyncReport second = pipeline.run();

        assertThat(second.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("created");
            assertThat(result.warnings()).anyMatch(warning -> warning.startsWith("address_kept:"));
        });
        assertThat(skillCount(platform)).isEqualTo(1);
        assertThat(skillCount("alpha-" + renamed)).isZero();
        assertThat(versionCount(platform)).isEqualTo(2);
        assertThat(new String(fileContent(platform, "SKILL.md"), StandardCharsets.UTF_8))
                .contains("name: " + platform + "\n").doesNotContain("name: " + renamed + "\n");
        /* Everything else follows upstream: the rename touches the name line only. */
        assertThat(new String(fileContent(platform, "run.py"), StandardCharsets.UTF_8))
                .isEqualTo("print(2)");

        fake.put(locator, "skills/tdd", "c3",
                List.of(file("skills/tdd/SKILL.md", skillMd(renamed, null)),
                        file("skills/tdd/run.py", "print(3)")), license("MIT"));
        SyncReport third = pipeline(List.of(
                entry(locator, "skills/tdd", new SyncEntry.Naming("beta", ""))), 1).run();

        assertThat(third.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("created");
            assertThat(result.warnings()).anyMatch(warning -> warning.startsWith("address_kept:"));
        });
        assertThat(skillCount(platform)).isEqualTo(1);
        assertThat(skillCount("beta-" + shared)).isZero();
        assertThat(skillCount("beta-" + renamed)).isZero();
    }

    @Test
    void addingAPrefixAfterABareImportKeepsTheBareAddress() {
        String shared = "tdd-" + suffix;
        String locator = "acme/late-prefix-" + suffix;
        fake.put(locator, "", "a1",
                List.of(file("SKILL.md", skillMd(shared, null))), license("MIT"));

        pipeline(List.of(entry(locator, "")), 1).run();

        fake.put(locator, "", "b2",
                List.of(file("SKILL.md", skillMd(shared, null)),
                        file("run.py", "print(2)")), license("MIT"));
        SyncReport second = pipeline(List.of(
                entry(locator, "", new SyncEntry.Naming("beta", ""))), 1).run();

        assertThat(second.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("created");
            assertThat(result.warnings()).anyMatch(warning -> warning.startsWith(
                    "address_kept:existing=" + shared + " requested=beta-" + shared));
        });
        assertThat(skillCount(shared)).isEqualTo(1);
        assertThat(skillCount("beta-" + shared)).isZero();
        assertThat(new String(fileContent(shared, "SKILL.md"), StandardCharsets.UTF_8))
                .contains("name: " + shared + "\n");
    }

    @Test
    void movingTheDirectoryIsANewEntryAndDoesNotTakeOverTheOldAddress() {
        String shared = "tdd-" + suffix;
        String locator = "acme/moved-" + suffix;
        String platform = "alpha-" + shared;
        SyncEntry.Naming alpha = new SyncEntry.Naming("alpha", "");
        fake.put(locator, "skills/tdd", "a1",
                List.of(file("skills/tdd/SKILL.md", skillMd(shared, null))), license("MIT"));
        pipeline(List.of(entry(locator, "skills/tdd", alpha)), 1).run();

        fake.put(locator, "skills/tdd-v2", "b2",
                List.of(file("skills/tdd-v2/SKILL.md", skillMd(shared, null))), license("MIT"));
        SyncReport moved = pipeline(List.of(entry(locator, "skills/tdd-v2", alpha)), 1).run();

        /* The new path resolves to the same address the old skill already holds, and an address
           belongs to one skill: the move is blocked rather than silently reassigned. */
        assertThat(moved.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("blocked");
            assertThat(result.reason()).isEqualTo("origin_conflict");
        });
        assertThat(skillCount(platform)).isEqualTo(1);
        assertThat(versionCount(platform)).isEqualTo(1);
        assertThat(origin(platform)).containsEntry("path", "skills/tdd");

        String renamed = "gamma-" + shared;
        SyncReport reprefixed = pipeline(List.of(
                entry(locator, "skills/tdd-v2", new SyncEntry.Naming("gamma", ""))), 1).run();

        assertThat(reprefixed.entries()).singleElement()
                .satisfies(result -> assertThat(result.result()).isEqualTo("created"));
        assertThat(skillCount(renamed)).isEqualTo(1);
        assertThat(skillCount(platform)).isEqualTo(1);
        assertThat(versionCount(platform)).isEqualTo(1);
        assertThat(origin(platform)).containsEntry("path", "skills/tdd");
        assertThat(origin(renamed)).containsEntry("path", "skills/tdd-v2");
    }

    @Test
    void aSkillFileWhoseNameLineCannotBeRewrittenFailsAsNotRewritable() {
        String shared = "tdd-" + suffix;
        String locator = "acme/rewrite-" + suffix;
        fake.put(locator, "", "a1", List.of(file("SKILL.md",
                "---\n\"name\": " + shared + "\ndescription: x\n---\n")), license("MIT"));

        SyncReport report = pipeline(List.of(
                entry(locator, "", new SyncEntry.Naming("beta", ""))), 1).run();

        assertThat(report.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("failed");
            assertThat(result.reason()).isEqualTo("name_not_rewritable");
        });
        assertThat(skillCount("beta-" + shared)).isZero();
    }

    @Test
    void aBareSkillRenamedUpstreamKeepsItsAddressAndStillRewritesTheNameLine() {
        String shared = "tdd-" + suffix;
        String renamed = "renamed-" + suffix;
        String locator = "acme/bare-rename-" + suffix;
        fake.put(locator, "", "a1",
                List.of(file("SKILL.md", skillMd(shared, null))), license("MIT"));
        pipeline(List.of(entry(locator, "")), 1).run();

        fake.put(locator, "", "b2",
                List.of(file("SKILL.md", skillMd(renamed, null)),
                        file("run.py", "print(2)")), license("MIT"));
        SyncReport second = pipeline(List.of(entry(locator, "")), 1).run();

        /* No prefix was configured, yet the address is stable: the new draft's name line is
           written back to the assigned address, which is the one case "nothing is rewritten"
           does not cover. */
        assertThat(second.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("created");
            assertThat(result.warnings()).anyMatch(warning -> warning.startsWith("address_kept:"));
        });
        assertThat(skillCount(shared)).isEqualTo(1);
        assertThat(skillCount(renamed)).isZero();
        assertThat(new String(fileContent(shared, "SKILL.md"), StandardCharsets.UTF_8))
                .contains("name: " + shared + "\n").doesNotContain("name: " + renamed + "\n");
    }

    @Test
    void aKeptAddressIsStillReportedWhenTheEntryIsBlocked() {
        String shared = "tdd-" + suffix;
        String locator = "acme/kept-blocked-" + suffix;
        fake.put(locator, "", "a1",
                List.of(file("SKILL.md", skillMd(shared, null))), license("MIT"));
        pipeline(List.of(entry(locator, "", new SyncEntry.Naming("alpha", ""))), 1).run();

        fake.put(locator, "", "b2",
                List.of(file("SKILL.md", skillMd(shared, null))), license("GPL-3.0"));
        SyncReport blocked = pipeline(List.of(
                entry(locator, "", new SyncEntry.Naming("beta", ""))), 1).run();

        /* The address was kept, then the licence gate refused the content: the operator still
           needs to see that the configured prefix did not apply. */
        assertThat(blocked.entries()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("blocked");
            assertThat(result.reason()).contains("license_not_allowed");
            assertThat(result.warnings()).anyMatch(warning -> warning.startsWith(
                    "address_kept:existing=alpha-" + shared + " requested=beta-" + shared));
        });
        assertThat(skillCount("alpha-" + shared)).isEqualTo(1);
        assertThat(skillCount("beta-" + shared)).isZero();
    }

    @Test
    void selectAllChildrenInheritThePrefix() {
        String locator = "acme/whole-" + suffix;
        String prefix = "many" + suffix;
        fake.put(locator, "skills", "a1", List.of(), license("MIT"));
        fake.children.put(locator + "|skills", List.of("a", "b"));
        fake.put(locator, "skills/a", "a1",
                List.of(file("skills/a/SKILL.md", skillMd("a", null))), license("MIT"));
        fake.put(locator, "skills/b", "a1",
                List.of(file("skills/b/SKILL.md", skillMd("b", null))), license("MIT"));

        SyncReport report = pipeline(List.of(new SyncEntry("fake", locator, "skills",
                SyncEntry.Mode.ALL, List.of(), "", new SyncEntry.Naming(prefix, ""))), 1).run();

        assertThat(report.entries()).extracting(SyncEntryResult::result)
                .containsExactlyInAnyOrder("created", "created");
        assertThat(skillCount(prefix + "-a")).isEqualTo(1);
        assertThat(skillCount(prefix + "-b")).isEqualTo(1);
    }
}
