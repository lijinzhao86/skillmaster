package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.support.Zips;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillUploadValidatorTest {

    private static final String VALID_SKILL_MD = """
            ---
            name: pdf-tools
            title: PDF tools
            description: Extract and merge PDFs
            ---
            # PDF tools
            """;

    private final SkillUploadValidator validator = new SkillUploadValidator(IngestLimits.STANDARD);

    @Test
    void acceptsATreeWrappedInOneDirectoryNamedAfterTheSkill() {
        // `zip -r x.zip pdf-tools/` is the natural way to make an archive, so it is accepted and
        // the wrapper is stripped. §1.3's MUST — directory name equals name — is checked here,
        // because the zip's directory structure is not stored anywhere afterwards.
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "pdf-tools/SKILL.md", VALID_SKILL_MD,
                "pdf-tools/references/checklist.md", "checklist")));

        assertThat(upload.name()).isEqualTo("pdf-tools");
        assertThat(upload.title()).isEqualTo("PDF tools");
        assertThat(upload.description()).isEqualTo("Extract and merge PDFs");
        assertThat(upload.files()).extracting(IngestedFile::relpath)
                .containsExactlyInAnyOrder("SKILL.md", "references/checklist.md");
    }

    @Test
    void acceptsATreeAtTheArchiveRoot() {
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", VALID_SKILL_MD,
                "references/checklist.md", "checklist")));

        assertThat(upload.files()).extracting(IngestedFile::relpath)
                .containsExactlyInAnyOrder("SKILL.md", "references/checklist.md");
    }

    @Test
    void includesSkillMdAmongTheFiles() {
        // §1.3 requires the manifest to list SKILL.md alongside the attachments; it is not a
        // special case that lives outside the file set.
        SkillUpload upload = validator.validate(Zips.ofText(Map.of("SKILL.md", VALID_SKILL_MD)));

        assertThat(upload.files()).extracting(IngestedFile::relpath).containsExactly("SKILL.md");
        assertThat(upload.skillMd().bytes()).isEqualTo(VALID_SKILL_MD.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fallsBackToTheNameWhenThereIsNoTitle() {
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n")));

        assertThat(upload.title()).isEqualTo("pdf-tools");
    }

    @Test
    void fallsBackToTheNameWhenTheTitleIsPresentButBlank() {
        // Present-but-empty is not the same as absent, and §4.2 puts the title on every card — so a
        // literal `title: ""` has to fall back rather than publish a blank display line.
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ntitle: \"\"\ndescription: d\n---\n")));

        assertThat(upload.title()).isEqualTo("pdf-tools");
    }

    @Test
    void acceptsAFrontmatterFieldLeftBlank() {
        // `license:` with nothing after it is ordinary YAML and parses to a null value. Copying the
        // frontmatter with Map.copyOf — which rejects null values — turned that into an uncaught
        // NullPointerException, so publishing an otherwise valid skill was a 500.
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\nlicense:\n---\n")));

        assertThat(upload.frontmatter()).containsEntry("license", null);
    }

    @Test
    void ignoresDirectoryEntries() {
        SkillUpload upload = validator.validate(Zips.withDirectoryEntry("references",
                Map.of("SKILL.md", validSkillMd().getBytes(StandardCharsets.UTF_8))));

        assertThat(upload.files()).extracting(IngestedFile::relpath).containsExactly("SKILL.md");
    }

    @Test
    void rejectsADirectoryNameThatDisagreesWithTheFrontmatter() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "something-else/SKILL.md", VALID_SKILL_MD))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("something-else")
                .hasMessageContaining("pdf-tools");
    }

    @Test
    void rejectsASymbolicLink() {
        // A symlink entry holds its target as content. Publishing it would store bytes the author
        // never uploaded, and the digest would then describe a file they cannot see.
        assertThatThrownBy(() -> validator.validate(Zips.withSymlink("SKILL.md", "/etc/passwd")))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsPathsThatEscapeTheSkillRoot() {
        // Built with ofRawPaths: these three names are exactly the ones a normal archive writer
        // rewrites on the way out, so a helper that sanitises would leave nothing to reject.
        assertThatThrownBy(() -> validator.validate(raw(Map.of(
                "../evil.md", "x", "SKILL.md", validSkillMd()))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("escapes the skill root");

        assertThatThrownBy(() -> validator.validate(raw(Map.of(
                "/etc/passwd", "x", "SKILL.md", validSkillMd()))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("absolute path");

        // The fourth shape ZipReader guards against — a backslash — has no test here on purpose.
        // commons-compress normalises '\' to '/' on read, so a backslash name cannot reach the
        // validator through it; ZipsTest.aBackslashInTheBytesIsNormalisedOnTheWayBackOut pins that
        // behaviour. Asserting a rejection would mean asserting against a fixture the reader has
        // already rewritten.
    }

    private static byte[] raw(Map<String, String> entries) {
        Map<String, byte[]> asBytes = new LinkedHashMap<>();
        entries.forEach((path, text) -> asBytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        return Zips.ofRawPaths(asBytes);
    }

    @Test
    void rejectsAnUploadWithNoSkillMd() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of("README.md", "hi"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void rejectsFrontmatterWithoutTheFieldsSearchDependsOn() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\ndescription: d\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("name");

        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\n---\n"))))
                .as("an empty description is a skill nobody can find")
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("description");
    }

    @Test
    void rejectsANameThatCouldNotBeADirectory() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf tools\ndescription: d\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsANameHoldingTheVersionSeparator() {
        // §4.1 spells a version as a suffix — `ns/name@3` — so a name containing '@' would make the
        // address undecidable: it could be the third version of `pdf-tools`, or a skill whose name
        // is literally `pdf-tools@3`. Reserving the character is what keeps splitting at the first
        // '@' unambiguous.
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools@3\ndescription: d\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("'@'")
                .hasMessageContaining("version");
    }

    @Test
    void rejectsANameThatNoRequestPathCanCarry() {
        // '%' encodes to %25 and ';' is refused outright, both by StrictHttpFirewall on the request
        // URI and again on the decoded path. A name holding either is publishable and searchable but
        // has no address that reaches it — and the delete endpoint is spelled the same way, so the
        // skill can never be removed either.
        for (String name : List.of("100%-done", "a;b")) {
            assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                    "SKILL.md", "---\nname: " + name + "\ndescription: d\n---\n"))))
                    .as("name %s", name)
                    .isInstanceOf(IngestException.class)
                    .hasMessageContaining("request path");
        }
    }

    @Test
    void rejectsAnUploadWithTwoEntriesAtTheSamePath() {
        // The zip format permits a repeated name and unzip only warns, but a manifest is keyed by
        // relpath: the second entry collides on PRIMARY KEY (version_id, relpath) and surfaces as a
        // 500 from the insert, after the blobs have already been written.
        byte[] zip = Zips.patchedNames(
                Zips.ofText(new LinkedHashMap<>(Map.of("SKILL.md", validSkillMd(),
                        "a.md", "first", "b.md", "second"))),
                "b.md", "a.md");

        assertThatThrownBy(() -> validator.validate(zip))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("same path");
    }

    @Test
    void rejectsFrontmatterThatPointsAtItself() {
        // A YAML alias may name its own ancestor, and SnakeYAML builds that object graph without
        // complaint. Nothing here recurses, so it used to reach Jackson — which refuses a structure
        // with no bottom and threw, answering 500 for an upload that is plainly bad, on the one
        // endpoint a client expects a 400 from.
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: cyclic\ndescription: d\na: &x\n  self: *x\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("refers to an ancestor");
    }

    @Test
    void rejectsAFieldOfTheWrongShapeRatherThanStringifyingIt() {
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription:\n  nested: value\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("must be text");
    }

    @Test
    void acceptsAVersionThatIsASemver() {
        // The version is a publisher extension, not a field of the Agent Skills standard, and when it
        // is present it is the author's declared name for this version (ADR 0033).
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\nversion: \"1.2.3\"\n---\n")));

        assertThat(upload.version()).isEqualTo("1.2.3");
    }

    @Test
    void leavesTheVersionNullWhenNoneIsDeclared() {
        // Declaring none is ordinary rather than a defect: the version is then addressable only by
        // its digest, which is the same bargain the host's own plugin loader makes (ADR 0033).
        SkillUpload upload = validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n")));

        assertThat(upload.version())
                .as("no name, and that is a valid submission")
                .isNull();
    }

    @Test
    void rejectsAVersionThatIsNotASemver() {
        // The same judgement the host's plugin loader applies — 1.0, v1.0.0 and latest are refused
        // there too — so that a version name that works in one place works in the other (ADR 0033).
        // Each of these is a near miss a lenient rule would accept and then treat as a name.
        for (String bad : List.of("1.0", "1", "v1.0.0", "01.2.3", "1.02.3", "latest", "1.0.0.0")) {
            assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                    "SKILL.md", "---\nname: pdf-tools\ndescription: d\nversion: \"" + bad
                            + "\"\n---\n"))))
                    .as("version '%s'", bad)
                    .isInstanceOf(IngestException.class)
                    .hasMessageContaining("semantic version");
        }
    }

    @Test
    void rejectsAnUnquotedVersionThatYamlReadsAsANumber() {
        // `version: 1.0` is YAML for a float, so the mistake is a missing quote rather than a typo —
        // and the message says how to fix it, which is why it is its own issue rather than the
        // generic field_not_text complaint. The refusal is still correct: the name must be text.
        assertThatThrownBy(() -> validator.validate(Zips.ofText(Map.of(
                "SKILL.md", "---\nname: pdf-tools\ndescription: d\nversion: 1.0\n---\n"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("quoted semver");
    }

    @Test
    void enforcesTheFileCountCeiling() {
        SkillUploadValidator tiny = new SkillUploadValidator(new IngestLimits(2, 1_000_000));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", validSkillMd());
        files.put("a.md", "a");
        files.put("b.md", "b");

        assertThatThrownBy(() -> tiny.validate(Zips.ofText(files)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("more than 2 files");
    }

    @Test
    void enforcesTheByteCeilingOnWhatIsActuallyDeliveredNotOnWhatIsDeclared() {
        // The declared sizes in a zip header can be lies; the reader counts bytes as it reads.
        //
        // The fixture has to be one. With honest headers the declared total is checked first and
        // this test never reaches the counting bound its name is about: delete that bound and the
        // suite still passed. Declaring zero is the extreme of the lie, and the deflate stream
        // still delivers its 500 bytes.
        SkillUploadValidator tiny = new SkillUploadValidator(new IngestLimits(512, 100));
        byte[] lyingAboutItsSize = Zips.withDeclaredSize(
                Zips.ofText(Map.of("SKILL.md", validSkillMd(), "big.md", "x".repeat(500))),
                "big.md", 0);

        assertThatThrownBy(() -> tiny.validate(lyingAboutItsSize))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("exceeds 100 bytes");
    }

    @Test
    void rejectsSomethingThatIsNotAZip() {
        assertThatThrownBy(() -> validator.validate("not a zip at all".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("not a readable zip");
    }

    @Test
    void rejectsAnEmptyArchive() {
        assertThatThrownBy(() -> validator.validate(Zips.of(Map.of())))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("no files");
    }

    private static String validSkillMd() {
        return VALID_SKILL_MD;
    }
}
