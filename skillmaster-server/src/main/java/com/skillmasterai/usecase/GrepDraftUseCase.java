package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.GrepResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code skill grep}: a scoped scan over one draft (v7 做 #5, ADR 0062).
 *
 * <p><strong>Only matches leave the server.</strong> The response has no file contents and no
 * unmatched files; a client learns which lines matched and nothing about the rest. That is the same
 * boundary {@code cat} sits behind, applied to the one command that would otherwise be tempted to
 * ship a version.
 *
 * <p><strong>Only drafts.</strong> A published version's content is frozen and already
 * addressable; searching it is {@code cat | grep} on the client, where the user's own tools decide
 * the semantics. Drafts are the mutable side and the one an agent is editing, which is why the
 * server offers this at all.
 *
 * <p>The fixed-string case is pre-filtered in the database ({@link BlobStore#containing}); the other
 * two decode and scan in the application, because PostgreSQL's case folding is ASCII-only and a
 * regular expression is not a byte sequence.
 */
@Component
public class GrepDraftUseCase {

    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 5000;
    /**
     * A ceiling on the pattern itself.
     *
     * <p>The engine is linear in the input (ADR 0066), but compiling is still work proportional to
     * the expression, and a caller has no reason to send a kilobyte of regex at a 16 MiB skill. The
     * bound is generous against real searches and keeps the request's own cost bounded.
     */
    private static final int MAX_PATTERN_LENGTH = 1000;
    /**
     * The largest {@code {n}} / {@code {n,m}} a pattern may carry.
     *
     * <p>RE2 is linear in the input but its cost per byte grows with the automaton, and a counted
     * repetition is how a seven-character pattern buys a thousand states: {@code a{999}b} against a
     * 16 MiB line ran for about a minute on this machine. The bound is generous for real searches
     * ({@code \d{4}} for a year, {@code [0-9]{13}} for an identifier) and turns the amplification
     * into an immediate refusal instead of a scan (ADR 0067).
     */
    private static final int MAX_REPETITION = 100;

    /**
     * The largest node count a pattern may expand to at compile time.
     *
     * <p>RE2 expands a counted repetition when it compiles, so the size is the product of the counts
     * times the length of the repeated expression: {@code (((a{100}){100}){100}){100}} is 27
     * characters and a hundred million nodes, and {@code ((a×955){100}){100}} is 969 characters and
     * ten million — either exhausts the heap, and the compile runs before any permission check (ADR
     * 0067). The bound is therefore {@code product × pattern length}, which over-estimates (the
     * length includes text outside the repetition) rather than under-estimates; one million nodes
     * leaves room for ordinary patterns such as {@code \d{4}-\d{2}-\d{2}}.
     */
    private static final long MAX_EXPANSION = 1_000_000;

    /**
     * How long one request may spend matching.
     *
     * <p>Nested repetitions below the limit can still build an automaton large enough to scan for
     * seconds — {@code (a{100}){100}} is the same minute in two counts — and no static bound sees
     * that cheaply. The wrapper handed to RE2 checks the clock as it reads, so the request is
     * refused at the deadline rather than holding a servlet thread and a database connection.
     */
    private static final long MATCH_DEADLINE_NANOS = 1_000_000_000L;

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final BlobStore blobs;
    private final DraftTextCache texts;

    public GrepDraftUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, DraftTextCache texts) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.blobs = blobs;
        this.texts = texts;
    }

    @Transactional(readOnly = true)
    public Optional<GrepResult> grep(String namespaceSlug, String name, String rowId,
            String pattern, boolean fixed, boolean ignoreCase, String pathPrefix, int limit,
            AuthenticatedSubject subject) {
        // **The pattern's shape is checked before the target is resolved.** It depends on nothing
        // about the skill, so a bad request must be the same 400 whether the skill exists, is
        // visible, or is on the other side of a permission check (TD §并发).
        if (pattern == null || pattern.isEmpty()) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "grep needs a pattern");
        }
        if (pattern.codePointCount(0, pattern.length()) > MAX_PATTERN_LENGTH) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "the pattern is longer than " + MAX_PATTERN_LENGTH + " characters",
                    List.of(new ApiError.Detail("pattern", "too_long")));
        }
        Pattern regex = null;
        if (!fixed) {
            refuseExpensiveExpansion(pattern);
            regex = compile(pattern, ignoreCase);
        }
        // A literal is searched with KMP, not with the regex engine: the engine's cost grows with the
        // automaton, so a 1,000-character needle scanned a 16 MiB line for over a minute. The two
        // arrays are built once per request (ADR 0067).
        int[] needle = fixed ? codePoints(pattern, ignoreCase) : null;
        int[] failure = fixed ? failure(needle) : null;

        Optional<Access> resolved = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                        Callers.of(namespaces, subject)));
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        Optional<VersionSummary> row = versions.openDraftForRead(access, rowId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        VersionSummary draft = row.get();
        Manifest manifest = versions.filesOf(draft);

        int cap = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        List<ManifestEntry> candidates = manifest.entries().stream()
                .filter(entry -> !entry.isBinary())
                .filter(entry -> within(entry.relpath(), pathPrefix))
                .toList();

        Set<String> prefetched = null;
        if (fixed && !ignoreCase) {
            // Only the blobs whose bytes contain the needle can contain a matching line. A match
            // that spans a line break makes this a superset, never a subset, so line matching still
            // decides — the query only narrows what is read.
            prefetched = Set.copyOf(blobs.containing(
                    candidates.stream().map(ManifestEntry::blobSha256).collect(java.util.stream.Collectors.toSet()),
                    pattern));
        }

        // **The clock starts at the scan, not at the request.** Resolving the skill and reading the
        // manifest are not matching, and charging them to this budget would turn a slow first read
        // into a false `too_expensive` on the first character.
        MatchBudget budget = new MatchBudget(System.nanoTime() + MATCH_DEADLINE_NANOS);

        List<GrepResult.Hit> hits = new ArrayList<>();
        boolean truncated = false;
        outer:
        for (ManifestEntry entry : candidates) {
            if (prefetched != null && !prefetched.contains(entry.blobSha256())) {
                continue;
            }
            String text = texts.text(entry.blobSha256(),
                    () -> blobs.get(entry.blobSha256()));
            // Walk the lines rather than `split("\n", -1)`: a 16 MiB file of newlines would otherwise
            // materialise millions of one-character strings before the first match is tested.
            if (text.isEmpty()) {
                continue;
            }
            int start = 0;
            int lineNumber = 0;
            int scanned = 0;
            while (true) {
                int end = text.indexOf('\n', start);
                if (end < 0) {
                    end = text.length();
                }
                String line = text.substring(start, end);
                boolean matched;
                try {
                    matched = matches(line, regex, needle, failure, ignoreCase, budget);
                } catch (MatchTooExpensive e) {
                    // The deadline is a property of the whole request, not of this line: once it is
                    // spent, continuing would keep holding the connection this use case was given.
                    throw tooExpensive();
                }
                if (matched) {
                    if (hits.size() >= cap) {
                        truncated = true;
                        break outer;
                    }
                    hits.add(new GrepResult.Hit(entry.relpath(), lineNumber + 1, line));
                }
                lineNumber++;
                // **A file of short lines is the case the per-character check cannot see.** Lines
                // shorter than the check interval never reach it, and a literal search reads no
                // characters through it at all, so the deadline is also checked at a line boundary.
                // Every 64th line is often enough that the overshoot is a fraction of the deadline,
                // and rare enough (a file of empty lines spends a clock call per 64 rows) that the
                // check itself is not the cost.
                if ((++scanned & 0x3F) == 0 && budget.expired()) {
                    throw tooExpensive();
                }
                if (end == text.length()) {
                    break;
                }
                start = end + 1;
                if (start == text.length()) {
                    // The file ended with a newline. That terminator does not start another line —
                    // local grep does not report a phantom empty last line either.
                    break;
                }
            }
        }

        // The manifest read above is the one the hits came from, so the digest is computed from it
        // rather than echoed from the row: under READ COMMITTED a concurrent write between the two
        // statements would otherwise label these lines with the manifest they do not belong to.
        return Optional.of(new GrepResult("sha256:" + manifest.digest(), pinOf(draft), hits,
                truncated));
    }

    private static Pattern compile(String pattern, boolean ignoreCase) {
        try {
            // RE2's case folding is Unicode by default; there is no UNICODE_CASE flag to add
            // (ADR 0066). This engine is what makes a pathological pattern cost a linear scan
            // instead of a servlet thread.
            return Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "the pattern is not a regular expression this server can compile",
                    List.of(new ApiError.Detail("pattern", "bad_pattern")));
        }
    }

    private static boolean matches(String line, Pattern regex, int[] needle, int[] failure,
            boolean ignoreCase, MatchBudget budget) {
        if (regex != null) {
            return regex.matcher(new BoundedText(line, budget)).find();
        }
        return literalContains(line, needle, failure, ignoreCase);
    }

    /**
     * A literal search in linear time, with the case folding {@code -i} promises.
     *
     * <p>KMP rather than a per-start comparison: the naive loop was O(line x needle) and a
     * 1,000-character needle against a 16 MiB line held a connection for seconds, which is the same
     * accident the regex engine was chosen to avoid (ADR 0066, 0067). Case folding is Unicode's
     * simple lowercase mapping, which is the folding the engine applies to a literal (ADR 0067).
     */
    private static boolean literalContains(String line, int[] needle, int[] failure,
            boolean fold) {
        if (needle.length == 0) {
            return true;
        }
        int matched = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            i += Character.charCount(cp);
            if (fold) {
                cp = Character.toLowerCase(cp);
            }
            while (matched > 0 && cp != needle[matched]) {
                matched = failure[matched - 1];
            }
            if (cp == needle[matched]) {
                matched++;
            }
            if (matched == needle.length) {
                return true;
            }
        }
        return false;
    }

    /** The needle as code points, folded when {@code -i} asked for it. */
    private static int[] codePoints(String value, boolean fold) {
        int[] points = new int[value.codePointCount(0, value.length())];
        int at = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            points[at++] = fold ? Character.toLowerCase(cp) : cp;
        }
        return points;
    }

    /** KMP's failure function: where to resume when the next code point differs. */
    private static int[] failure(int[] needle) {
        int[] failure = new int[needle.length];
        for (int i = 1, matched = 0; i < needle.length; i++) {
            while (matched > 0 && needle[i] != needle[matched]) {
                matched = failure[matched - 1];
            }
            if (needle[i] == needle[matched]) {
                matched++;
            }
            failure[i] = matched;
        }
        return failure;
    }

    /**
     * Refuses a pattern whose counted repetitions expand too far.
     *
     * <p><strong>The bound is the product, not each count.</strong> RE2 expands {@code {n}} when it
     * compiles, so nesting multiplies: every count in {@code (((a{100}){100}){100}){100}} is legal on
     * its own and the whole is a hundred million nodes — an {@link OutOfMemoryError} before any
     * permission check has run. Multiplying every counted repetition is an upper bound on that expansion (it
     * multiplies counts that sit side by side, which the automaton does not, so it can only reject
     * more than needed), and both the per-count and the cumulative ceiling are checked here, before
     * the compile (ADR 0067).
     *
     * <p>Scanned rather than parsed: escapes, character classes and quoted text are skipped so that
     * {@code \{}, {@code [{]} and {@code \Q{999}\E} stay literals, and a brace that is not a counted
     * repetition is left to the compiler.
     */
    private static void refuseExpensiveExpansion(String pattern) {
        boolean inClass = false;
        long expansion = 1;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i = skipEscape(pattern, i);
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            if (c == '[') {
                inClass = true;
                continue;
            }
            if (c != '{') {
                continue;
            }
            int at = i + 1;
            int least = 0;
            boolean counted = false;
            while (at < pattern.length() && isAsciiDigit(pattern.charAt(at))) {
                least = Math.min(least * 10 + (pattern.charAt(at) - '0'), MAX_REPETITION + 1);
                at++;
                counted = true;
            }
            if (!counted) {
                continue;
            }
            int most = least;
            if (at < pattern.length() && pattern.charAt(at) == ',') {
                at++;
                boolean bounded = false;
                int value = 0;
                while (at < pattern.length() && isAsciiDigit(pattern.charAt(at))) {
                    value = Math.min(value * 10 + (pattern.charAt(at) - '0'), MAX_REPETITION + 1);
                    at++;
                    bounded = true;
                }
                if (bounded) {
                    most = Math.max(least, value);
                }
            }
            int count = Math.max(least, most);
            if (count > MAX_REPETITION) {
                throw tooExpensive();
            }
            // **A zero count does not make the expansion zero.** RE2 still expands the counts around
            // `{0}` (measured: four layers with an inner `{0}` exhaust the heap), so the product
            // counts every repetition as at least one.
            expansion *= Math.max(1, count);
            if (expansion > MAX_EXPANSION) {
                throw tooExpensive();
            }
        }
        // A repetition expands the expression it repeats, so the node count is the product of the
        // counts times its length — the factor that turned a passing 969-character pattern into an
        // OOM at a 512 MB heap.
        if (expansion * Math.max(1, pattern.length()) > MAX_EXPANSION) {
            throw tooExpensive();
        }
    }

    /** RE2's counted repetitions take ASCII digits; anything else is literal text. */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Moves past one escape, including the forms whose braces are part of the escape.
     *
     * <p>{@code \Q…\E} is quoted text — a {@code {999}} inside it is six literal characters, not a
     * repetition. {@code \x{…}} and {@code \p{…}} / {@code \P{…}} carry braces that the repetition
     * scanner must not read either. Returns the index of the last character consumed.
     */
    private static int skipEscape(String pattern, int backslash) {
        if (backslash + 1 >= pattern.length()) {
            return backslash;
        }
        char next = pattern.charAt(backslash + 1);
        if (next == 'Q') {
            int end = pattern.indexOf("\\E", backslash + 2);
            return end < 0 ? pattern.length() - 1 : end + 1;
        }
        if ((next == 'x' || next == 'p' || next == 'P')
                && backslash + 2 < pattern.length() && pattern.charAt(backslash + 2) == '{') {
            int close = pattern.indexOf('}', backslash + 3);
            return close < 0 ? pattern.length() - 1 : close;
        }
        return backslash + 1;
    }

    private static RequestException tooExpensive() {
        return new RequestException(ErrorCode.INVALID_REQUEST,
                "this pattern is too expensive to run against this draft; narrow it or use cat | grep",
                List.of(new ApiError.Detail("pattern", "too_expensive")));
    }

    /** Signals that the request's matching budget is spent; caught where the scan is abandoned. */
    private static final class MatchTooExpensive extends RuntimeException {
        MatchTooExpensive() {
            super(null, null, false, false);
        }
    }

    /**
     * The request's matching allowance: one deadline, and one read count shared by every line.
     *
     * <p><strong>Shared is the point.</strong> A fresh count per line meant a file of short lines
     * never reached the 1,024th read, so the deadline was never checked — the clock has to follow the
     * request, not the line (ADR 0067).
     */
    private static final class MatchBudget {
        private final long deadline;
        private long reads;

        MatchBudget(long deadline) {
            this.deadline = deadline;
        }

        /** Called for each character the engine reads; throws once the deadline has passed. */
        void spend() {
            if ((++reads & 0x3FF) == 0 && System.nanoTime() > deadline) {
                throw new MatchTooExpensive();
            }
        }

        boolean expired() {
            return System.nanoTime() > deadline;
        }
    }

    /**
     * The line RE2 reads, charged against the request's budget as it goes.
     *
     * <p>The engine reads through {@link CharSequence}, a few characters per position, so this is
     * where a scan that is cheaper to abandon than to finish is abandoned.
     */
    private static final class BoundedText implements CharSequence {
        private final CharSequence text;
        private final MatchBudget budget;

        BoundedText(CharSequence text, MatchBudget budget) {
            this.text = text;
            this.budget = budget;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            budget.spend();
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedText(text.subSequence(start, end), budget);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /**
     * A relpath prefix, matched by segment: {@code references/} matches what is under it and not
     * {@code references-old/}. A leading or trailing {@code /} is tolerated because a person types
     * both.
     */
    private static boolean within(String relpath, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        String wanted = prefix;
        while (wanted.startsWith("/")) {
            wanted = wanted.substring(1);
        }
        while (wanted.endsWith("/")) {
            wanted = wanted.substring(0, wanted.length() - 1);
        }
        return relpath.equals(wanted) || relpath.startsWith(wanted + "/");
    }

    static String pinOf(VersionSummary row) {
        return row.version() != null ? row.version() : row.id();
    }
}
