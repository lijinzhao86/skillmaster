package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.modules.canon.PathCanon;
import com.skillmasterai.modules.canon.TextCanon;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.support.Zips;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the door does to a package before it becomes a version (v33 test-plan S8–S12 / A1, A3–A6).
 *
 * <p>At the validator rather than behind HTTP: the refusal codes are what clients branch on, and
 * some of the shapes are flattened by the container before a request is routed.
 */
class UploadCanonicalizationTest {

    private static final String VALID_SKILL_MD = """
            ---
            name: pdf-tools
            description: Extract and merge PDFs
            ---
            # PDF tools
            """;

    private final SkillUploadValidator validator = new SkillUploadValidator(IngestLimits.STANDARD);

    private ValidatedUpload validate(byte[] zip) {
        return validator.validate(zip);
    }

    @Test
    void textIsCanonicalizedAndTheChangeIsReported() {
        ValidatedUpload validated = validate(Zips.ofText(new LinkedHashMap<>(Map.of(
                "SKILL.md", "﻿" + VALID_SKILL_MD.replace("\n", "\r\n"),
                "references/split.md", "a\r\nb\r\n"))));

        assertThat(validated.upload().skillMd().bytes())
                .isEqualTo(VALID_SKILL_MD.getBytes(StandardCharsets.UTF_8));
        assertThat(validated.normalized()).extracting(ValidatedUpload.Normalized::path)
                .containsExactlyInAnyOrder("SKILL.md", "references/split.md");
        assertThat(validated.normalized().stream()
                .filter(entry -> entry.path().equals("SKILL.md")).findFirst().orElseThrow().changes())
                .contains(TextCanon.BOM_REMOVED, TextCanon.NEWLINE_LF);
    }

    @Test
    void theSameTextInTwoLineEndingsBecomesOneSetOfBytes() {
        ValidatedUpload crlf = validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD.replace("\n", "\r\n"))));
        ValidatedUpload lf = validate(Zips.ofText(Map.of("SKILL.md", VALID_SKILL_MD)));

        assertThat(crlf.upload().skillMd().bytes()).isEqualTo(lf.upload().skillMd().bytes());
    }

    @Test
    void theSameTextInTwoLineEndingsMakesOneDigest() {
        // A2 without a database: the digest is computed from the manifest, so equal bytes and equal
        // paths are equal digests — which is the fact the version's "one text, one version" promise
        // rests on.
        String fromWindows = digestOf(validate(Zips.ofText(Map.of(
                "SKILL.md", "﻿" + VALID_SKILL_MD.replace("\n", "\r\n"),
                "references/checklist.md", "a\r\nb"))).upload());
        String fromUnix = digestOf(validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                "references/checklist.md", "a\nb"))).upload());

        assertThat(fromWindows).isEqualTo(fromUnix);
    }

    private static String digestOf(SkillUpload upload) {
        java.util.List<ManifestEntry> entries = upload.files().stream()
                .map(file -> new ManifestEntry(file.relpath(),
                        com.skillmasterai.common.Sha256Hex.of(file.bytes()), file.size(),
                        file.isBinary()))
                .toList();
        return Manifest.of(entries).digest();
    }

    @Test
    void archiveJunkIsDroppedAndReported() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("__MACOSX/._SKILL.md", "junk");
        entries.put(".DS_Store", "junk");
        entries.put("notes/.DS_Store", "junk");
        entries.put("~$draft.docx", "junk");
        entries.put(".git/config", "junk");
        entries.put("SKILL.md", VALID_SKILL_MD);

        ValidatedUpload validated = validate(Zips.ofText(entries));

        assertThat(validated.upload().files()).extracting(IngestedFile::relpath)
                .containsExactly("SKILL.md");
        assertThat(validated.dropped()).containsExactlyInAnyOrder(
                "__MACOSX/._SKILL.md", ".DS_Store", "notes/.DS_Store", "~$draft.docx",
                ".git/config");
    }

    @Test
    void junkDoesNotHideTheSingleRootDirectory() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("__MACOSX/._pdf-tools", "junk");
        entries.put("pdf-tools/SKILL.md", VALID_SKILL_MD);
        entries.put("pdf-tools/references/split.md", "x");

        ValidatedUpload validated = validate(Zips.ofText(entries));

        assertThat(validated.upload().files()).extracting(IngestedFile::relpath)
                .containsExactlyInAnyOrder("SKILL.md", "references/split.md");
        assertThat(validated.dropped()).containsExactly("__MACOSX/._pdf-tools");

        // The root-name rule still fires once the junk is out of the way: the stripped directory
        // is compared with the frontmatter's name.
        assertThatThrownBy(() -> validate(Zips.ofText(new LinkedHashMap<>(Map.of(
                "__MACOSX/._other", "junk",
                "other-skill/SKILL.md", VALID_SKILL_MD)))))
                .isInstanceOf(IngestException.class)
                .satisfies(thrown -> assertThat(((IngestException) thrown).details())
                        .anySatisfy(detail -> assertThat(detail.issue())
                                .isEqualTo("name_does_not_match_directory")));
    }

    @Test
    void aNameWindowsCannotRestoreIsRefusedWithItsPath() {
        assertThatThrownBy(() -> validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                "报告:v2.md", "x"))))
                .isInstanceOf(IngestException.class)
                .satisfies(thrown -> assertThat(((IngestException) thrown).details())
                        .anySatisfy(detail -> {
                            assertThat(detail.field()).isEqualTo("报告:v2.md");
                            assertThat(detail.issue()).isEqualTo(PathCanon.WINDOWS_INVALID_CHAR);
                        }));
    }

    @Test
    void aReservedDeviceNameIsRefused() {
        assertThatThrownBy(() -> validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                "CON.md", "x"))))
                .isInstanceOf(IngestException.class)
                .satisfies(thrown -> assertThat(((IngestException) thrown).details())
                        .anySatisfy(detail -> assertThat(detail.issue())
                                .isEqualTo(PathCanon.WINDOWS_RESERVED_NAME)));
    }

    @Test
    void twoNamesThatOneFilesystemMergesAreBothNamed() {
        assertThatThrownBy(() -> validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                "A.md", "a",
                "a.md", "b"))))
                .isInstanceOf(IngestException.class)
                .satisfies(thrown -> {
                    var details = ((IngestException) thrown).details();
                    assertThat(details).filteredOn(detail -> detail.issue()
                            .equals(PathCanon.CASE_COLLISION))
                            .extracting(com.skillmasterai.common.ApiError.Detail::field)
                            .containsExactlyInAnyOrder("A.md", "a.md");
                });
    }

    @Test
    void twoSpellingsOfOneNameAreACollisionAndNotTwoFiles() {
        String nfc = Normalizer.normalize("café.md", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("café.md", Normalizer.Form.NFD);

        assertThatThrownBy(() -> validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                nfc, "a",
                nfd, "b"))))
                .isInstanceOf(IngestException.class)
                .satisfies(thrown -> assertThat(((IngestException) thrown).details())
                        .filteredOn(detail -> detail.issue()
                                .equals(PathCanon.UNICODE_COLLISION))
                        .hasSize(2));
    }

    @Test
    void aNameThatCouldNotBeDecodedHasNoPathToPointAt() {
        assertThatThrownBy(() -> validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                "�.md", "x"))))
                .isInstanceOf(IngestException.class)
                .satisfies(thrown -> assertThat(((IngestException) thrown).details())
                        .anySatisfy(detail -> {
                            assertThat(detail.issue()).isEqualTo("invalid_zip_name");
                            assertThat(detail.field()).isEmpty();
                        }));
    }
}
