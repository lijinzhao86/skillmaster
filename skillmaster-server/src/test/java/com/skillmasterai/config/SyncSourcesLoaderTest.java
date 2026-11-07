package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.usecase.sync.SyncEntry;
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

        assertThat(entries).hasSize(5);
        assertThat(entries).allSatisfy(entry -> assertThat(entry.mode())
                .isEqualTo(SyncEntry.Mode.ALL));
        assertThat(entries).extracting(SyncEntry::locator)
                .containsExactly("mattpocock/skills", "mattpocock/skills", "mattpocock/skills",
                        "mattpocock/skills", "obra/superpowers");
        assertThat(entries).extracting(SyncEntry::path)
                .containsExactly("skills/engineering", "skills/productivity", "skills/misc",
                        "skills/in-progress", "skills");
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

    private Path write(String content) throws IOException {
        Path file = directory.resolve("sources.yml");
        Files.writeString(file, content);
        return file;
    }
}
