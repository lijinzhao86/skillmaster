package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaTypesTest {

    @Test
    void rendersOnlyTheSafeAllowlistInPlace() {
        assertThat(MediaTypes.isInline("assets/design.png")).isTrue();
        assertThat(MediaTypes.isInline("assets/photo.JPG")).isTrue();
        assertThat(MediaTypes.isInline("assets/manual.pdf")).isTrue();
        assertThat(MediaTypes.contentTypeFor("assets/design.png")).isEqualTo("image/png");
        assertThat(MediaTypes.contentTypeFor("assets/manual.pdf")).isEqualTo("application/pdf");
    }

    @Test
    void everythingElseIsAnOpaqueAttachment() {
        // SVG is XML and can carry script; it is a download, not a rendered image (ADR 0131).
        assertThat(MediaTypes.isInline("assets/icon.svg")).isFalse();
        assertThat(MediaTypes.contentTypeFor("assets/icon.svg"))
                .isEqualTo(MediaTypes.OCTET_STREAM);
        assertThat(MediaTypes.contentTypeFor("docs/manual.docx"))
                .isEqualTo(MediaTypes.OCTET_STREAM);
        assertThat(MediaTypes.contentTypeFor("notes")).isEqualTo(MediaTypes.OCTET_STREAM);
    }

    @Test
    void aDotInADirectoryIsNotAnExtension() {
        assertThat(MediaTypes.contentTypeFor("v1.2/notes")).isEqualTo(MediaTypes.OCTET_STREAM);
        assertThat(MediaTypes.isInline("v1.2/notes")).isFalse();
    }

    @Test
    void theDispositionNameIsTheLastSegment() {
        assertThat(MediaTypes.filenameOf("references/设计图.png")).isEqualTo("设计图.png");
        assertThat(MediaTypes.filenameOf("SKILL.md")).isEqualTo("SKILL.md");
    }
}
