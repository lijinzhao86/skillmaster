package com.skillmasterai.modules.feedback;

import com.skillmasterai.common.Sha256Hex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The rules an attachment has to pass before it is stored (v65): the type allowlist, the two size
 * ceilings, the filename cleanup, the object key, and the digest.
 *
 * <p><strong>The type is read from the bytes, never from the name or the part header.</strong> A
 * client that says {@code image/png} about an SVG must be refused; a {@code .png} extension on an
 * executable is not a PNG. The head is 256 bytes, enough for the seven magic values below and for
 * the ISO base media {@code ftyp} brand that separates MP4/MOV from HEIC.
 *
 * <p><strong>Nothing here decodes the media.</strong> A malformed PNG that starts with the PNG
 * signature is stored as submitted: the platform is a mailbox, not an image pipeline, and a decoder
 * would make the server a place an attacker can point a parser at.
 */
public final class FeedbackAttachmentSafety {

    /** The most attachments one feedback document may carry. */
    public static final int MAX_ATTACHMENTS = 5;

    /** A single image's byte ceiling. */
    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** A single video's byte ceiling. */
    public static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;

    /** The sum of all attachments on one feedback document. */
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    /** The longest display filename kept; longer names are truncated on a code-point boundary. */
    public static final int MAX_FILENAME_CODE_POINTS = 200;

    /** What the type refusal may name. Ordered for a stable sentence and a stable error detail. */
    public static final List<String> ALLOWED_TYPES = List.of(
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "video/mp4", "video/quicktime", "video/webm");

    private static final int HEAD_BYTES = 256;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87A = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89A = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EBML = {(byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3};

    /**
     * The ISO base media brands that mean "this is a playable MP4". HEIC/HEIF brands
     * ({@code heic}, {@code heix}, {@code mif1}) are deliberately absent: they are images the
     * product cannot show, and accepting them would make the operator's download bag a guessing
     * game.
     */
    private static final Set<String> MP4_BRANDS = Set.of(
            "isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash");

    private FeedbackAttachmentSafety() {
    }

    /** Whether the bytes begin with a signature this product accepts. */
    public static Optional<String> detect(byte[] head) {
        if (head == null || head.length < 3) {
            return Optional.empty();
        }
        if (startsWith(head, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(head, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, GIF87A) || startsWith(head, GIF89A)) {
            return Optional.of("image/gif");
        }
        if (head.length >= 12 && startsWith(head, RIFF) && ascii(head, 8, 4).equals("WEBP")) {
            return Optional.of("image/webp");
        }
        if (head.length >= 12 && ascii(head, 4, 4).equals("ftyp")) {
            String brand = ascii(head, 8, 4);
            if (brand.equals("qt  ")) {
                return Optional.of("video/quicktime");
            }
            if (MP4_BRANDS.contains(brand)) {
                return Optional.of("video/mp4");
            }
            return Optional.empty();
        }
        if (startsWith(head, EBML) && ascii(head, 0, head.length)
                .toLowerCase(Locale.ROOT).contains("webm")) {
            return Optional.of("video/webm");
        }
        return Optional.empty();
    }

    /**
     * The type of a file on disk, read from its first bytes.
     *
     * @throws IOException when the file cannot be opened or read; the caller answers it as
     *     {@code feedback_attachment_unreadable}, not as a type failure
     */
    public static Optional<String> detect(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return detect(in.readNBytes(HEAD_BYTES));
        }
    }

    /** The per-file ceiling for a detected type. */
    public static long limitBytes(String mediaType) {
        return switch (mediaType) {
            case "image/png", "image/jpeg", "image/gif", "image/webp" -> MAX_IMAGE_BYTES;
            case "video/mp4", "video/quicktime", "video/webm" -> MAX_VIDEO_BYTES;
            default -> throw new IllegalArgumentException("unsupported media type: " + mediaType);
        };
    }

    /** The extension used in the object key; never taken from the original filename. */
    public static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            default -> throw new IllegalArgumentException("unsupported media type: " + mediaType);
        };
    }

    /**
     * The private-bucket address of one attachment.
     *
     * <p>The id is a ULID, so the key is opaque and sortable by submission time; the original
     * filename and the receipt are absent on purpose. A receipt cannot be turned into a bucket
     * address even by somebody who has the receipt.
     */
    public static String objectKey(String at, String id, String mediaType) {
        LocalDate day = Instant.parse(at).atZone(ZoneOffset.UTC).toLocalDate();
        return "feedback/attachments/" + day.getYear() + "/"
                + String.format(Locale.ROOT, "%02d", day.getMonthValue()) + "/"
                + String.format(Locale.ROOT, "%02d", day.getDayOfMonth()) + "/"
                + id + "." + extension(mediaType);
    }

    /**
     * The display name kept for the operator: the basename, without control or format characters,
     * at most {@link #MAX_FILENAME_CODE_POINTS} code points. It is never used as a path or in the
     * key.
     */
    public static String filename(String raw) {
        String base = raw == null ? "" : raw;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        StringBuilder cleaned = new StringBuilder(base.length());
        base.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                // **Format characters (U+202E and friends) are stripped too.** They are invisible in
                // most renderers and can reverse the tail of a name an operator reads in the table.
                .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                .forEach(cleaned::appendCodePoint);
        String name = cleaned.toString().strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "attachment";
        }
        if (name.codePointCount(0, name.length()) > MAX_FILENAME_CODE_POINTS) {
            return name.substring(0, name.offsetByCodePoints(0, MAX_FILENAME_CODE_POINTS));
        }
        return name;
    }

    /**
     * The digest of the stored bytes, computed here rather than accepted from the client.
     *
     * @throws IOException when the file cannot be read
     */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = Sha256Hex.newDigest();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean startsWith(byte[] head, byte[] prefix) {
        if (head.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (head[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] head, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > head.length) {
            return "";
        }
        return new String(head, offset, length, StandardCharsets.US_ASCII);
    }
}
