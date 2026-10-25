package com.skillmasterai.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.usecase.DiscoverRecommendedUseCase;
import com.skillmasterai.usecase.LibraryListUseCase;
import com.skillmasterai.usecase.ListPublicTagsUseCase;
import java.util.List;

/**
 * The v14 response bodies, in one place because they are one vocabulary.
 *
 * <p><strong>Field names are the contract and are shared by the CLI and the browser.</strong>
 * {@code public_total} and {@code used_in} are deliberately different names: they count different
 * things, and a client that swapped them would show a number that is true about something else. The
 * private layer's rows are addressed by id, the public one by key, because only the private one has
 * a definition that can be renamed (PRD §展示与计数口径).
 *
 * <p>Nested records rather than a record per file: these shapes are only ever produced by their own
 * factory and read by Jackson, and there are six of them.
 */
final class OrganizationResponses {

    private OrganizationResponses() {
    }

    /* ---- three groups ---- */

    record SkillTags(Category category, List<PublicTag> publicTags, MyTags myTags,
            Library library) {
        static SkillTags of(SkillTagService.TagGroups groups) {
            return new SkillTags(
                    groups.category() == null ? null
                            : new Category(groups.category().key(), groups.category().name()),
                    groups.publicTags().stream()
                            .map(tag -> new PublicTag(tag.key(), tag.name())).toList(),
                    new MyTags(groups.myTags().readable(), groups.myTags().reason(),
                            groups.myTags().tags().stream()
                                    .map(tag -> new MyTag(tag.id(), tag.name(),
                                            tag.description()))
                                    .toList()),
                    new Library(groups.library().source(), groups.library().active(),
                            groups.library().joinable(), groups.library().canRemove()));
        }
    }

    /**
     * The information page's join/leave button (v14).
     *
     * @param source    created / shared / joined, or null for "not in my library"
     * @param joinable  whether "加入" would change anything
     * @param canRemove displayed source is "joined"
     */
    record Library(String source, boolean active, boolean joinable, boolean canRemove) {
    }

    /**
     * A write's answer when the same request may be a no-op.
     *
     * <p>{@code changed=false} is not an error: joining a skill already in the library, attaching a
     * word already there, or setting the shelf a skill is on are all states the caller asked for.
     * The client turns it into "已经在你的库里。"/"已经贴过"/"已经是这个分类。" rather than a second
     * audit row or a misleading success message.
     */
    record Changed(boolean changed) {
    }

    record Category(String key, String name) {
    }

    record PublicTag(String key, String name) {
    }

    record MyTag(String id, String name, String description) {
    }

    /**
     * @param readable false when the group must not be drawn at all
     * @param reason   null / {@code not_in_library} / {@code dormant}
     */
    record MyTags(boolean readable, String reason, List<MyTag> tags) {
    }

    /* ---- vocabularies ---- */

    record Categories(List<CategoryRow> categories) {
        static Categories of(List<com.skillmasterai.modules.skill.SkillCategoryService.CategoryRow> rows) {
            return new Categories(rows.stream()
                    .map(row -> new CategoryRow(row.key(), row.name(), row.state(),
                            row.publicTotal()))
                    .toList());
        }
    }

    record CategoryRow(String key, String name, String state, long publicTotal) {
    }

    record PublicTags(List<PublicTagRow> tags, String nextCursor) {
        static PublicTags of(ListPublicTagsUseCase.Page page) {
            return new PublicTags(page.tags().stream()
                    .map(row -> new PublicTagRow(row.key(), row.name(), row.publicTotal()))
                    .toList(), page.nextCursor());
        }
    }

    record PublicTagRow(String key, String name, long publicTotal) {
    }

    record MyTagsList(List<MyTagRow> tags) {
        static MyTagsList of(List<SkillTagService.MyTagRow> rows) {
            return new MyTagsList(rows.stream()
                    .map(row -> new MyTagRow(row.id(), row.name(), row.description(),
                            row.usedIn()))
                    .toList());
        }
    }

    record MyTagRow(String id, String name, String description, long usedIn) {
    }

    /** The one sentence a definition write returns; nothing else on this layer needs echoing. */
    record Definition(String id, String name, String description) {
        static Definition of(SkillTagService.Definition definition) {
            return new Definition(definition.id(), definition.name(),
                    definition.description());
        }
    }

    /* ---- web listings ---- */

    record WebLibrary(List<SearchResponse.Card> skills, int page, int pageSize, long totalEntries,
            long effectiveEntries, long filteredTotal) {
        static WebLibrary of(LibraryListUseCase.Result result, int page) {
            return new WebLibrary(
                    result.cards().stream().map(SearchResponse.Card::of).toList(),
                    page, LibraryListUseCase.WEB_PAGE_SIZE,
                    result.counts().totalEntries(), result.counts().effectiveEntries(),
                    result.counts().filteredTotal());
        }
    }

    /**
     * The discovery results: a page-number pager's whole picture.
     *
     * @param total the filtered result size, not the page's size
     */
    /**
     * One route, two shapes, and the paging fields are what tell them apart (v19).
     *
     * <p><strong>A search by meaning has no pages.</strong> Its answer is "the ten most similar",
     * not "page 1 of N": similarity is not a page boundary, and there is no total to divide. So a
     * semantic answer omits {@code p}, {@code page_size} and {@code total}, while a keyword answer
     * — which is what a category-only browse still is, and what a degraded semantic search falls
     * back to — carries all three. A client draws the pager when {@code total} is present.
     *
     * @param note why this is not the answer that was asked for: the caller's filter named something
     *             that does not exist, nothing was similar enough, or semantic search was
     *             unavailable and these rows came from the literal search. Null on an ordinary
     *             answer, and omitted from the JSON when null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DiscoverResults(List<SearchResponse.Card> skills, Integer p, Integer pageSize, Long total,
            String note) {
        static DiscoverResults of(
                com.skillmasterai.usecase.SearchPublicSkillsUseCase.WebResult result) {
            return of(result, null);
        }

        static DiscoverResults of(
                com.skillmasterai.usecase.SearchPublicSkillsUseCase.WebResult result, String note) {
            return new DiscoverResults(
                    result.cards().stream().map(SearchResponse.Card::of).toList(),
                    result.page(), result.pageSize(), result.total(), note);
        }

        /** The semantic shape: no page, no total, the score carried on each card. */
        static DiscoverResults semantic(
                List<com.skillmasterai.usecase.SemanticSearchUseCase.Hit> hits, String note) {
            return new DiscoverResults(
                    hits.stream().map(hit -> SearchResponse.Card.of(hit.card(), hit.score())).toList(),
                    null, null, null, note);
        }
    }

    record Recommended(List<SearchResponse.Card> skills, String nextCursor) {
        static Recommended of(DiscoverRecommendedUseCase.Result result) {
            return new Recommended(
                    result.cards().stream().map(SearchResponse.Card::of).toList(),
                    result.nextCursor());
        }
    }

    /* ---- web tag tabs ---- */

    record WebMyTags(List<WebMyTagRow> tags) {
        static WebMyTags of(List<SkillTagService.MyTagRow> rows) {
            return new WebMyTags(rows.stream()
                    .map(row -> new WebMyTagRow(row.id(), row.name(), row.description(),
                            row.usedIn(), row.dormantIn()))
                    .toList());
        }
    }

    /** @param dormantIn attachments the person cannot currently see; the delete dialog's M */
    record WebMyTagRow(String id, String name, String description, long usedIn,
            long dormantIn) {
    }

    record WebPublicTags(List<WebPublicTagRow> tags) {
        static WebPublicTags of(List<SkillTagService.ManagedPublicTagRow> rows) {
            return new WebPublicTags(rows.stream()
                    .map(row -> new WebPublicTagRow(row.key(), row.name(), row.usedIn()))
                    .toList());
        }
    }

    record WebPublicTagRow(String key, String name, long usedIn) {
    }

}
