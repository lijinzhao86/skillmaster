package com.skillmasterai.api;

import com.skillmasterai.usecase.ListCategoriesUseCase;
import com.skillmasterai.usecase.ListPublicTagsUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two public vocabularies (v14).
 *
 * <p><strong>Both are reads and both count discoverable skills.</strong> The category list is one
 * response because there are ten words; the public-tag list is paged because it grows with use. The
 * field is {@code public_total} in both, and it is not the management page's {@code used_in} —
 * those two numbers mean different things and are named differently on purpose
 * (PRD §展示与计数口径).
 */
@RestController
@RequestMapping(path = "/api/v1")
class TagVocabularyController {

    private final ListCategoriesUseCase categories;
    private final ListPublicTagsUseCase publicTags;

    TagVocabularyController(ListCategoriesUseCase categories, ListPublicTagsUseCase publicTags) {
        this.categories = categories;
        this.publicTags = publicTags;
    }

    /** Enabled and stopped words, in vocabulary order; retired words are absent. */
    @GetMapping(path = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Categories> categories() {
        return ResponseEntity.ok(OrganizationResponses.Categories.of(categories.list()));
    }

    /** The words in use, count-descending then by name, with the cursor the CLI's {@code --all} walks. */
    @GetMapping(path = "/public-tags", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.PublicTags> publicTags(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(OrganizationResponses.PublicTags.of(
                publicTags.list(q, limit, cursor)));
    }
}
