package com.skillmasterai.modules.canon;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a name has to survive to be a file anybody can download (v33).
 *
 * <p><strong>These are not the archive's rules and not the write path's — they are both.</strong>
 * {@link com.skillmasterai.modules.ingest.SkillPaths} keeps the structural ones (empty segments,
 * {@code ..}, backslash), because they were already shared by the zip reader and the write path.
 * What lives here is the cross-system half: a name that macOS or Windows cannot restore, or that
 * two of them would restore onto one file.
 *
 * <p><strong>Why refuse rather than warn.</strong> The platform's promise is that whoever downloads
 * a skill gets the same files back. A name that only breaks on the downloader's machine fails that
 * promise after the author has left, and a warning at upload time is seen by the wrong person
 * (v33 PRD §不做 6).
 *
 * <p>All three methods are pure and depend on nothing but the JDK, so the browser's editor can run
 * the same rules locally and the server stays the authority (ADR 0011).
 */
public final class PathCanon {

    public static final String WINDOWS_INVALID_CHAR = "windows_invalid_char";
    public static final String WINDOWS_RESERVED_NAME = "windows_reserved_name";
    public static final String TRAILING_SPACE_OR_DOT = "trailing_space_or_dot";
    public static final String PATH_TOO_LONG = "path_too_long";
    public static final String CASE_COLLISION = "case_collision";
    public static final String UNICODE_COLLISION = "unicode_collision";

    /** Windows refuses these anywhere in a name, not just at the start. */
    private static final Set<Integer> INVALID_CHARS = Set.of(
            (int) '<', (int) '>', (int) ':', (int) '"', (int) '|', (int) '?', (int) '*');

    /**
     * Windows treats these as devices, and does so for {@code CON.md} as much as for {@code CON} —
     * which is why the comparison is on the stem, before the first dot.
     */
    private static final Set<String> RESERVED_NAMES = reservedNames();

    /** A single segment, and the whole path, in UTF-8 bytes. */
    private static final int MAX_SEGMENT_BYTES = 255;
    private static final int MAX_PATH_BYTES = 1024;

    private PathCanon() {
    }

    /**
     * @param field  what the caller should point a person at — the relpath itself
     * @param issue  the stable reason a client branches on and says in words
     */
    public record Issue(String field, String issue) {
    }

    /**
     * Two paths that one filesystem would restore onto one file.
     *
     * <p>In {@link #collisions} the two come in the order they were found; in
     * {@link #collidesWith} {@code a} is the path being written and {@code b} the one already there
     * — which is the order the write route's details are sent in, so a client can render the first.
     */
    public record Collision(String a, String b, String issue) {
    }

    /** Every segment normalized; the placeholder for "nothing to report" is {@code Optional.empty}. */
    public static String normalize(String relpath) {
        String[] segments = relpath.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            segments[index] = Normalizer.normalize(segments[index], Normalizer.Form.NFC);
        }
        return String.join("/", segments);
    }

    public static Optional<Issue> check(String relpath) {
        String[] segments = relpath.split("/", -1);
        for (String segment : segments) {
            Optional<String> problem = segmentIssue(segment);
            if (problem.isPresent()) {
                return Optional.of(new Issue(relpath, problem.get()));
            }
        }
        for (String segment : segments) {
            if (segment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
                return Optional.of(new Issue(relpath, PATH_TOO_LONG));
            }
        }
        if (relpath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            return Optional.of(new Issue(relpath, PATH_TOO_LONG));
        }
        return Optional.empty();
    }

    private static Optional<String> segmentIssue(String segment) {
        for (int index = 0; index < segment.length(); ) {
            int codePoint = segment.codePointAt(index);
            if (codePoint < 0x20 || codePoint == 0x7F) {
                return Optional.of(WINDOWS_INVALID_CHAR);
            }
            if (INVALID_CHARS.contains(codePoint)) {
                return Optional.of(WINDOWS_INVALID_CHAR);
            }
            index += Character.charCount(codePoint);
        }
        if (segment.endsWith(" ") || segment.endsWith(".")) {
            return Optional.of(TRAILING_SPACE_OR_DOT);
        }
        String stem = segment.split("\\.", -1)[0].toUpperCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(stem)) {
            return Optional.of(WINDOWS_RESERVED_NAME);
        }
        return Optional.empty();
    }

    /**
     * Pairs of paths one filesystem would merge.
     *
     * <p>Grouped by {@code lowercase(NFC(path))} — the two differences that vanish on macOS or
     * Windows. Inside a group the paths are paired two at a time: a path named once is enough to
     * make its case visible, and every path is named at most once. **That makes the result linear**
     * — at most half the files, 256 pairs at the 512-file ceiling — which is why there is no cap
     * here: a cap would leave the client unable to say how many problems there were, and the
     * version's promise is that one submission lists all of them (v33 PRD §措辞).
     */
    public static List<Collision> collisions(List<String> paths) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String path : paths) {
            groups.computeIfAbsent(PathCanon.normalize(path).toLowerCase(Locale.ROOT),
                    key -> new ArrayList<>()).add(path);
        }
        List<Collision> collisions = new ArrayList<>();
        for (List<String> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (int index = 0; index + 1 < group.size(); index += 2) {
                String a = group.get(index);
                String b = group.get(index + 1);
                boolean sameAfterNfc = normalize(a).equals(normalize(b));
                collisions.add(new Collision(a, b, sameAfterNfc ? UNICODE_COLLISION : CASE_COLLISION));
            }
        }
        return collisions;
    }

    /**
     * The one collision a path would make against an existing set, if any.
     *
     * <p>Only the target is compared — an unrelated write must not be refused for a collision the
     * version has carried since before this rule existed (the same tolerance
     * {@code DraftFileWrite.requireTree} shows for legacy drafts).
     */
    public static Optional<Collision> collidesWith(String path, List<String> others) {
        String target = normalize(path);
        String lower = target.toLowerCase(Locale.ROOT);
        for (String other : others) {
            if (other.equals(path)) {
                continue;
            }
            String candidate = normalize(other);
            if (!candidate.toLowerCase(Locale.ROOT).equals(lower)) {
                continue;
            }
            return Optional.of(new Collision(path, other,
                    candidate.equals(target) ? UNICODE_COLLISION : CASE_COLLISION));
        }
        return Optional.empty();
    }

    private static Set<String> reservedNames() {
        Set<String> names = new java.util.HashSet<>(List.of("CON", "PRN", "AUX", "NUL"));
        for (int number = 1; number <= 9; number++) {
            names.add("COM" + number);
            names.add("LPT" + number);
        }
        return Set.copyOf(names);
    }
}
