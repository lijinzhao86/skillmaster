package com.skillmasterai.ops.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.ops.imports.FetchedFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class SubmissionZipTest {

    private static final String SKILL_MD = """
            ---
            name: upstream-name
            description: does a thing
            ---

            body
            """;

    @Test
    void theArchiveIsRootedAtThePlatformName() throws IOException {
        Map<String, String> entries = entries(SubmissionZip.build("platform-name", files()));

        assertThat(entries).containsOnlyKeys("platform-name/SKILL.md", "platform-name/run.sh");
    }

    @Test
    void theOneRewrittenFieldIsTheName() throws IOException {
        Map<String, String> entries = entries(SubmissionZip.build("platform-name", files()));

        assertThat(entries.get("platform-name/SKILL.md"))
                .contains("name: platform-name")
                .contains("description: does a thing")
                .contains("body");
    }

    @Test
    void aMatchingNameIsLeftByteForByte() throws IOException {
        Map<String, String> entries = entries(SubmissionZip.build("upstream-name", files()));

        assertThat(entries.get("upstream-name/SKILL.md")).isEqualTo(SKILL_MD);
    }

    private static List<FetchedFile> files() {
        return List.of(
                new FetchedFile("run.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)),
                new FetchedFile("SKILL.md", SKILL_MD.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> entries(byte[] archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
