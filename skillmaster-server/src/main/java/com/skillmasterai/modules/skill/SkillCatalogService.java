package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SkillCatalogRepository;
import java.util.List;

/**
 * M12's listing seam: pages of {@code skill}, ordered and filtered.
 *
 * <p>Search policy belongs to M8 (§2.5, §3.4) — which fields count, what a hit is worth, how a text
 * query is spelled. The tables belong here, in M12. So the query is decided by M8 and
 * <strong>executed</strong> here, which is the same split the blob sweep uses and for the same
 * reason: a statement naming two modules' tables is a rule-1 violation, and §3.4 requires the
 * ownership filter to be <em>in</em> the statement rather than applied to its results. Since
 * ADR 0053 both of these listings read only {@code skill} and {@code skill_grant} — the facts the
 * cards show are the V21 projection columns.
 *
 * <p>What crosses the boundary is deliberately dull: a namespace, an optional piece of text, three
 * numbers, a boolean and a cursor key. No type from M8 appears in this signature, because M12
 * depending on M8 while M8 depends on M12 is a cycle — and a cycle here would mean the two could
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
            RankingWeights weights, boolean byRelevance, boolean publicOnly,
            List<String> categoryKeys, List<String> publicTagKeys, List<String> afterKey,
            int limit, Integer offset, Caller caller) {
        return repository.page(namespaceFilterIds, queryText, weights, byRelevance, publicOnly,
                categoryKeys, publicTagKeys, afterKey, limit, offset, caller);
    }

    /**
     * The largest admissible set worth enumerating — 20000 ids, about 520 KB in one round trip.
     *
     * <p>Above it the caller stops trying to be exact and over-fetches instead. The number is a
     * transfer size, not a product limit: see the TD's two-stage rule.
     */
    public static final int ADMISSIBLE_ID_CAP = 20_000;

    /**
     * The set a search may look in, when it is small enough to name.
     *
     * <p><strong>This is the exact half of the TD's two-stage rule.</strong> Scope and filters are
     * M12's to evaluate — the tables are — and handing M13 a list of ids keeps the vector query
     * inside its own module while still restricting the scan <em>before</em> the top-k. Fetching a
     * prefix of the whole corpus and filtering it afterwards would lose recall exactly when a
     * caller's own skills are a small share of it.
     *
     * @return the ids and whether they are the whole set. Empty ids with {@code complete=false}
     *         means "too many to enumerate", which is not the same answer as "none"
     */
    public IdSet admissibleIds(SearchScope scope, SearchFilters filters, Caller caller) {
        List<String> ids = repository.admissibleIds(scope, filters, caller, ADMISSIBLE_ID_CAP);
        if (ids.size() > ADMISSIBLE_ID_CAP) {
            return new IdSet(List.of(), false);
        }
        return new IdSet(ids, true);
    }

    /** @param complete false when the set was too large to enumerate, so {@code ids} is empty */
    public record IdSet(List<String> ids, boolean complete) {
        public IdSet {
            ids = List.copyOf(ids);
        }
    }

    /**
     * Which of these ids the caller may see in that scope, with the card fields.
     *
     * <p><strong>The approximate half.</strong> M13 found the nearest vectors of the whole corpus;
     * this says which of them the caller may open under the same scope and filters. It can only
     * remove rows, never add one — which is what makes it safe to hand it a list from another
     * module.
     *
     * <p>Rows come back in no particular order: the caller restores the vector ranking from its own
     * scores, the only party that has them.
     */
    public List<CatalogRow> admissibleAmong(List<String> ids, SearchScope scope,
            SearchFilters filters, Caller caller) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return repository.admissibleAmong(ids, scope, filters, caller);
    }

    /** The same filters {@link #page} uses, counted before the page is cut. */
    public long count(List<String> namespaceFilterIds, String queryText, boolean publicOnly,
            List<String> categoryKeys, List<String> publicTagKeys, Caller caller) {
        return repository.count(namespaceFilterIds, queryText, publicOnly, categoryKeys,
                publicTagKeys, caller);
    }

    /**
     * The skills waiting to be published from, for one caller: those they hold {@code maintain} on
     * or above, with at least one draft. Newest waiting draft first.
     *
     * <p><strong>No {@code write} rung here, and that is the queue's whole premise</strong> (ADR 0090
     * decision 7): a row a person cannot publish is a decision they cannot make, and showing it only
     * to say so at the last moment is worse than not showing it. The filter is here rather than on
     * the page because the response carries no rung for the page to filter by — deliberately, since
     * a rung field would invite a second, weaker answer.
     *
     * @param limit rows to return; the caller asks for one more than it will serve, to tell
     *              "exactly this many" from "there are more"
     */
    public List<QueueRow> publishQueue(int limit, Caller caller) {
        return repository.publishQueue(limit, caller);
    }

    /**
     * The caller's own skills, drafts included, newest submission first (ADR 0031).
     *
     * <p>Lives beside {@link #page} rather than in a second class because both are listings of the
     * same table by the same module, and the difference is a question rather than a mechanism: this
     * one asks what the author has, that one asks what a consumer may find. <strong>The predicates
     * differ, though</strong> — this one accepts a grant at {@code write} and above and not
     * {@code browse}, because a draft is a version you could act on (ADR 0034, ADR 0047). It takes
     * no search policy at all, so M8 is not involved.
     *
     * @param limit   rows to return; the caller picks a number and says why
     * @param deleted false for the live list, true for the "deleted" one. The second is ownership's
     *                question alone, since restoring is the owner's (ADR 0050)
     */
    public List<AuthorRow> authorPage(int limit, boolean deleted, Caller caller) {
        return repository.authorPage(limit, deleted, caller);
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
     * @param updatedAt RFC3339 UTC of the current published version's submission, or of the newest
     *                  draft for a skill nothing has been published from. **Null when every version
     *                  has been discarded** — the row still exists, and its card says so — so a
     *                  reader must not treat it as a string that is always there. Deliberately not
     *                  {@code skill.updated_at}: that records when something went live
     * @param deletedAt RFC3339 UTC of the soft delete, or null on the live list. The deleted list
     *                  shows "删除于 …" rather than "更新于 …" (ADR 0050), because a page that no
     *                  longer resolves has no "last updated"
     */
    public record AuthorRow(String id, String namespaceId, String name, String title,
            String description, String visibility, String currentVersion, String currentDigest,
            int drafts, String newestDraftVersion, String newestDraftDigest,
            String updatedAt, String deletedAt, String offlineAt) {
    }

    /**
     * One skill in the publish queue: the row the queue's per-skill facts come from.
     *
     * @param id          the skill's id, which is what the queue hands M7 to fetch this skill's drafts
     * @param currentVersion the version consumers get, or null when nothing is published yet
     * @param currentSubmittedAt when that version was submitted, or null. Deliberately not the
     *                    list's "updated at": that one falls back to the newest draft, and the queue
     *                    says "已上线 @" with a date that belongs to the thing it names
     * @param newestDraftAt the ordering key for the queue — skills are ranked by their newest
     *                    waiting draft, because the question the page answers is "what is waiting"
     */
    public record QueueRow(String id, String namespaceId, String name, String currentVersion,
            String currentSubmittedAt, String newestDraftAt) {
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
     *                    a constant the caller already knows. M12 owns the column; the slug the wire
     *                    needs is M4's, and the use case is where the two meet
     * @param updatedAt RFC3339 UTC of the current published version's submission (V21). The card's
     *                  "更新于" and the cursor's ordering key; NULL only for a row nothing has been
     *                  published from, which the listing excludes above
     * @param relevance the text score, 0 when no query text was given
     */
    /**
     * @param source     the caller's relationship to the skill — created / shared / joined — or null
     *                   when they have none (v14). Nullable on purpose: a discovery result the
     *                   caller has never added is a result, and its card simply has no source to show
     * @param state      the display state — live / draft / offline / deleted — derived from the
     *                   three columns that already say it (v14). Never stored
     * @param version    the current version's author-given name, or null when there is none
     * @param deletedAt  the soft-delete time, or null on the live list
     * @param categoryKey/name the one shelf, or null for none (ADR 0078)
     * @param active     whether the caller's library entry is effective; false for a skill that is
     *                   merely public, which is what keeps {@code my_tags} off a discovery card
     */
    public record CatalogRow(String id, String namespaceId, String name, String description,
            String whenToUse, String updatedAt, int relevance, String source,
            String state, String version, String deletedAt,
            String categoryKey, String categoryName, boolean active) {

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
