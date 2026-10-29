package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.retrieval.EmbeddingSettings;
import com.skillmasterai.modules.retrieval.RetrievalService;
import com.skillmasterai.modules.skill.Caller;
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

    public SemanticSearchUseCase(NamespaceService namespaces, SkillCatalogService catalog,
            SkillCategoryService categories, SkillTagService tags, SkillCardAssembler cards,
            RetrievalService retrieval, EmbeddingSettings settings) {
        this.namespaces = namespaces;
        this.catalog = catalog;
        this.categories = categories;
        this.tags = tags;
        this.cards = cards;
        this.retrieval = retrieval;
        this.settings = settings;
    }

    /** @param score the cosine similarity the ranking used; 0–1, larger is closer */
    public record Hit(SkillCardView card, double score) {
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
            List<String> publicTagNames, List<String> myTagNames, int limit,
            List<String> excludedIds, AuthenticatedSubject subject) {
        if (!retrieval.enabled()) {
            // Switched off: answer without touching the database or enumerating an id set nobody
            // will use. The caller falls through to the keyword path with no note — this is the
            // configured state, not a degradation (PRD #8).
            return new Outcome(List.of(), null);
        }
        Caller caller = Callers.of(namespaces, subject);

        SearchFilters filters = resolve(categoryNames, publicTagNames, myTagNames, subject.userId());
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
        List<SkillCardView> views = cards.assemble(visible, subject.userId());
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
            List<String> myTagNames, String userId) {
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
        return new SearchFilters(categoryKeys, publicTagKeys, myTagIds);
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
