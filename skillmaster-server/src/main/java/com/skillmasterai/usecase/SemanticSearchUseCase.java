package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.retrieval.EmbeddingSettings;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.retrieval.RetrievalService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.LabelService;
import com.skillmasterai.modules.skill.MyWhenToUseService;
import com.skillmasterai.modules.skill.SearchFilters;
import com.skillmasterai.modules.skill.SearchScope;
import com.skillmasterai.modules.skill.SkillCatalogService;
import com.skillmasterai.modules.skill.SkillCategoryService;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.TagNames;
import com.skillmasterai.usecase.model.SkillCardView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Search by meaning (v19): M13 finds the neighbours, M12 says which of them this caller may open.
 *
 * <p><strong>The two-stage rule, implemented once.</strong> Scope and filters are M12's answer to
 * "what may this caller look in". When that set is small enough to name, it is handed to the vector
 * query, which then restricts its scan <em>before</em> the top-k — exact, whatever the corpus size.
 * When it is too large, the vector query takes the corpus's top {@code limit × overFetch} and M12
 * filters those ids afterwards — approximate, but only when the filter was not selective anyway.
 *
 * <p><strong>Degrading is a first-class outcome, not an exception.</strong> When the provider is
 * down the result says why, and the controller runs the keyword search it already had; the caller
 * gets results and a note, never a 500 and never an empty page that looks like "no such skill".
 */
@Component
public class SemanticSearchUseCase {

    /** The ceiling from the TD: the console shows six, a CLI reader wants a shortlist. */
    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_LIMIT = 10;


    private final NamespaceService namespaces;
    private final SkillCatalogService catalog;
    private final SkillCategoryService categories;
    private final SkillTagService tags;
    private final SkillCardAssembler cards;
    private final RetrievalService retrieval;
    private final EmbeddingSettings settings;
    private final MyWhenToUseService notes;
    private final LabelService labels;

    public SemanticSearchUseCase(NamespaceService namespaces, SkillCatalogService catalog,
            SkillCategoryService categories, SkillTagService tags, SkillCardAssembler cards,
            RetrievalService retrieval, EmbeddingSettings settings, MyWhenToUseService notes,
            LabelService labels) {
        this.namespaces = namespaces;
        this.catalog = catalog;
        this.categories = categories;
        this.tags = tags;
        this.cards = cards;
        this.retrieval = retrieval;
        this.settings = settings;
        this.notes = notes;
        this.labels = labels;
    }

    /**
     * @param score the cosine similarity or exact-run length the ranking used
     * @param tier  the evidence tier: 0 name, 1 personal exact, 2 personal semantic,
     *              3 author exact, 4 generic semantic
     */
    public record Hit(SkillCardView card, double score, int tier) {
        /** A semantic hit that no exact or personal evidence promoted. */
        public Hit(SkillCardView card, double score) {
            this(card, score, 4);
        }
    }

    /**
     * @param hits  ranked best first, at most {@code limit}
     * @param note      null on a normal answer; the reason semantic search did not run otherwise. A
     *                  non-null note means {@code hits} is empty and the controller should fall back
     * @param emptyNote a sentence for an empty answer that is the caller's to fix — a category or
     *                  tag name that is not in the vocabulary — rather than "nothing was close
     *                  enough". Null when the emptiness needs no special explanation, and always
     *                  null when {@code note} is set: the two are different kinds of empty
     */
    public record Outcome(List<Hit> hits, String note, String emptyNote) {
        public Outcome {
            hits = List.copyOf(hits);
        }

        /** The ordinary answer, with the "nothing cleared the threshold" reading left to the caller. */
        public Outcome(List<Hit> hits, String note) {
            this(hits, note, null);
        }

        public boolean degraded() {
            return note != null;
        }
    }

    /**
     * Whether semantic search may be attempted at all — the deployment switch.
     *
     * <p>The controller asks before it calls {@link #search}, so that a switched-off deployment
     * does not even enumerate an id set it will not use.
     */
    public boolean enabled() {
        return retrieval.enabled();
    }

    /**
     * <p><strong>Not transactional, deliberately.</strong> It makes an HTTP call to the provider,
     * and a transaction would hold a pooled connection for the whole of it — ten slow searches
     * would exhaust the pool and stall everything else, publishes included. The reads below do not
     * need one snapshot: the visibility filter runs last and re-checks every row, so a set that
     * moved in between costs a row, never an answer that should not have been given.
     *
     * @param limit rows wanted, 1..{@link #MAX_LIMIT}; the caller validates before getting here
     */
    public Outcome search(String query, SearchScope scope, List<String> categoryNames,
            List<String> publicTagNames, List<String> myTagNames, List<String> labelKeys, int limit,
            List<String> excludedIds, AuthenticatedSubject subject) {
        if (!retrieval.enabled()) {
            // Switched off: answer without touching the database or enumerating an id set nobody
            // will use. The caller falls through to the keyword path with no note — this is the
            // configured state, not a degradation (PRD #8).
            return new Outcome(List.of(), null);
        }
        Caller caller = Callers.of(namespaces, subject);

        SearchFilters filters = resolve(categoryNames, publicTagNames, myTagNames, labelKeys,
                subject.userId());
        if (filters == null) {
            // Some name was not in the vocabulary. The keyword path's rule applies unchanged: a
            // filter selects nothing rather than being dropped, because a caller that typed a word
            // must not be shown results that ignored it. Said in its own words — "nothing relevant"
            // would send a person looking for a better question instead of a correct word.
            return new Outcome(List.of(), null, "分类或标签的名字不在词表里，没有可检索的范围。");
        }

        java.util.Set<String> excluded = java.util.Set.copyOf(
                excludedIds == null ? List.of() : excludedIds);
        SkillCatalogService.IdSet admissible = catalog.admissibleIds(scope, filters, caller);
        List<RetrievalService.Candidate> candidates;
        if (admissible.complete()) {
            // **The exclusion is spent here, on the candidate set, before the ranking** (v30): the
            // nearest ids of what remains are the answer, so the page is a full page of unseen
            // skills rather than a full page with holes. A vector query has no cursor to fetch more
            // from (similarity is not a page boundary), which is why this cannot be left to the
            // caller to filter afterwards.
            List<String> admissibleIds = excluded.isEmpty()
                    ? admissible.ids()
                    : admissible.ids().stream().filter(id -> !excluded.contains(id)).toList();
            if (scope == SearchScope.LIBRARY || scope == SearchScope.ALL) {
                // The library is small enough to rank in one pass: exact name/note evidence and
                // S2 are available for every entry, not only for the vector's top-k. One provider
                // call answers both semantic reads.
                List<String> libraryIds = scope == SearchScope.LIBRARY
                        ? admissibleIds
                        : libraryIds(filters, caller, excluded);
                RetrievalService.LibraryOutcome semantic = retrieval.searchLibrary(query,
                        subject.userId(), libraryIds, limit);
                List<Hit> libraryHits = libraryRank(query, libraryIds, semantic, filters, caller,
                        limit, subject);
                if (scope == SearchScope.LIBRARY) {
                    if (libraryHits.isEmpty() && semantic.degraded()) {
                        return new Outcome(List.of(), semantic.fallbackReason());
                    }
                    return new Outcome(libraryHits, null);
                }
                // scope=all: ADR 0180 puts library rows through the personal tiers and leaves
                // public rows on the generic semantic ranking. The public slice is the nearest
                // vectors minus the library ids already ranked above.
                RetrievalService.Outcome exact = retrieval.nearestWithin(query, admissibleIds,
                        Math.min(MAX_LIMIT * 2, limit + libraryIds.size()));
                if (exact.degraded() && libraryHits.isEmpty()) {
                    return new Outcome(List.of(), exact.fallbackReason());
                }
                java.util.Set<String> inLibrary = java.util.Set.copyOf(libraryIds);
                List<RetrievalService.Candidate> publicOnly = exact.degraded()
                        ? List.of()
                        : exact.candidates().stream()
                                .filter(candidate -> !inLibrary.contains(candidate.skillId()))
                                .toList();
                return new Outcome(mergeRanked(libraryHits,
                        rank(publicOnly, scope, filters, caller, limit, subject), limit), null);
            }
            RetrievalService.Outcome exact =
                    retrieval.nearestWithin(query, admissibleIds, limit);
            if (exact.degraded()) {
                return new Outcome(List.of(), exact.fallbackReason());
            }
            // M12 answers "which", never "in what order": the scores are this use case's input and
            // the catalogue never sees them.
            return new Outcome(rank(exact.candidates(), scope, filters, caller, limit, subject),
                    null);
        }

        // The approximate half: the corpus is too large to name a candidate set, so the vector query
        // takes a wider slice and the exclusion is applied to it. Widening by the excluded size is
        // the same compensation the literal path uses — approximate, like the visibility filter that
        // already runs here.
        if (scope == SearchScope.ALL) {
            // Library rows stay enumerable even when the public corpus is not, so their personal
            // tiers are computed in full and the public slice is asked for a little larger to
            // survive removing them.
            List<String> libraryIds = libraryIds(filters, caller, excluded);
            RetrievalService.LibraryOutcome semantic = retrieval.searchLibrary(query,
                    subject.userId(), libraryIds, limit);
            List<Hit> libraryHits = libraryRank(query, libraryIds, semantic, filters, caller,
                    limit, subject);
            RetrievalService.Outcome wide = retrieval.nearest(query,
                    limit * settings.overFetch() + excluded.size() + libraryIds.size());
            if (wide.degraded() && libraryHits.isEmpty()) {
                return new Outcome(List.of(), wide.fallbackReason());
            }
            java.util.Set<String> inLibrary = java.util.Set.copyOf(libraryIds);
            List<RetrievalService.Candidate> publicOnly = wide.degraded()
                    ? List.of()
                    : wide.candidates().stream()
                            .filter(candidate -> !excluded.contains(candidate.skillId()))
                            .filter(candidate -> !inLibrary.contains(candidate.skillId()))
                            .toList();
            return new Outcome(mergeRanked(libraryHits,
                    rank(publicOnly, scope, filters, caller, limit, subject), limit), null);
        }
        RetrievalService.Outcome wide =
                retrieval.nearest(query, limit * settings.overFetch() + excluded.size());
        if (wide.degraded()) {
            return new Outcome(List.of(), wide.fallbackReason());
        }
        candidates = excluded.isEmpty()
                ? wide.candidates()
                : wide.candidates().stream()
                        .filter(candidate -> !excluded.contains(candidate.skillId()))
                        .toList();
        return new Outcome(rank(candidates, scope, filters, caller, limit, subject), null);
    }

    /** The caller's visible library ids, less the ids this request excluded. */
    private List<String> libraryIds(SearchFilters filters, Caller caller,
            java.util.Set<String> excluded) {
        return catalog.admissibleIds(SearchScope.LIBRARY, filters, caller).ids().stream()
                .filter(id -> !excluded.contains(id))
                .toList();
    }

    /** Personal-tier hits first, then the generic ranking, as one reproducible list. */
    private static List<Hit> mergeRanked(List<Hit> tiered, List<Hit> generic, int limit) {
        List<Hit> all = new ArrayList<>(tiered.size() + generic.size());
        all.addAll(tiered);
        all.addAll(generic);
        all.sort((left, right) -> {
            int byTier = Integer.compare(left.tier(), right.tier());
            if (byTier != 0) {
                return byTier;
            }
            int byScore = Double.compare(right.score(), left.score());
            return byScore != 0 ? byScore : left.card().id().compareTo(right.card().id());
        });
        return all.stream().limit(limit).toList();
    }

    /**
     * Library ranking by evidence tier, then score (v59 P1).
     *
     * <p><strong>S0 name &gt; S1 personal exact &gt; S2 personal semantic &gt; S3 author exact &gt;
     * S4 generic semantic.</strong> The exact evidence is computed over every admissible library
     * row, so a note match that the vector query did not put in its top-k still appears. A provider
     * outage does not erase S0/S1/S3: if the vector search degrades but an exact hit exists, those
     * hits are the answer; only when there is no exact hit does the caller fall back to the keyword
     * path with the provider's reason.
     */
    private List<Hit> libraryRank(String query, List<String> admissibleIds,
            RetrievalService.LibraryOutcome vector, SearchFilters filters, Caller caller, int limit,
            AuthenticatedSubject subject) {
        List<SkillCatalogService.CatalogRow> rows = catalog.admissibleAmong(admissibleIds,
                SearchScope.LIBRARY, filters, caller);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, SkillCatalogService.CatalogRow> rowById = new LinkedHashMap<>();
        for (SkillCatalogService.CatalogRow row : rows) {
            rowById.put(row.id(), row);
        }
        Map<String, String> personal = notes.allFor(subject.userId());

        record Ranked(int tier, double score, boolean personal) {
        }
        Map<String, Ranked> ranked = new LinkedHashMap<>();
        for (SkillCatalogService.CatalogRow row : rows) {
            // S0/S1 first; the author's own sentence is S3 and only applies when the caller has no
            // stronger exact evidence on this row.
            Optional<PersonalTriggers.Match> exact =
                    PersonalTriggers.exact(query, row.name(), row.title(), personal.get(row.id()));
            if (exact.isEmpty()) {
                exact = PersonalTriggers.authorExact(query, row.whenToUse());
            }
            exact.ifPresent(hit -> ranked.put(row.id(),
                    new Ranked(hit.tier(), hit.score(), hit.personal())));
        }

        // S2: the caller's own sentence vectors, one best score per skill. Threshold and margin
        // are applied before any of them are allowed to claim a tier.
        // Rows already claimed by S0/S1/S3 are not S2's rivals: their personal score never decided
        // anything, and letting it set the margin can suppress the only row S2 would promote.
        List<RetrievalService.PersonalHit> personalHits = vector.personal().stream()
                .filter(hit -> rowById.containsKey(hit.skillId()))
                .filter(hit -> {
                    Ranked existing = ranked.get(hit.skillId());
                    return existing == null || existing.tier() >= 3;
                })
                .filter(hit -> hit.score() >= settings.personalThreshold())
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
        double second = personalHits.size() > 1 ? personalHits.get(1).score() : 0;
        boolean personalConfident = !personalHits.isEmpty()
                && personalHits.getFirst().score() - second >= settings.personalMargin();
        if (personalConfident) {
            for (RetrievalService.PersonalHit hit : personalHits) {
                Ranked existing = ranked.get(hit.skillId());
                // S2 beats S3: "both halves matched, rank by mine" is the spec's rule. It never
                // beats S0/S1, which are the caller's stronger exact evidence.
                if (existing == null || existing.tier() >= 3) {
                    ranked.put(hit.skillId(), new Ranked(2, hit.score(), true));
                }
            }
        }

        if (!vector.degraded()) {
            for (RetrievalService.Candidate candidate : vector.corpus()) {
                if (!rowById.containsKey(candidate.skillId())) {
                    continue;
                }
                Ranked already = ranked.get(candidate.skillId());
                // A library skill's vector is the author's text — name, title, description and
                // when_to_use — so it is S3. S4 is the public/general semantic ranking, which is
                // produced by this use case's other branch and never reaches this map.
                if (already == null) {
                    ranked.put(candidate.skillId(), new Ranked(3, candidate.score(), false));
                } else if (already.tier() == 3) {
                    ranked.put(candidate.skillId(),
                            new Ranked(3, Math.max(already.score(), candidate.score()), false));
                }
            }
        } else if (ranked.isEmpty()) {
            return List.of();
        }

        List<SkillCatalogService.CatalogRow> selected = ranked.keySet().stream()
                .map(rowById::get).toList();
        Map<String, SkillCardView> viewById = new LinkedHashMap<>();
        for (SkillCardView view : cards.assemble(selected, subject.userId())) {
            Ranked rank = ranked.get(view.id());
            viewById.put(view.id(), rank.personal()
                    ? view.withMyWhenToUseMatched(true)
                    : view);
        }
        return viewById.values().stream()
                .map(view -> new Hit(view, ranked.get(view.id()).score(),
                        ranked.get(view.id()).tier()))
                .sorted((left, right) -> {
                    int byTier = Integer.compare(left.tier(), right.tier());
                    if (byTier != 0) {
                        return byTier;
                    }
                    int byScore = Double.compare(right.score(), left.score());
                    return byScore != 0 ? byScore : left.card().id().compareTo(right.card().id());
                })
                .limit(limit)
                .toList();
    }

    private List<Hit> rank(List<RetrievalService.Candidate> candidates, SearchScope scope,
            SearchFilters filters, Caller caller, int limit, AuthenticatedSubject subject) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scoreOf = new LinkedHashMap<>();
        for (RetrievalService.Candidate candidate : candidates) {
            scoreOf.merge(candidate.skillId(), candidate.score(), Math::max);
        }
        List<SkillCatalogService.CatalogRow> visible =
                catalog.admissibleAmong(List.copyOf(scoreOf.keySet()), scope, filters, caller);
        List<SkillCardView> views = cards.assemble(visible, subject.userId(), false);
        return views.stream()
                .map(view -> new Hit(view, scoreOf.getOrDefault(view.id(), 0.0)))
                // Ties break on the id so two identical scores cannot swap between requests: the
                // ranking has to be reproducible or "the CLI and the console agree" is luck.
                .sorted((left, right) -> {
                    int byScore = Double.compare(right.score(), left.score());
                    return byScore != 0 ? byScore : left.card().id().compareTo(right.card().id());
                })
                .limit(limit)
                .toList();
    }

    /**
     * Names to keys, with the same readers the keyword path uses.
     *
     * @return null when a name names nothing — the caller answers "no results" rather than
     *         silently searching for less than it was asked to
     */
    private SearchFilters resolve(List<String> categoryNames, List<String> publicTagNames,
            List<String> myTagNames, List<String> labelKeys, String userId) {
        List<String> categoryKeys = new ArrayList<>();
        for (String name : nullToEmpty(categoryNames)) {
            String key = normalizedKey(name);
            String resolved = key == null ? null : categoryKeyOf(key);
            if (resolved == null) {
                return null;
            }
            categoryKeys.add(resolved);
        }
        List<String> publicTagKeys = new ArrayList<>();
        for (String name : nullToEmpty(publicTagNames)) {
            String key = normalizedKey(name);
            if (key == null) {
                return null;
            }
            publicTagKeys.add(key);
        }
        List<String> myTagIds = new ArrayList<>();
        for (String name : nullToEmpty(myTagNames)) {
            String key = normalizedKey(name);
            Optional<SkillTagService.Definition> definition =
                    key == null ? Optional.empty() : tags.findDefinitionByName(userId, key);
            if (definition.isEmpty()) {
                return null;
            }
            myTagIds.add(definition.get().id());
        }
        // **An unknown attribute word is a 400 on every path** (v66 TD §接口): the words come from
        // the vocabulary, so a misspelling is the caller's to fix, not "nothing was similar
        // enough". Categories and tags keep the empty-answer rule — a category key the console no
        // longer shows is a stale link, not a typo the caller made here.
        List<String> labelIds = new ArrayList<>();
        for (String key : nullToEmpty(labelKeys)) {
            labelIds.add(labels.definitionIdOf(key)
                    .orElseThrow(() -> new InvalidSearchRequestException(
                            "unknown label '" + key + "'")));
        }
        return new SearchFilters(categoryKeys, publicTagKeys, myTagIds, labelIds);
    }

    /**
     * A retired category is not filterable, exactly as on the public keyword path (PRD #7).
     *
     * <p><strong>Two spellings name the same shelf, and both arrive here.</strong> The API plane's
     * {@code category=} takes the display name (a person types it), the console's {@code tag=} takes
     * the key (a picker sends it). This resolver is shared by both, so it accepts either — reading
     * only one spelling made "keyword + category" on the console answer "分类或标签的名字不在词表里"
     * with no rows, a failure that looked like an empty vocabulary rather than a mismatch.
     */
    private String categoryKeyOf(String key) {
        for (SkillCategoryService.CategoryRow row : categories.all()) {
            if ("retired".equals(row.state())) {
                continue;
            }
            if (TagNames.key(TagNames.display(row.name())).equals(key) || row.key().equals(key)) {
                return row.key();
            }
        }
        return null;
    }

    private static String normalizedKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return TagNames.key(TagNames.display(raw));
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * Parse the wire's {@code semantic} flag.
     *
     * <p><strong>Absent means off.</strong> The product's default is semantic — the CLI sends
     * {@code semantic=true} unless asked otherwise (PRD #13) — but the transport's default is the
     * older behaviour, because this route is shared with listings and paging that predate v19 and
     * would otherwise change meaning without any client asking.
     */
    public static Optional<Boolean> parseSemantic(String wire) {
        if (wire == null) {
            return Optional.of(false);
        }
        return switch (wire) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> Optional.empty();
        };
    }
}
