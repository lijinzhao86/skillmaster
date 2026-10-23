package com.skillmasterai.api;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.usecase.DiscoverRecommendedUseCase;
import com.skillmasterai.usecase.SearchPublicSkillsUseCase;
import java.util.List;
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

    private final SearchPublicSkillsUseCase search;
    private final DiscoverRecommendedUseCase recommended;

    WebDiscoverController(SearchPublicSkillsUseCase search, DiscoverRecommendedUseCase recommended) {
        this.search = search;
        this.recommended = recommended;
    }

    /** Public results: a keyword or a category, genre-relevance ordered, six per page. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.DiscoverResults> discover(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> tag,
            @RequestParam(required = false) Integer p, Authentication caller) {
        return ResponseEntity.ok(OrganizationResponses.DiscoverResults.of(search.web(q, tag, p,
                AuthenticatedSubject.ofSession(caller.getName()))));
    }

    /** One bounded batch. The cursor is opaque; the console never shows it. */
    @GetMapping(path = "/recommended", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Recommended> recommended(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit, Authentication caller) {
        return ResponseEntity.ok(OrganizationResponses.Recommended.of(
                recommended.recommended(cursor, limit,
                        AuthenticatedSubject.ofSession(caller.getName()))));
    }
}
