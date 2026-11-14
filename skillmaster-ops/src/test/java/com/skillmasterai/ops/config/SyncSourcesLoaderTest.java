package com.skillmasterai.ops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.ops.imports.SyncEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/** The whitelist is parsed strictly: a mistake fails startup, not a sync that quietly does nothing. */
class SyncSourcesLoaderTest {

    @TempDir
    Path directory;

    private final SyncSourcesLoader loader =
            new SyncSourcesLoader(new DefaultResourceLoader());

    @Test
    void readsTheFourFormsTheProductDocuments() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/one-skill
                  - kind: github
                    locator: acme/tools
                    path: skills/ab
                  - kind: github
                    locator: acme/many
                    path: skills
                    select: all
                  - kind: github
                    locator: acme/selected
                    path: skills
                    select: [a, b]
                """);

        var entries = loader.load(file.toUri().toString());

        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).mode()).isEqualTo(SyncEntry.Mode.ONE);
        assertThat(entries.get(0).path()).isEmpty();
        assertThat(entries.get(2).mode()).isEqualTo(SyncEntry.Mode.ALL);
        assertThat(entries.get(3).names()).containsExactly("a", "b");
    }

    @Test
    void anUnknownVersionIsRefused() throws IOException {
        Path file = write("version: 2\nsources:\n  - kind: github\n    locator: acme/x\n");

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("version: 1");
    }

    @Test
    void aFractionalVersionIsNotTruncatedToTheKnownOne() throws IOException {
        Path file = write("version: 1.5\nsources:\n  - kind: github\n    locator: acme/x\n");

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("version: 1");
    }

    @Test
    void aConfiguredLicensePathIsCarriedOnTheEntry() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    license: legal/LICENSE-MIT
                """);

        var entries = loader.load(file.toUri().toString());

        assertThat(entries.get(0).licensePath()).isEqualTo("legal/LICENSE-MIT");
    }

    @Test
    void selectedChildrenInheritTheConfiguredLicense() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    path: skills
                    select: [a]
                    license: legal/LICENSE-MIT
                """);

        var entry = loader.load(file.toUri().toString()).get(0);

        assertThat(entry.licensePath()).isEqualTo("legal/LICENSE-MIT");
        assertThat(entry.child("a").licensePath()).isEqualTo("legal/LICENSE-MIT");
    }

    @Test
    void aNonStringLicenseIsRefusedAtStartup() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    license: [legal/LICENSE-MIT]
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("non-string license");
    }

    @Test
    void theWhitelistThisRepositoryDeploysLoads() {
        Path deployed = Files.exists(Path.of("..", "deploy", "sync-sources.yml"))
                ? Path.of("..", "deploy", "sync-sources.yml")
                : Path.of("deploy", "sync-sources.yml");

        var entries = loader.load(deployed.toAbsolutePath().toUri().toString());

        // The list itself is data and changes; what must hold is that it loads through the same
        // parser the server boots with, and that no two entries name one (kind, locator, path) —
        // a repeat would import a directory twice under two different prefixes.
        assertThat(entries).isNotEmpty();
        assertThat(entries).extracting(entry -> entry.kind() + "|" + entry.locator() + "|"
                        + entry.path())
                .doesNotHaveDuplicates();
    }

    @Test
    void aLicensePathThatIsAbsoluteOrRelativeDotIsRefused() throws IOException {
        for (String license : new String[] {"/legal/LICENSE", "./legal/LICENSE"}) {
            Path file = write("""
                    version: 1
                    sources:
                      - kind: github
                        locator: acme/x
                        license: %s
                    """.formatted(license));

            assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                    .hasMessageContaining("license");
        }
    }

    @Test
    void aLicensePathThatEscapesIsRefused() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    license: ../LICENSE
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("license");
    }

    @Test
    void aPathThatEscapesIsRefused() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    path: ../etc
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("invalid path");
    }

    @Test
    void selectAllNeedsAPath() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    select: all
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("has no path");
    }

    @Test
    void aBlankSelectListIsRefused() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    path: skills
                    select: []
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("empty select");
    }

    @Test
    void anUnknownKeyIsRefusedRatherThanIgnored() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    paths: skills/ab
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("unknown key 'paths'");
    }

    @Test
    void aTrailingSlashOnThePathIsRefused() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    path: skills/ab/
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("trailing slash");
    }

    @Test
    void aSelectNameWithDotDotIsRefused() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    path: skills
                    select: [a..b]
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("invalid select name");
    }

    @Test
    void anEmptySourceListIsRefused() throws IOException {
        Path file = write("version: 1\nsources: []\n");

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("lists no sources");
    }

    @Test
    void aPrefixOrAnExplicitNameIsCarriedOnTheEntry() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/many
                    path: skills
                    select: all
                    prefix: mattpocock
                  - kind: github
                    locator: acme/pdf
                    name: curated-pdf
                """);

        var entries = loader.load(file.toUri().toString());

        assertThat(entries.get(0).naming()).isEqualTo(new SyncEntry.Naming("mattpocock", ""));
        assertThat(entries.get(0).child("pdf").naming().prefix()).isEqualTo("mattpocock");
        assertThat(entries.get(1).naming()).isEqualTo(new SyncEntry.Naming("", "curated-pdf"));
    }

    @Test
    void aPrefixAndANameAreMutuallyExclusive() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    prefix: acme
                    name: curated
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("both prefix and name");
    }

    @Test
    void aNameCannotBeCombinedWithSelect() throws IOException {
        Path file = write("""
                version: 1
                sources:
                  - kind: github
                    locator: acme/x
                    path: skills
                    select: all
                    name: curated
                """);

        assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                .hasMessageContaining("name with select");
    }

    @Test
    void anInvalidOrReservedPrefixIsRefusedAtStartup() throws IOException {
        for (String prefix : new String[] {"PDF", "pdf--tools", "pdf_", "skillmaster"}) {
            Path file = write("""
                    version: 1
                    sources:
                      - kind: github
                        locator: acme/x
                        prefix: %s
                    """.formatted(prefix));

            assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                    .hasMessageContaining("invalid prefix");
        }
    }

    @Test
    void aPresentButEmptyPrefixOrNameIsATypoNotNoPrefix() throws IOException {
        for (String key : new String[] {"prefix", "name"}) {
            for (String line : new String[] {key + ": \"\"", key + ":", key + ": \" \""}) {
                Path file = write("""
                        version: 1
                        sources:
                          - kind: github
                            locator: acme/x
                            %s
                        """.formatted(line));

                assertThatThrownBy(() -> loader.load(file.toUri().toString()))
                        .hasMessageContaining("empty " + key);
            }
        }
    }

    private Path write(String content) throws IOException {
        Path file = directory.resolve("sources.yml");
        Files.writeString(file, content);
        return file;
    }
}
