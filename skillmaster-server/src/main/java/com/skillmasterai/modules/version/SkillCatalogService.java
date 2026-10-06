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
 * <p>The ownership filter is not optional and has no default. It is the predicate whose omission
 * leaks every other user's private skills (§3.4), so it is required at the only place it can be
 * applied. v1 has no sharing, so callers pass the one namespace their subject owns.
 */
public final class SkillCatalogService {

    private final SkillCatalogRepository repository;

    public SkillCatalogService(SkillCatalogRepository repository) {
        this.repository = repository;
    }

    /**
     * @param namespaceId  the only namespace whose skills may be returned
     * @param queryText    the caller's text, unescaped — this module owns how it becomes a pattern
     * @param weights      what a hit in each field is worth
     * @param byRelevance  true orders by text relevance then recency; false by recency alone
     * @param afterKey     the cursor's key, or null for the first page
     * @param limit        rows to return; the caller asks for one more than it will use
     */
    public List<CatalogRow> page(String namespaceId, String queryText, RankingWeights weights,
            boolean byRelevance, List<String> afterKey, int limit) {
        return repository.page(namespaceId, queryText, weights, byRelevance, afterKey, limit);
    }

    /**
     * The caller's own skills, drafts included, newest submission first (ADR 0031).
     *
     * <p>Lives beside {@link #page} rather than in a second class because both are listings of the
     * same table by the same module, and the difference is a question rather than a mechanism: this
     * one asks what the author has, that one asks what a consumer may find. It takes no search
     * policy at all, so M8 is not involved.
     *
     * @param namespaceId the only namespace whose skills may be returned
     * @param limit       rows to return; the caller picks a number and says why
     */
    public List<AuthorRow> authorPage(String namespaceId, int limit) {
        return repository.authorPage(namespaceId, limit);
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
     * anything is published at all — the latter as a null rather than a zero, because version 0 does
     * not exist and a number is an address.
     *
     * @param currentNumber the published version's alias, or null when nothing is published yet
     * @param currentDigest its digest, or null in the same case
     * @param drafts        how many versions are waiting to be published or discarded
     * @param newestDraft   the highest-numbered one of those, or null when there are none — which is
     *                      the difference between {@code drafts == 0} and a listing that can send a
     *                      reader to the thing that is waiting
     * @param latestSubmittedAt RFC3339 UTC of the most recent non-discarded submission, which is what
     *                          the listing is ordered by. **Null when every version has been
     *                          discarded** — the query is {@code max(submitted_at) WHERE state <>
     *                          'discarded'} — so a reader must not treat it as a string that is
     *                          always there
     */
    public record AuthorRow(String id, String name, String title, String description,
            String visibility, Integer currentNumber, String currentDigest, int drafts,
            Integer newestDraft, String latestSubmittedAt) {
    }

    /**
     * One skill, as a listing needs it: L1 fields only, plus the score this ordering ranked it by.
     *
     * @param number    the current version's immutable alias (ADR 0012); a card carries it so a
     *                  client can pin without a second request
     * @param digest    the current version's digest, bare lowercase hex
     * @param updatedAt RFC3339 UTC; the row's own timestamp, not the version's — a metadata edit
     *                  moves it without creating a version (§4.3)
     * @param relevance the text score, 0 when no query text was given
     */
    public record CatalogRow(String id, String name, String title, String description,
            String visibility, int number, String digest, String updatedAt, int relevance) {

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
