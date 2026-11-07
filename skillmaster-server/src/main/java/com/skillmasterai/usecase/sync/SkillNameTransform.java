package com.skillmasterai.usecase.sync;

import com.skillmasterai.modules.ingest.SkillDocument;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The import-time name rule (ADR 0160): an optional source prefix or an explicit name, and the one
 * field a sync may rewrite.
 *
 * <p><strong>Why only {@code name}.</strong> The address and the content name one skill; when the
 * operator asks for a prefixed address, the delivered {@code SKILL.md} has to say the same name or
 * the validator refuses the zip and the local agent sees a different identifier than the platform.
 * The rest of the file is upstream content and stays byte-for-byte.
 *
 * <p><strong>Why the rewrite is line-based rather than a YAML round trip.</strong> Re-serialising
 * the frontmatter would reorder keys, drop comments and change quoting (and possibly types) in
 * fields the sync has no business touching. This finds the one top-level {@code name} line and
 * replaces its scalar, preserving the surrounding bytes. A root mapping indented as a whole is
 * accepted: its keys share one indent, and anything deeper is a nested key. Forms the line rewrite
 * cannot edit safely — block scalars, anchors, quoted keys, flow mappings, CR-only line endings —
 * raise {@link NotRewritable} rather than being guessed at; the pipeline tries the rewrite before
 * the write transaction and reports such an entry as {@code failed name_not_rewritable}, so a
 * valid document is never described as invalid.
 */
public final class SkillNameTransform {

    public static final int MAX_LENGTH = 64;

    private static final Pattern SHAPE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final String RESERVED = "skillmaster";
    private static final char BOM = '\uFEFF';

    private SkillNameTransform() {
    }

    /**
     * The address the entry asks for before the existing-origin lookup: explicit name, then
     * {@code <prefix>-<upstream>}, then the upstream name unchanged.
     */
    public static String platformName(String upstreamName, SyncEntry.Naming naming) {
        if (!naming.explicitName().isEmpty()) {
            return naming.explicitName();
        }
        if (!naming.prefix().isEmpty()) {
            return naming.prefix() + "-" + upstreamName;
        }
        return upstreamName;
    }

    /** @throws InvalidName with {@code name_too_long} / {@code invalid_name} / {@code name_reserved} */
    public static void requireValid(String name) {
        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            throw new InvalidName("name_too_long");
        }
        if (!SHAPE.matcher(name).matches()) {
            throw new InvalidName("invalid_name");
        }
        if (name.equals(RESERVED) || name.startsWith(RESERVED + "-")) {
            throw new InvalidName("name_reserved");
        }
    }

    /**
     * Validates a configured {@code prefix} or {@code name} on its own at startup. The composed
     * length is checked at run time, when the upstream name is known.
     */
    public static void requireValidComponent(String value) {
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            throw new InvalidName("name_too_long");
        }
        if (!SHAPE.matcher(value).matches()) {
            throw new InvalidName("invalid_name");
        }
        if (value.equals(RESERVED) || value.startsWith(RESERVED + "-")) {
            throw new InvalidName("name_reserved");
        }
    }

    /**
     * Replaces the top-level {@code name} scalar in {@code SKILL.md} and returns the new bytes.
     *
     * <p>The result is parsed again with {@link SkillDocument}; a rewrite that cannot be read back
     * is a bug here, not a document to store.
     */
    public static byte[] rewriteSkillMd(byte[] content, String platformName) {
        String text = decode(content);
        String bom = "";
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            bom = String.valueOf(BOM);
            text = text.substring(1);
        }
        if (!text.contains("\n") && text.contains("\r")) {
            throw new NotRewritable("SKILL.md uses CR-only line endings");
        }
        String[] lines = text.split("\n", -1);
        int end = closingDelimiter(lines);
        if (end < 0) {
            throw new NotRewritable("SKILL.md has no closed frontmatter block");
        }
        int base = baseIndent(lines, end);
        int nameLine = -1;
        int colon = -1;
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#") || indentOf(line) != base) {
                continue;
            }
            String contentLine = line.substring(base);
            if (contentLine.startsWith("{") || contentLine.startsWith("[")) {
                throw new NotRewritable("SKILL.md's frontmatter is a flow mapping or sequence");
            }
            int found = contentLine.indexOf(':');
            if (found > 0 && contentLine.substring(0, found).trim().equals("name")) {
                nameLine = i;
                colon = base + found;
                break;
            }
        }
        if (nameLine < 0) {
            throw new NotRewritable("SKILL.md has no top-level name line");
        }
        lines[nameLine] = replaceValue(lines[nameLine], colon, platformName);
        byte[] rewritten = (bom + String.join("\n", lines)).getBytes(StandardCharsets.UTF_8);
        SkillDocument.Document parsed = SkillDocument.parse(rewritten);
        if (!parsed.name().equals(platformName)) {
            throw new NotRewritable("rewriting SKILL.md did not change its name");
        }
        return rewritten;
    }

    /**
     * The indent the root mapping's keys share: the indent of the first non-blank, non-comment
     * line. A comment may sit at any column, and using the smallest indent would let a column-0
     * comment hide an evenly indented root mapping. Keys nested below the first key sit deeper, so
     * they are never mistaken for {@code name}.
     */
    private static int baseIndent(String[] lines, int end) {
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            return indentOf(line);
        }
        return 0;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))
                && line.charAt(i) != '\r' && line.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int closingDelimiter(String[] lines) {
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return -1;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                return i;
            }
        }
        return -1;
    }

    private static String replaceValue(String line, int colon, String platformName) {
        String after = line.substring(colon + 1);
        int restStart = after.length() - after.stripLeading().length();
        String leading = after.substring(0, restStart);
        String rest = after.substring(restStart);
        if (rest.isEmpty()) {
            throw new NotRewritable("SKILL.md's name has no value");
        }
        char first = rest.charAt(0);
        if (first == '|' || first == '>' || first == '&' || first == '*' || first == '!'
                || first == '{' || first == '[') {
            throw new NotRewritable("SKILL.md's name uses an encoding the sync cannot rewrite");
        }
        if (first == '\'' || first == '"') {
            int close = closingQuote(rest, first);
            String suffix = rest.substring(close + 1);
            return line.substring(0, colon + 1) + leading + first + platformName + first + suffix;
        }
        int comment = commentStart(rest);
        String suffix = "";
        if (comment >= 0) {
            int start = comment;
            while (start > 0 && Character.isWhitespace(rest.charAt(start - 1))) {
                start--;
            }
            suffix = rest.substring(start);
        }
        /* The line's own CRLF ending is part of the bytes around the value; dropping it would turn
           one line of a CRLF file into an LF line. */
        if (line.endsWith("\r") && !suffix.endsWith("\r")) {
            suffix = suffix + "\r";
        }
        return line.substring(0, colon + 1) + leading + platformName + suffix;
    }

    private static int closingQuote(String rest, char quote) {
        for (int i = 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (quote == '"' && c == '\\') {
                i++;
                continue;
            }
            if (c == quote) {
                if (quote == '\'' && i + 1 < rest.length() && rest.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                return i;
            }
        }
        throw new NotRewritable("SKILL.md's name has an unterminated quote");
    }

    private static int commentStart(String rest) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(rest.charAt(i - 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException("SKILL.md is not valid UTF-8", failure);
        }
    }

    /**
     * The document is readable, but its {@code name} cannot be rewritten by this line-based pass.
     *
     * <p>Kept distinct from a parse failure: the content is valid, and telling the operator it is
     * invalid sends them looking for a problem the file does not have.
     */
    public static final class NotRewritable extends IllegalStateException {

        public NotRewritable(String message) {
            super(message);
        }
    }

    /** The reason a name is not addressable; also used by the loader at startup. */
    public static final class InvalidName extends RuntimeException {

        private final String reason;

        public InvalidName(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
