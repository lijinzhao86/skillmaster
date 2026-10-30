package com.skillmasterai.modules.canon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What counts as an archive artefact rather than content (v33 test-plan S11 / A5). */
class JunkTest {

    @Test
    void artefactsOfTheToolsThatMadeTheZipAreJunk() {
        assertThat(Junk.isJunk("__MACOSX/._SKILL.md")).isTrue();
        assertThat(Junk.isJunk(".DS_Store")).isTrue();
        assertThat(Junk.isJunk("notes/.DS_Store")).isTrue();
        assertThat(Junk.isJunk("Thumbs.db")).isTrue();
        assertThat(Junk.isJunk("notes/desktop.ini")).isTrue();
        assertThat(Junk.isJunk("~$draft.docx")).isTrue();
        assertThat(Junk.isJunk(".git/config")).isTrue();
        assertThat(Junk.isJunk("pdf-tools/.git/config")).isTrue();
        assertThat(Junk.isJunk(".svn/entries")).isTrue();
    }

    @Test
    void ordinaryFilesAreContent() {
        assertThat(Junk.isJunk("SKILL.md")).isFalse();
        assertThat(Junk.isJunk("references/split.md")).isFalse();
        assertThat(Junk.isJunk("scripts/compress.sh")).isFalse();
        // A name that merely contains the marker is not the marker.
        assertThat(Junk.isJunk("notes/.DS_Store.md")).isFalse();
    }
}
