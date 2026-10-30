package com.skillmasterai.modules.canon;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One text file's bytes, made the same on every system (v33).
 *
 * <p><strong>This is the only place bytes are rewritten, and it runs only on the way in.</strong>
 * The read path serves what is stored, byte for byte, so the digest a client computes still
 * describes what it downloaded (ADR 0005); what changes is that the same text typed on Windows and
 * on macOS now arrives as the same bytes, and therefore as the same digest (ADR 0117).
 *
 * <p>Order matters and is fixed here:
 * <ol>
 *   <li>{@code .bat} / {@code .cmd} are handed back untouched. cmd.exe has been known to misread a
 *       batch file whose lines end in a bare LF, so converting one could turn a working script into
 *       a broken one — the failure this whole module exists to prevent.</li>
 *   <li>A UTF-16 BOM means the bytes are text in a form no skill client reads; they are decoded
 *       strictly and re-encoded as UTF-8.</li>
 *   <li>Anything that is not valid UTF-8 is binary. It is returned exactly as received — a decode
 *       probe, never a guess at some other encoding.</li>
 *   <li>Then, for text: the leading BOM goes, CRLF and lone CR become LF, and the text is NFC.</li>
 * </ol>
 *
 * <p>Step 2 before 3 because UTF-16's bytes almost never decode as UTF-8; step 3 before 4 because a
 * file with a BOM followed by garbage is not text, and stripping the BOM from it would be editing
 * bytes we have no business understanding.
 */
public final class TextCanon {

    /** The bytes came from UTF-16 (with a BOM) and are now UTF-8. */
    public static final String UTF16_TO_UTF8 = "utf16_to_utf8";
    /** A leading UTF-8 BOM was removed. */
    public static final String BOM_REMOVED = "bom_removed";
    /** CRLF or lone CR became LF. */
    public static final String NEWLINE_LF = "newline_lf";
    /** The text was normalized to Unicode NFC. */
    public static final String NFC = "nfc";

    private TextCanon() {
    }

    /**
     * @param bytes   the text as it will be stored — the input array itself when nothing changed
     * @param changes the rules that fired, empty when the bytes are already canonical
     */
    public record Result(byte[] bytes, List<String> changes) {

        public Result {
            changes = List.copyOf(changes);
        }

        public boolean changed() {
            return !changes.isEmpty();
        }
    }

    public static Result canonicalize(String relpath, byte[] bytes) {
        if (isBatchFile(relpath)) {
            return new Result(bytes, List.of());
        }
        return canonicalize(bytes);
    }

    static Result canonicalize(byte[] bytes) {
        List<String> changes = new ArrayList<>();

        String text;
        if (startsWith(bytes, 0xFE, 0xFF) || startsWith(bytes, 0xFF, 0xFE)) {
            Charset encoding = bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE
                    ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE;
            String decoded = decodeStrict(bytes, encoding);
            if (decoded == null) {
                return new Result(bytes, List.of());
            }
            text = decoded;
            changes.add(UTF16_TO_UTF8);
        } else {
            String decoded = decodeStrict(bytes, StandardCharsets.UTF_8);
            if (decoded == null) {
                return new Result(bytes, List.of());
            }
            text = decoded;
        }

        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
            // A UTF-16 decode always yields a leading U+FEFF from its BOM; `utf16_to_utf8` already
            // says so, and two codes for one fact would make `changes` harder to read than it makes
            // it precise.
            if (!changes.contains(UTF16_TO_UTF8)) {
                changes.add(BOM_REMOVED);
            }
        }

        String unix = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!unix.equals(text)) {
            changes.add(NEWLINE_LF);
        }

        String normalized = Normalizer.normalize(unix, Normalizer.Form.NFC);
        if (!normalized.equals(unix)) {
            changes.add(NFC);
        }

        if (changes.isEmpty()) {
            return new Result(bytes, List.of());
        }
        return new Result(normalized.getBytes(StandardCharsets.UTF_8), changes);
    }

    private static boolean isBatchFile(String relpath) {
        String lower = relpath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".bat") || lower.endsWith(".cmd");
    }

    /** Strict, so an invalid sequence is a refusal rather than a replacement character. */
    private static String decodeStrict(byte[] bytes, Charset encoding) {
        try {
            CharBuffer decoded = encoding.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, int first, int second) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == first && (bytes[1] & 0xFF) == second;
    }
}
