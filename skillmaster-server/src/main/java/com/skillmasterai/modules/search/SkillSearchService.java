package com.skillmasterai.modules.search;

import com.skillmasterai.common.CursorCodec;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.SkillCatalogService;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * M8: finding a skill by what it says about itself.
 *
 * <p>What is searched is fixed by §3.4 and is a safety constraint rather than a performance one:
 * the name, the title and the description, and <strong>never the body or the files</strong>. A
 * full-text index over a skill's content would let fragments of it be reconstructed by querying for
 * them, and the manifest's whole purpose is that content is fetched deliberately and completely
 * rather than sampled.
 *
 * <p><strong>This class owns policy and no SQL.</strong> Ranking, the meaning of a cursor, and the
 * decision to search at all are here; the query itself is M7's, because the tables are, and §2.5
 * rule 1 admits no arrangement where one statement names two modules' tables. The same split as the
 * blob sweep, for the same reason.
 *
 * <h2>Why the ordering is a tuple and not a score</h2>
 *
 * <p>Results come back ordered by {@code (relevance, updated_at descending, id)} — a lexicographic
 * comparison, not a single weighted sum. The sum is the more obvious design and it is wrong here,
 * for a reason that only appears on page two: a sum containing the current time evaluates
 * differently every time it runs, so the key a cursor carries stops meaning what it meant when the
 * cursor was issued, and rows get skipped or repeated with nothing in the response to say so.
 *
 * <p>A tuple has no such problem — every component is a stored column or a function of stored
 * columns — and it states the intent more directly: text relevance first, newest first within a
 * tier, and an id to break the remaining ties so the order is total.
 *
 * <p><strong>The namespace is a required parameter.</strong> §3.4 is blunt that the ownership
 * filter has to be in the query, because a search that returns an index's matches directly leaks
 * every other user's private skills. v1 has one namespace per user, so passing it makes the result
 * "mine" — and it is applied where the rows are found, not afterwards, so there is no version of
 * the query that can forget it.
 */
public final class SkillSearchService {

    private final SkillCatalogService catalog;
    private final RelevanceWeights weights;

    public SkillSearchService(SkillCatalogService catalog, RelevanceWeights weights) {
        this.catalog = catalog;
        this.weights = weights;
    }

    /**
     * @param caller          who is asking, and the namespace they own — the access predicate's two
     *                        halves (ADR 0034). M7 applies both; nothing here decides access
     * @param namespaceFilterIds narrows the result within what the caller may already see; empty
     *                        for all of it. The caller resolves the slugs, because resolving them is
     *                        a question about M4's table and this class owns policy, not SQL
     */
    public SearchPage search(SearchRequest request, Caller caller, List<String> namespaceFilterIds) {
        boolean byRelevance = request.sort() == SearchRequest.SortOrder.RELEVANCE;
        String ordering = orderingIdOf(byRelevance);
        Optional<CursorCodec.Cursor> cursor =
                resumePointOf(request.cursor(), ordering, byRelevance);

        // One more row than asked for: its presence is how "there is another page" is known without
        // a second query to count what is left.
        List<SkillCatalogService.CatalogRow> rows = catalog.page(
                namespaceFilterIds,
                request.query(),
                new SkillCatalogService.RankingWeights(weights.name(), weights.title(),
                        weights.description()),
                byRelevance,
                cursor.map(CursorCodec.Cursor::key).orElse(null),
                request.limit() + 1,
                caller);

        boolean hasMore = rows.size() > request.limit();
        List<SkillCard> cards = rows.stream()
                .limit(request.limit())
                .map(SkillSearchService::toCard)
                .toList();

        // The cursor names the last row actually returned, never the extra one fetched only to
        // answer "is there more" — a cursor pointing past a row would drop it.
        String next = hasMore
                ? CursorCodec.encode(ordering,
                        rows.get(request.limit() - 1).sortKey(byRelevance))
                : null;
        return new SearchPage(cards, next);
    }

    /** @param nextCursor null when this is the last page — the only signal a client needs */
    public record SearchPage(List<SkillCard> skills, String nextCursor) {
        public SearchPage {
            skills = List.copyOf(skills);
        }
    }

    private String orderingIdOf(boolean byRelevance) {
        return byRelevance ? weights.orderingId() : "recent";
    }

    /**
     * The cursor's key, once it is known to belong to this ordering.
     *
     * <p>Every rejection here is a 400 rather than a fresh first page. Silently ignoring a cursor a
     * client believes in turns pagination into a loop that returns page one forever, and the client
     * cannot tell that from a listing that happens to shrink.
     */
    private Optional<CursorCodec.Cursor> resumePointOf(String cursor, String ordering,
            boolean byRelevance) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor)
                .orElseThrow(() -> new InvalidSearchRequestException(
                        "the cursor is not one this version understands"));
        if (!decoded.ordering().equals(ordering)) {
            throw new InvalidSearchRequestException(
                    "the cursor belongs to a different ordering: it was issued for '"
                            + decoded.ordering() + "' and this request asks for '" + ordering + "'");
        }
        if (decoded.key().size() != catalog.sortKeyWidth(byRelevance)) {
            throw new InvalidSearchRequestException("the cursor has the wrong number of key parts");
        }
        requireKeyComponentsOfTheRightShape(decoded.key(), byRelevance);
        return Optional.of(decoded);
    }

    /**
     * The key's components, not just how many there are.
     *
     * <p>The width check alone lets a hand-edited cursor through to the query, where the first
     * component is {@code CAST(… AS integer)} — an uncastable value is a SQL error, and a SQL error
     * on a request path is a 500 for what §4.1 calls a cursor nobody can read. The remaining
     * components are compared as text, so a bad one would not fail at all; it would silently move
     * the boundary, which is the skipped-or-repeated-row bug the cursor exists to prevent.
     *
     * <p>{@link CursorCodec} deliberately never throws — a decoder that does would turn a bad query
     * string into a 500 by itself — so the shape check lands here, where the answer is already known
     * to be a 400.
     */
    private static void requireKeyComponentsOfTheRightShape(List<String> key, boolean byRelevance) {
        if (key.getLast().isBlank()) {
            throw new InvalidSearchRequestException("the cursor is not one this version understands");
        }
        try {
            if (byRelevance) {
                requireCanonicalScore(key.get(0));
                requireCanonicalTimestamp(key.get(1));
            } else {
                requireCanonicalTimestamp(key.get(0));
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new InvalidSearchRequestException(
                    "the cursor is not one this version understands");
        }
    }

    /**
     * The score has to be ASCII digits, because that is what the query's {@code CAST(:k0 AS
     * integer)} accepts.
     *
     * <p>{@code Integer.parseInt} is looser than PostgreSQL's integer input: it accepts every
     * Unicode decimal digit, so a key part written in Arabic-Indic digits passed the check and then
     * died on the cast — the 500 this whole method exists to prevent. A value too large for an int
     * is left to throw, which the caller turns into the same 400.
     */
    private static void requireCanonicalScore(String value) {
        if (!value.matches("[0-9]+")) {
            throw new InvalidSearchRequestException("the cursor is not one this version understands");
        }
        Integer.parseInt(value);
    }

    /**
     * The timestamp has to be the canonical spelling, not merely a parseable one.
     *
     * <p>The keyset compares {@code updated_at} <em>as text</em>, which is only sound because
     * {@link Timestamps} writes a fixed width, a fixed offset and zero padding — lexicographic order
     * is chronological order. {@code Instant.parse} is looser than that: it accepts {@code
     * 2026-01-01T00:00:00+00:00}, the same instant written differently, and a text comparison
     * against it would land somewhere else entirely. Round-tripping through the formatter is what
     * checks the property the comparison actually depends on.
     */
    private static void requireCanonicalTimestamp(String value) {
        if (!Timestamps.format(Timestamps.parse(value)).equals(value)) {
            throw new InvalidSearchRequestException("the cursor is not one this version understands");
        }
    }

    /**
     * The namespace slug comes from the caller's namespace rather than from the row.
     *
     * <p>Not a shortcut: v1 returns skills from exactly one namespace — the caller's — so the slug
     * is the same for every card, and reading it from the row would mean M7 joining {@code
     * namespace}, a table it does not own, for a value already in hand.
     */
    /**
     * The row's own namespace, not the caller's.
     *
     * <p>It used to be the caller's, and that was correct while every result was in the one
     * namespace they owned. Since ADR 0034 a listing spans namespaces, so taking the slug from the
     * request would label a shared skill with the wrong one — the address on the card would then
     * point at a skill of that name in the caller's own namespace, which may not exist.
     */
    private static SkillCard toCard(SkillCatalogService.CatalogRow row) {
        return new SkillCard(row.id(), row.name(), row.description(), row.whenToUse(),
                row.namespaceId(), row.updatedAt());
    }
}
