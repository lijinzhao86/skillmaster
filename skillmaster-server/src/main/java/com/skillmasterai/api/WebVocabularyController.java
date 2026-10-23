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
 * The two vocabularies on the browser plane (v14).
 *
 * <p><strong>The console cannot hard-code the category list.</strong> The vocabulary changes by
 * migration, and a page that carried its own copy would show a word the platform had stopped or
 * hide one it had added. These are the same rows the API serves, at the same field names, so the
 * console's picker and the CLI's table cannot disagree.
 *
 * <p>The public-tag list is the picker's candidate source: the console shows the words that already
 * exist before a person types a new one, which is how a free-word vocabulary converges without a
 * synonym table.
 */
@RestController
@RequestMapping(path = "/web")
class WebVocabularyController {

    private final ListCategoriesUseCase categories;
    private final ListPublicTagsUseCase publicTags;

    WebVocabularyController(ListCategoriesUseCase categories, ListPublicTagsUseCase publicTags) {
        this.categories = categories;
        this.publicTags = publicTags;
    }

    /** Enabled and stopped words, in vocabulary order; retired words are absent. */
    @GetMapping(path = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.Categories> categories() {
        return ResponseEntity.ok(OrganizationResponses.Categories.of(categories.list()));
    }

    /**
     * The public words, count-descending then by name.
     *
     * <p>One generous page: a picker's candidate list is bounded by what a person can scan, and the
     * CLI's {@code --all} is the unbounded read.
     */
    @GetMapping(path = "/public-tags", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrganizationResponses.PublicTags> publicTags(
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(OrganizationResponses.PublicTags.of(
                publicTags.list(q, ListPublicTagsUseCase.MAX_LIMIT, null)));
    }
}
