package com.skillmasterai.modules.canon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import org.junit.jupiter.api.Test;

/**
 * What the door rewrites, item by item (v33 test-plan S1–S5 / A1).
 *
 * <p>Byte equality is the assertion, not decoded equality: the whole point is that two systems'
 * copies of one text produce the same bytes, so a test that compares strings would pass on a build
 * that left CRLF in place.
 */
class TextCanonTest {

    private static final byte[] LF_TEXT = "第一行\nsecond line\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void everyLineEndingBecomesLf() {
        TextCanon.Result crlf = TextCanon.canonicalize(
                "SKILL.md", "第一行\r\nsecond line\r\n".getBytes(StandardCharsets.UTF_8));
        TextCanon.Result cr = TextCanon.canonicalize(
                "SKILL.md", "第一行\rsecond line\r".getBytes(StandardCharsets.UTF_8));
        TextCanon.Result already = TextCanon.canonicalize("SKILL.md", LF_TEXT);

        assertThat(crlf.bytes()).isEqualTo(LF_TEXT);
        assertThat(cr.bytes()).isEqualTo(LF_TEXT);
        assertThat(already.bytes()).isEqualTo(LF_TEXT);
        assertThat(crlf.changes()).containsExactly(TextCanon.NEWLINE_LF);
        assertThat(cr.changes()).containsExactly(TextCanon.NEWLINE_LF);
        assertThat(already.changes()).isEmpty();
    }

    @Test
    void aBomAndUtf16ArriveAsTheSameBytes() {
        byte[] bom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, LF_TEXT);
        TextCanon.Result withBom = TextCanon.canonicalize("SKILL.md", bom);
        TextCanon.Result utf16le = TextCanon.canonicalize("SKILL.md",
                concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
                        "第一行\nsecond line\n".getBytes(StandardCharsets.UTF_16LE)));
        TextCanon.Result utf16be = TextCanon.canonicalize("SKILL.md",
                concat(new byte[] {(byte) 0xFE, (byte) 0xFF},
                        "第一行\nsecond line\n".getBytes(StandardCharsets.UTF_16BE)));

        assertThat(withBom.bytes()).isEqualTo(LF_TEXT);
        assertThat(withBom.changes()).containsExactly(TextCanon.BOM_REMOVED);
        assertThat(utf16le.bytes()).isEqualTo(LF_TEXT);
        assertThat(utf16le.changes()).containsExactly(TextCanon.UTF16_TO_UTF8);
        assertThat(utf16be.bytes()).isEqualTo(LF_TEXT);
        assertThat(utf16be.changes()).containsExactly(TextCanon.UTF16_TO_UTF8);
    }

    @Test
    void combiningMarksBecomeNfc() {
        String nfd = Normalizer.normalize("café", Normalizer.Form.NFD);
        TextCanon.Result result = TextCanon.canonicalize("notes.md",
                nfd.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(result.bytes(), StandardCharsets.UTF_8))
                .isEqualTo(Normalizer.normalize(nfd, Normalizer.Form.NFC));
        assertThat(result.changes()).containsExactly(TextCanon.NFC);
    }

    @Test
    void binaryAndBatchFilesAreHandedBackUntouched() {
        byte[] binary = new byte[] {0, 1, 2, (byte) 0xFF, (byte) 0xFE, 0x0D, 0x0A};
        assertThat(TextCanon.canonicalize("assets/manual.pdf", binary).bytes()).isEqualTo(binary);
        assertThat(TextCanon.canonicalize("assets/manual.pdf", binary).changes()).isEmpty();

        byte[] crlf = "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8);
        assertThat(TextCanon.canonicalize("scripts/run.bat", crlf).bytes()).isEqualTo(crlf);
        assertThat(TextCanon.canonicalize("scripts/RUN.CMD", crlf).bytes()).isEqualTo(crlf);
        assertThat(TextCanon.canonicalize("scripts/run.bat", crlf).changes()).isEmpty();
    }

    @Test
    void aMissingFinalNewlineStaysMissing() {
        byte[] noFinalNewline = "a\nb".getBytes(StandardCharsets.UTF_8);
        assertThat(TextCanon.canonicalize("notes.md", noFinalNewline).bytes())
                .isEqualTo(noFinalNewline);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
