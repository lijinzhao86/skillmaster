package com.skillmasterai.modules.version;

import com.skillmasterai.modules.version.internal.SkillCatalogRepository;
import java.util.List;

/**
 * M7's listing seam: pages of {@code skill}, ordered and filtered.
 *
 * <p>Search policy belongs to M8 (§2.5, §3.4) — which fields count, what a hit is worth, how a text
 * query is spelled. The tables belong here. So the query is decided by M8 and
 * <strong>executed</strong> here, which is the same split the blob sweep uses and for the same
 * reason: a statement naming two modules' tables is a rule-1 violation, and §3.4 requires the
 * ownership filter to be <em>in</em> the statement rather than applied to its results.
 *
 * <p>What crosses the boundary is deliberately dull: a namespace, an optional piece of text, three
 * numbers, a boolean and a cursor key. No type from M8 appears in this signature, because M7
 * depending on M8 while M8 depends on M7 is a cycle — and a cycle here would mean the two could
 * never be reasoned about apart.
 *
 * <p>The access filter is not optional and has no default. It is the predicate whose omission leaks
 * every other user's private skills (§3.4), so it is required at the only place it can be applied.
 * Since ADR 0034 it is a disjunction — the namespace the caller owns, or a skill granted to them —
 * and it travels as one {@link Caller} rather than as separate arguments, so no caller can supply
 * half of it.
 */
public final class SkillCatalogService {

    private final SkillCatalogRepository repository;

    public SkillCatalogService(SkillCatalogRepository repository) {
        this.repository = repository;
    }

    /**
     * @param namespaceFilterIds narrows within what the caller may already see (§4.2); empty for
     *                          everything they may see. **A filter, never the access rule** — since
     *                          ADR 0034 that is the disjunction inside {@code caller}, and a
     *                          namespace outside it simply selects nothing. A set rather than one id
     *                          because a caller reads in more than one namespace, and because the two
     *                          predicates compose the only way that is safe: this one can only remove
     *                          rows from what the access rule already allowed
     * @param queryText    the caller's text, unescaped — this module owns how it becomes a pattern
     * @param weights      what a hit in each field is worth
     * @param byRelevance  true orders by text relevance then recency; false by recency alone
     * @param afterKey     the cursor's key, or null for the first page
     * @param limit        rows to return; the caller asks for one more than it will use
     */
    public List<CatalogRow> page(List<String> namespaceFilterIds, String queryText,
            RankingWeights weights, boolean byRelevance, List<String> afterKey, int limit,
            Caller caller) {
        return repository.page(namespaceFilterIds, queryText, weights, byRelevance, afterKey, limit,
                caller);
    }

    /**
     * The caller's own skills, drafts included, newest submission first (ADR 0031).
     *
     * <p>Lives beside {@link #page} rather than in a second class because both are listings of the
     * same table by the same module, and the difference is a question rather than a mechanism: this
     * one asks what the author has, that one asks what a consumer may find. <strong>The predicates
     * differ, though</strong> — this one accepts an {@code editor} grant and not a {@code viewer}
     * one, because a draft is a version you could act on (ADR 0034). It takes no search
     * policy at all, so M8 is not involved.
     *
     * @param limit  rows to return; the caller picks a number and says why
     */
    public List<AuthorRow> authorPage(int limit, Caller caller) {
        return repository.authorPage(limit, caller);
    }

    /**
     * How many components a cursor key carries under this ordering.
     *
     * <p>Exposed rather than left to the caller to infer: the key is what this module's ordering
     * compares, so its shape is this module's to state. A caller that assumed a width and guessed
     * wrong would build a cursor that replays the first page forever.
     */
    public int sortKeyWidth(boolean byRelevance) {
        return byRelevance ? 3 : 2;
    }

    /** @param name/title/description points for a hit in that field; hits add up */
    public record RankingWeights(int name, int title, int description) {
    }

    /**
     * One of the author's own skills, as their list needs it.
     *
     * <p>Carries no score, because nothing ranks this listing. What it carries instead is the two
     * facts that only exist on this side of the split: how many drafts are waiting, and whether
     * anything is published at all — the latter as a null rather than a placeholder, because a
     * version that names nothing is not a version a client can fetch.
     *
     * <p>Every version here travels as its name plus its digest, never as one of them alone: the name
     * may be absent (ADR 0033) and the digest is what the row can always be addressed by, so a caller
     * that had only one of the two would have to invent the other.
     *
     * @param namespaceId which namespace this skill is in. **Carried since ADR 0034**, for the same
     *                    reason {@link CatalogRow#namespaceId} is: this listing now holds the ones
     *                    shared with the caller as an editor, so the namespace is a property of the
     *                    row rather than something the caller already knows. A caller that labelled
     *                    every row with its own namespace would give a shared skill its own address —
     *                    which resolves to a different skill, or to none
     * @param currentVersion the published version's name, or null when nothing is published yet — or
     *                       when the published version's author declared no name
     * @param currentDigest  its digest, or null in the first of those cases
     * @param drafts         how many versions are waiting to be published or discarded
     * @param newestDraftVersion the name of the most recently submitted draft, or null when there are
     *                       none — which is the difference between {@code drafts == 0} and a listing
     *                       that can send a reader to the thing that is waiting
     * @param newestDraftDigest its digest, null in the same case. Carried alongside the name so a
     *                       nameless draft can still be linked to
     * @param latestSubmittedAt RFC3339 UTC of the most recent non-discarded submission, which is what
     *                          the listing is ordered by. **Null when every version has been
     *                          discarded** — the query is {@code max(submitted_at) WHERE state <>
     *                          'discarded'} — so a reader must not treat it as a string that is
     *                          always there
     */
    public record AuthorRow(String id, String namespaceId, String name, String title,
            String description, String visibility, String currentVersion, String currentDigest,
            int drafts, String newestDraftVersion, String newestDraftDigest,
            String latestSubmittedAt) {
    }

    /**
     * One skill, as a listing needs it: L1 fields only, plus the score this ordering ranked it by.
     *
     * <p><strong>Neither the version name nor the digest is here any more, and neither was dropped by
     * accident.</strong> A card carried the pair so that one search was enough to pin — the client
     * could write {@code @1.2.3} without fetching {@code latest} first. Since ADR 0035 the client
     * resolves the version and remembers it itself, so the pair had no reader left. The join on
     * {@code s.current_version_id} stays: that is what keeps a draft-only skill out of a listing, and
     * it has nothing to do with what is selected.
     *
     * @param namespaceId which namespace this skill is in. **Carried since ADR 0034**, because a
     *                    listing may now span namespaces — the caller's own plus any they have been
     *                    granted a skill in — so the namespace is a property of the row and no longer
     *                    a constant the caller already knows. M7 owns the column; the slug the wire
     *                    needs is M4's, and the use case is where the two meet
     * @param updatedAt RFC3339 UTC; the row's own timestamp, not the version's — a metadata edit
     *                  moves it without creating a version (§4.3)
     * @param relevance the text score, 0 when no query text was given
     */
    public record CatalogRow(String id, String namespaceId, String name, String description,
            String whenToUse, String updatedAt, int relevance) {

        /**
         * The cursor key for this row, ordered as {@link #page} compares its components.
         *
         * <p>The mirror image of the keyset predicate in {@link SkillCatalogRepository}: this
         * row's key becomes the value the next request compares against, so the two must agree on
         * both the components and their order.
         */
        public List<String> sortKey(boolean byRelevance) {
            return byRelevance
                    ? List.of(String.valueOf(relevance), updatedAt, id)
                    : List.of(updatedAt, id);
        }
    }
}
