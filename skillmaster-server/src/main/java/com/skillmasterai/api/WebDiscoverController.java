package com.skillmasterai.api;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.activity.ActivityEvent;
import com.skillmasterai.modules.activity.ActivityKinds;
import com.skillmasterai.modules.activity.SearchActivity;
import com.skillmasterai.modules.activity.SearchHit;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.skill.SearchScope;
import com.skillmasterai.usecase.DiscoverRecommendedUseCase;
import com.skillmasterai.usecase.SearchPublicSkillsUseCase;
import com.skillmasterai.usecase.SemanticSearchUseCase;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's discovery plane (v14).
 *
 * <p><strong>Two reads with different contracts.</strong> The results are a search: a keyword or a
 * category is required, the ordering is relevance, and the page size is six. The recommendation
 * stream is not a search: it has no input, its own keyset, and "not in my library" as its rule.
 * Keeping them apart is what lets each say what it is (TD Q4).
 */
@RestController
@RequestMapping(path = WebDiscoverController.BASE)
class WebDiscoverController {

    static final String BASE = "/web/discover";

    /** Ten rows, the same default the CLI uses, so "the same question gives the same answer" holds. */
    private static final int SEMANTIC_LIMIT = SemanticSearchUseCase.DEFAULT_LIMIT;

    private final SearchPublicSkillsUseCase search;
    private final DiscoverRecommendedUseCase recommended;
    private final SemanticSearchUseCase semantic;
    private final CardSignals cardSignals;
    private final ActivityLog activity;

    WebDiscoverController(SearchPublicSkillsUseCase search, DiscoverRecommendedUseCase recommended,
            SemanticSearchUseCase semantic, CardSignals cardSignals, ActivityLog activity) {
        this.search = search;
        this.recommended = recommended;
        this.semantic = semantic;
        this.cardSignals = cardSignals;
        this.activity = activity;
    }

    private OrganizationResponses.DiscoverResults withSignals(
            OrganizationResponses.DiscoverResults results) {
        var enriched = cardSignals.enrich(new SearchResponse(results.skills(), null, null));
        return new OrganizationResponses.DiscoverResults(enriched.skills(), results.p(),
                results.pageSize(), results.total(), results.note());
    }

    private OrganizationResponses.Recommended withSignals(
            OrganizationResponses.Recommended results) {
        var enriched = cardSignals.enrich(new SearchResponse(results.skills(), null, null));
        return new OrganizationResponses.Recommended(enriched.skills(), results.nextCursor());
    }

    /**
     * Public results: **a keyword is a search by meaning** (v19), a category alone is the literal
     * search paged at six.
     *
     * <p><strong>Why the split is on {@code q} and not on a flag.</strong> The console's search box
     * is the place people type what they want to do; a category chip is a browse. Asking the reader
     * to also pick a search mode would be one more control for something the input already says.
     * The CLI keeps its explicit {@code --semantic} because it also serves listings and paging.
     *
     * <p><strong>Degrading is a first-class answer.</strong> When the provider is down the literal
     * search runs instead and the response says so in {@code note} — a page that quietly answered a
     * different question would be worse than one that says which question it answered.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.DiscoverResults> discover(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> tag,
            @RequestParam(required = false) List<String> label,
            @RequestParam(required = false) Integer p, Authentication caller) {
        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        if (q != null && !q.isBlank() && semantic.enabled()) {
            SemanticSearchUseCase.Outcome outcome = semantic.search(q, SearchScope.PUBLIC, tag,
                    null, null, label, SEMANTIC_LIMIT, java.util.List.of(), subject);
            if (!outcome.degraded()) {
                // **"Nothing was similar enough" is the page's sentence, not this route's.** The
                // console owns that empty state (the design writes it), so sending our own copy too
                // would give one state two spellings and let them drift. What only the server knows
                // — a filter word that names nothing — is what comes back as the note.
                recordSearch(subject, q, tag, null, outcome.hits().stream()
                        .map(hit -> new SearchHit(hit.card().id(),
                                hit.card().namespace() + "/" + hit.card().name()))
                        .toList(), null);
                return ResponseEntity.ok(withSignals(
                        OrganizationResponses.DiscoverResults.semantic(outcome.hits(),
                                outcome.emptyNote())));
            }
            SearchPublicSkillsUseCase.WebResult fallback = search.web(q, tag, label, p, subject);
            recordSearch(subject, q, tag, p, hitsOf(fallback.cards()), fallback.total());
            // Three sentences, because the literal search is what ran and it can find nothing: then
            // "下面是按字面找到的结果" would describe rows that are not there.
            //
            // **The choice is `total`, not this page's rows.** Asking for a page past the end of a
            // non-empty result gives an empty page with a non-zero total; "no match" there would
            // contradict the total sitting beside it, and the client is about to correct the page
            // number anyway.
            String ending = fallback.total() == 0 ? "按字面找也没有匹配的 skill。"
                    : fallback.cards().isEmpty() ? "" : "下面是按字面找到的结果。";
            return ResponseEntity.ok(withSignals(OrganizationResponses.DiscoverResults.of(fallback,
                    "语义检索暂不可用。" + ending)));
        }
        SearchPublicSkillsUseCase.WebResult literal = search.web(q, tag, label, p, subject);
        if (q == null || q.isBlank()) {
            recordBrowse(subject, tag, literal.cards().size());
        } else {
            recordSearch(subject, q, tag, p, hitsOf(literal.cards()), literal.total());
        }
        return ResponseEntity.ok(withSignals(OrganizationResponses.DiscoverResults.of(literal)));
    }

    private void recordSearch(AuthenticatedSubject subject, String q, List<String> tag, Integer p,
            List<SearchHit> results, Long total) {
        String target = ActivityTargets.cut(q);
        activity.search(new SearchActivity(subject.userId(), target, filters(tag), total, results,
                p != null && p > 1, Timestamps.now()));
    }

    private void recordBrowse(AuthenticatedSubject subject, List<String> tag, int returned) {
        activity.record(new ActivityEvent(subject.userId(), null, ActivityKinds.BROWSE, null, null,
                null, null, null, Map.of("returned", returned, "filters", filters(tag)),
                Timestamps.now()));
    }

    private static Map<String, Object> filters(List<String> tag) {
        return Map.of("scope", "public", "tag", tag == null ? List.of() : tag);
    }

    private static List<SearchHit> hitsOf(
            List<com.skillmasterai.usecase.model.SkillCardView> cards) {
        return cards.stream()
                .map(card -> new SearchHit(card.id(), card.namespace() + "/" + card.name()))
                .toList();
    }

    /** One bounded batch. The cursor is opaque; the console never shows it. */
    @GetMapping(path = "/recommended", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Recommended> recommended(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, Authentication caller) {
        AuthenticatedSubject subject = AuthenticatedSubject.ofSession(caller.getName());
        DiscoverRecommendedUseCase.Result result = recommended.recommended(cursor, limit, subject);
        recordBrowse(subject, null, result.cards().size());
        return ResponseEntity.ok(withSignals(OrganizationResponses.Recommended.of(result)));
    }
}
