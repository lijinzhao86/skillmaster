package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.skill.internal.GitHubTar;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

/** The tarball boundary: what the sync keeps, and what stops the whole entry. */
class GitHubTarTest {

    private static final GitHubTar.Limits LIMITS =
            new GitHubTar.Limits(20, 1024, 4096, 1_000_000, 100_000);

    @Test
    void keepsOnlyTheConfiguredDirectoryAndTheNearestLicense() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            file(tar, "repo-abc/skills/ab/run.py", "print(1)");
            file(tar, "repo-abc/skills/other/SKILL.md", "# other");
            file(tar, "repo-abc/LICENSE", "MIT License");
        });

        GitHubTar.Result result = GitHubTar.extract(new ByteArrayInputStream(archive),
                "skills/ab", LIMITS);

        assertThat(result.error()).isNull();
        assertThat(result.files()).extracting(GitHubTar.Entry::path)
                .containsExactlyInAnyOrder("skills/ab/SKILL.md", "skills/ab/run.py");
        assertThat(result.licenses()).extracting(GitHubTar.Entry::path).containsExactly("LICENSE");
    }

    @Test
    void aPathThatEscapesTheArchiveStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            file(tar, "repo-abc/../evil", "bad");
        });

        GitHubTar.Result result = GitHubTar.extract(new ByteArrayInputStream(archive),
                "skills/ab", LIMITS);

        assertThat(result.error()).isEqualTo("unsafe_archive");
        assertThat(result.files()).isEmpty();
    }

    @Test
    void anAbsolutePathStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc//etc/passwd", "bad");
        });

        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "", LIMITS).error())
                .isEqualTo("unsafe_archive");
    }

    @Test
    void aSymbolicLinkStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            link(tar, "repo-abc/skills/ab/link", "/etc/passwd");
        });

        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", LIMITS).error())
                .isEqualTo("unsafe_archive");
    }

    @Test
    void aHardLinkInsideTheSkillDirectoryStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            hardLink(tar, "repo-abc/skills/ab/alias", "../../outside");
        });

        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", LIMITS).error())
                .isEqualTo("unsafe_archive");
    }

    @Test
    void aLinkOutsideTheSkillDirectoryIsIgnoredRatherThanBlockingTheEntry() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            link(tar, "repo-abc/CLAUDE.md", "AGENTS.md");
        });

        GitHubTar.Result result = GitHubTar.extract(new ByteArrayInputStream(archive),
                "skills/ab", LIMITS);

        assertThat(result.error()).isNull();
        assertThat(result.files()).extracting(GitHubTar.Entry::path)
                .containsExactly("skills/ab/SKILL.md");
    }

    @Test
    void aFileOverThePerFileCeilingStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> file(tar, "repo-abc/skills/ab/big.bin",
                "x".repeat(1025)));

        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", LIMITS).error())
                .isEqualTo("too_large");
    }

    @Test
    void tooManyFilesStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> {
            for (int i = 0; i < 21; i++) {
                file(tar, "repo-abc/skills/ab/f" + i + ".txt", "x");
            }
        });

        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", LIMITS).error())
                .isEqualTo("too_large");
    }

    @Test
    void theCompressedCeilingIsTooLargeRatherThanANetworkFailure() throws IOException {
        byte[] archive = archive(tar -> file(tar, "repo-abc/skills/ab/SKILL.md", "# ab"));

        GitHubTar.Limits tiny = new GitHubTar.Limits(20, 1024, 4096, 10, 100_000);
        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", tiny).error())
                .isEqualTo("too_large");
    }

    @Test
    void anEntryCeilingStopsAnArchiveOfEmptyEntries() throws IOException {
        byte[] archive = archive(tar -> {
            for (int i = 0; i < 1001; i++) {
                file(tar, "repo-abc/f" + i, "");
            }
        });

        /* Asking for a directory none of the entries is under keeps max-files out of the way: the
           count only moves for wanted files, so 1001 unwanted entries reach the entry ceiling
           itself. The unpacked ceiling also has to stay clear of 1001 headers (1001 × 512 B). */
        GitHubTar.Limits roomy = new GitHubTar.Limits(20, 1024, 4096, 1_000_000, 10_000_000);
        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", roomy).error())
                .isEqualTo("too_large");
    }

    @Test
    void anExplicitLicenseOutsideTheAncestryIsKept() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            file(tar, "repo-abc/legal/LICENSE-MIT", "MIT License");
        });

        GitHubTar.Result result = GitHubTar.extract(new ByteArrayInputStream(archive),
                "skills/ab", LIMITS, "legal/LICENSE-MIT");

        assertThat(result.error()).isNull();
        assertThat(result.licenses()).extracting(GitHubTar.Entry::path)
                .containsExactly("legal/LICENSE-MIT");
    }

    @Test
    void aSymbolicLinkAtTheExplicitLicensePathStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            link(tar, "repo-abc/legal/LICENSE-MIT", "/etc/passwd");
        });

        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", LIMITS,
                "legal/LICENSE-MIT").error()).isEqualTo("unsafe_archive");
    }

    @Test
    void aSiblingLicenseIsNotKept() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            file(tar, "repo-abc/skills/other/LICENSE", "MIT License");
        });

        GitHubTar.Result result = GitHubTar.extract(new ByteArrayInputStream(archive),
                "skills/ab", LIMITS);

        assertThat(result.error()).isNull();
        assertThat(result.licenses()).isEmpty();
    }

    @Test
    void aTopLevelDotDotIsRefused() throws IOException {
        byte[] archive = archive(tar -> file(tar, "../evil", "bad"));

        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "", LIMITS).error())
                .isEqualTo("unsafe_archive");
    }

    @Test
    void theExecutableBitIsKept() throws IOException {
        byte[] archive = archive(tar -> {
            file(tar, "repo-abc/skills/ab/SKILL.md", "# ab");
            executable(tar, "repo-abc/skills/ab/install.sh", "#!/bin/sh\n");
        });

        GitHubTar.Result result = GitHubTar.extract(new ByteArrayInputStream(archive),
                "skills/ab", LIMITS);

        assertThat(result.files()).filteredOn(file -> file.path().endsWith("install.sh"))
                .singleElement()
                .satisfies(file -> assertThat(file.executable()).isTrue());
    }

    @Test
    void theDecompressedCeilingStopsTheWholeEntry() throws IOException {
        byte[] archive = archive(tar -> {
            for (int i = 0; i < 20; i++) {
                file(tar, "repo-abc/skills/ab/f" + i + ".txt", "x".repeat(1000));
            }
        });

        GitHubTar.Limits small = new GitHubTar.Limits(100, 2000, 100_000, 1_000_000, 5_000);
        assertThat(GitHubTar.extract(new ByteArrayInputStream(archive), "skills/ab", small).error())
                .isEqualTo("too_large");
    }

    private interface Body {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    private static byte[] archive(Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            body.write(tar);
        }
        return bytes.toByteArray();
    }

    private static void file(TarArchiveOutputStream tar, String name, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    private static void executable(TarArchiveOutputStream tar, String name, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setMode(0755);
        entry.setSize(bytes.length);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    private static void link(TarArchiveOutputStream tar, String name, String target)
            throws IOException {
        link(tar, name, target, org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
    }

    private static void hardLink(TarArchiveOutputStream tar, String name, String target)
            throws IOException {
        link(tar, name, target, org.apache.commons.compress.archivers.tar.TarConstants.LF_LINK);
    }

    private static void link(TarArchiveOutputStream tar, String name, String target, byte type)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, type);
        entry.setLinkName(target);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }
}
