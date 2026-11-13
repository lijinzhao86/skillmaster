package com.skillmasterai.modules.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The attachment allowlist, size classes, filename cleanup and object key (v65). */
class FeedbackAttachmentSafetyTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");

    @Test
    void detectsTheSevenAllowedTypesFromTheirHeaders() {
        assertThat(FeedbackAttachmentSafety.detect(PNG)).contains("image/png");
        assertThat(FeedbackAttachmentSafety.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .contains("image/jpeg");
        assertThat(FeedbackAttachmentSafety.detect("GIF89a".getBytes(StandardCharsets.US_ASCII)))
                .contains("image/gif");
        assertThat(FeedbackAttachmentSafety.detect(webp())).contains("image/webp");
        assertThat(FeedbackAttachmentSafety.detect(iso("isom"))).contains("video/mp4");
        assertThat(FeedbackAttachmentSafety.detect(iso("qt  "))).contains("video/quicktime");
        assertThat(FeedbackAttachmentSafety.detect(webm())).contains("video/webm");
    }

    @Test
    void theExtensionIsNotConsultedAndUnknownHeadersAreRefused() {
        // An SVG named `.png` is the case the header check exists for.
        assertThat(FeedbackAttachmentSafety.detect("<svg xmlns=...".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        // HEIC is an ISO base media file too; its brand is deliberately not in the MP4 allowlist.
        assertThat(FeedbackAttachmentSafety.detect(iso("heic"))).isEmpty();
        assertThat(FeedbackAttachmentSafety.detect("II*\u0000".getBytes(StandardCharsets.ISO_8859_1)))
                .isEmpty();
        assertThat(FeedbackAttachmentSafety.detect("MZ".getBytes(StandardCharsets.US_ASCII))).isEmpty();
    }

    @Test
    void sizeClassesAndExtensionsFollowTheDetectedType() {
        assertThat(FeedbackAttachmentSafety.limitBytes("image/png"))
                .isEqualTo(FeedbackAttachmentSafety.MAX_IMAGE_BYTES);
        assertThat(FeedbackAttachmentSafety.limitBytes("video/mp4"))
                .isEqualTo(FeedbackAttachmentSafety.MAX_VIDEO_BYTES);
        assertThat(FeedbackAttachmentSafety.extension("image/jpeg")).isEqualTo("jpg");
        assertThat(FeedbackAttachmentSafety.extension("video/quicktime")).isEqualTo("mov");
    }

    @Test
    void theObjectKeyCarriesADateAndAnOpaqueIdOnly() {
        String key = FeedbackAttachmentSafety.objectKey(
                "2026-11-13T02:00:00Z", "01JABCDEFGHJKMNPQRSTVWXYZ0", "image/png");
        assertThat(key)
                .isEqualTo("feedback/attachments/2026/11/13/01JABCDEFGHJKMNPQRSTVWXYZ0.png")
                .doesNotContain("receipt")
                .doesNotContain("shot");
    }

    @Test
    void filenameKeepsOnlyADisplayBasename() {
        assertThat(FeedbackAttachmentSafety.filename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FeedbackAttachmentSafety.filename("C:\\Users\\me\\shot.png")).isEqualTo("shot.png");
        assertThat(FeedbackAttachmentSafety.filename("a\u0000b\u0007c.png")).isEqualTo("abc.png");
        assertThat(FeedbackAttachmentSafety.filename("   ")).isEqualTo("attachment");
        assertThat(FeedbackAttachmentSafety.filename(null)).isEqualTo("attachment");
        String longName = "字".repeat(300) + ".png";
        assertThat(FeedbackAttachmentSafety.filename(longName).codePointCount(
                0, FeedbackAttachmentSafety.filename(longName).length()))
                .isEqualTo(FeedbackAttachmentSafety.MAX_FILENAME_CODE_POINTS);
    }

    @Test
    void sha256IsOverTheStoredBytes() throws Exception {
        Path file = Files.createTempFile("feedback-attachment-", ".png");
        try {
            Files.write(file, PNG);
            assertThat(FeedbackAttachmentSafety.sha256(file))
                    .isEqualTo("5e3d382db4dd83d59aa5742793ad6b7903409e865c83bcbc54835049f043bc15");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] webp() {
        byte[] bytes = new byte[16];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[8] = 'W';
        bytes[9] = 'E';
        bytes[10] = 'B';
        bytes[11] = 'P';
        return bytes;
    }

    private static byte[] iso(String brand) {
        byte[] bytes = new byte[24];
        bytes[3] = 24;
        bytes[4] = 'f';
        bytes[5] = 't';
        bytes[6] = 'y';
        bytes[7] = 'p';
        byte[] value = brand.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(value, 0, bytes, 8, Math.min(4, value.length));
        return bytes;
    }

    private static byte[] webm() {
        byte[] bytes = new byte[32];
        bytes[0] = 0x1A;
        bytes[1] = 0x45;
        bytes[2] = (byte) 0xDF;
        bytes[3] = (byte) 0xA3;
        byte[] docType = "webm".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(docType, 0, bytes, 12, docType.length);
        return bytes;
    }
}
