package com.skillmasterai.usecase;

import com.skillmasterai.common.CursorCodec;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.search.InvalidSearchRequestException;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillCatalogService;
import com.skillmasterai.modules.skill.SkillCategoryService;
import com.skillmasterai.modules.skill.SkillLibraryService;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.usecase.model.SkillCardView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "My library", both listings (v14, ADR 0082).
 *
 * <p><strong>One use case, two transports.</strong> The API pages by keyset cursor and takes names
 * for its filters; the browser pages by {@code p} with a fixed size of 10 and takes ids/keys. The
 * query itself is one — {@link SkillLibraryService} — because the result set and the predicates are
 * one; only how a page is cut and how a filter is addressed differ.
 *
 * <p><strong>The three counters are computed even when the filters are unusable.</strong> A filter
 * the caller typed that names nothing yields an empty page, not an empty library: the page still
 * has to tell "no matches" from "nothing here at all" (PRD §展示与计数口径).
 *
 * <p><strong>The default is the consumable library.</strong> {@code show=live} is what the CLI
 * lists; the console asks for {@code all} or {@code deleted} explicitly. {@code all} is live +
 * draft + offline, and the deleted list is the caller's own soft-deleted skills only.
 */
@Component
public class LibraryListUseCase {

    public static final int API_DEFAULT_LIMIT = 50;
    public static final int API_MAX_LIMIT = 100;
    public static final int WEB_PAGE_SIZE = 10;
    /** The sizes the console's 「每页」 control offers (2026-11-01). Anything else is a bad request. */
    public static final List<Integer> WEB_PAGE_SIZES = List.of(10, 20, 50);

    private static final List<String> SHOWS =
            List.of("live", "draft", "offline", "all", "deleted");
    private static final List<String> SOURCES = List.of("created", "shared", "joined");

    private final NamespaceService namespaces;
    private final SkillLibraryService library;
    private final SkillTagService tags;
    private final SkillCategoryService categories;
    private final SkillCardAssembler cards;

    public LibraryListUseCase(NamespaceService namespaces, SkillLibraryService library,
            SkillTagService tags, SkillCategoryService categories, SkillCardAssembler cards) {
        this.namespaces = namespaces;
        this.library = library;
        this.tags = tags;
        this.categories = categories;
        this.cards = cards;
    }

    /**
     * The API plane: cursor pagination, filters given by the names a person types.
     *
     * @param myTags private tag names; an unknown name selects nothing rather than widening, which
     *               is what "a filter narrows" means
     */
    @Transactional(readOnly = true)
    public Result api(String q, List<String> namespaceSlugs, List<String> categoryNames,
            List<String> myTags, List<String> publicTags, List<String> sources, String sort,
            String show, Integer limit, String cursor, List<String> excludedIds,
            AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        List<String> excluded = excludedIds == null ? List.of() : excludedIds;
        List<String> requestedNamespaces =
                namespaceSlugs == null ? List.of() : namespaceSlugs;
        List<String> namespaceIds = new ArrayList<>();
        for (String slug : requestedNamespaces) {
            namespaces.bySlug(slug).map(Namespace::id).ifPresent(namespaceIds::add);
        }
        // A namespace filter that resolved to nothing selects nothing. Dropping it would widen the
        // listing to everything, which is the opposite of what the parameter means.
        boolean unknownNamespace = !requestedNamespaces.isEmpty() && namespaceIds.isEmpty();
        String normalShow = show == null ? "live" : show;
        requireKnown(normalShow, SHOWS, "show");
        // TD §接口: `show=deleted` rejects the three tag filters rather than ignoring them. The
        // console may drop them from the address; an API caller that asked must be told.
        if ("deleted".equals(normalShow)
                && ((categoryNames != null && !categoryNames.isEmpty())
                    || (myTags != null && !myTags.isEmpty())
                    || (publicTags != null && !publicTags.isEmpty()))) {
            throw new InvalidSearchRequestException(
                    "show=deleted accepts no category, my_tag or public_tag filter");
        }
        boolean byName = "name".equals(requireSort(sort));
        List<String> normalSources = sources == null ? List.of() : sources.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();
        for (String source : normalSources) {
            requireKnown(source, SOURCES, "source");
        }

        List<String> categoryKeys = new ArrayList<>();
        boolean unknown = false;
        for (String name : categoryNames == null ? List.<String>of() : categoryNames) {
            String key = categoryKey(name);
            if (key == null) {
                unknown = true;
            } else {
                categoryKeys.add(key);
            }
        }

        List<String> myTagIds = new ArrayList<>();
        for (String name : myTags == null ? List.<String>of() : myTags) {
            String key = normalizedKey(name);
            Optional<SkillTagService.Definition> definition =
                    key == null ? Optional.empty()
                            : tags.findDefinitionByName(subject.userId(), key);
            if (definition.isEmpty()) {
                unknown = true;
            } else {
                myTagIds.add(definition.get().id());
            }
        }

        List<String> publicTagKeys = new ArrayList<>();
        for (String name : publicTags == null ? List.<String>of() : publicTags) {
            String key = normalizedKey(name);
            if (key == null) {
                unknown = true;
            } else {
                publicTagKeys.add(key);
            }
        }

        // The deleted list keeps only the caller's own skills, so a source filter that names any
        // other source selects nothing rather than being silently dropped (PRD #45).
        if ("deleted".equals(normalShow)
                && normalSources.stream().anyMatch(source -> !"created".equals(source))) {
            unknown = true;
        }

        int pageSize;
        if (limit == null) {
            pageSize = API_DEFAULT_LIMIT;
        } else if (limit < 1) {
            throw new InvalidSearchRequestException("limit must be at least 1, was " + limit);
        } else {
            pageSize = Math.min(limit, API_MAX_LIMIT);
        }
        List<String> afterKey = resumeKey(cursor, byName);
        if (unknown || unknownNamespace) {
            return new Result(List.of(), null, Counts.NONE);
        }

        // One more than the page, so "is there another page" is answered without a second query.
        // **The exclusion widens that window by its own size** (v30): at most that many rows can be
        // dropped, so a page's worth plus one always survives to answer the cursor honestly.
        SkillLibraryService.Query query = new SkillLibraryService.Query(q, namespaceIds, categoryKeys,
                myTagIds, publicTagKeys, normalSources, normalShow, byName, afterKey, null,
                pageSize + excluded.size() + 1);
        List<SkillCatalogService.CatalogRow> rows = library.page(query, caller);
        if (!excluded.isEmpty()) {
            var excludedSet = java.util.Set.copyOf(excluded);
            rows = rows.stream().filter(row -> !excludedSet.contains(row.id())).toList();
        }
        String next = nextCursor(rows, pageSize, byName);
        List<SkillCatalogService.CatalogRow> page = rows.stream().limit(pageSize).toList();
        return new Result(cards.assemble(page, subject.userId()), next, Counts.NONE);
    }

    /**
     * The browser plane: page numbers, fixed size, filters given by id/key.
     *
     * @param myTagIds definition ids; one belonging to somebody else simply selects nothing, which
     *                 is also the privacy answer
     */
    @Transactional(readOnly = true)
    public Result web(String q, List<String> categoryKeys, List<String> myTagIds,
            List<String> publicTagKeys, String sort, String show, Integer page, Integer per,
            AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        String normalShow = show == null ? "all" : show;
        if (!"all".equals(normalShow) && !"deleted".equals(normalShow)) {
            throw new InvalidSearchRequestException("show must be 'all' or 'deleted'");
        }
        boolean byName = "name".equals(requireSort(sort));
        int pageSize = per == null ? WEB_PAGE_SIZE : per;
        if (!WEB_PAGE_SIZES.contains(pageSize)) {
            throw new InvalidSearchRequestException("per must be one of 10, 20, 50, was " + per);
        }
        int wanted = page == null || page < 1 ? 1 : page;
        // Computed wide: (2^31-1) * 50 overflows an int and would reach PostgreSQL as a negative
        // OFFSET — a 500 for what is just an unreachable page.
        long wide = ((long) wanted - 1) * pageSize;
        if (wide > Integer.MAX_VALUE) {
            throw new InvalidSearchRequestException("page is too large");
        }
        int offset = (int) wide;

        SkillLibraryService.Query query = new SkillLibraryService.Query(q, List.of(),
                categoryKeys == null ? List.of() : categoryKeys,
                myTagIds == null ? List.of() : myTagIds,
                publicTagKeys == null ? List.of() : publicTagKeys,
                List.of(), normalShow, byName, null, offset, pageSize);
        List<SkillCatalogService.CatalogRow> rows = library.page(query, caller);
        long filtered = library.filteredTotal(query, caller);
        long total = library.totalEntries(caller);
        long effective = library.effectiveEntries(caller);
        return new Result(cards.assemble(rows, subject.userId()), null,
                new Counts(total, effective, filtered));
    }

    private String categoryKey(String name) {
        String key = normalizedKey(name);
        if (key == null) {
            return null;
        }
        for (SkillCategoryService.CategoryRow row : categories.all()) {
            // A retired word is not a filter: PRD #7 says it disappears from the picker and the
            // vocabulary, and "不可筛" means the name resolves to nothing, not to an empty page.
            if (!"retired".equals(row.state()) && normalizedKey(row.name()).equals(key)) {
                return row.key();
            }
        }
        return null;
    }

    private static String normalizedKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return com.skillmasterai.modules.skill.TagNames.key(
                com.skillmasterai.modules.skill.TagNames.display(raw));
    }

    private static String requireSort(String sort) {
        String value = sort == null ? "recent" : sort;
        if (!"recent".equals(value) && !"name".equals(value)) {
            throw new InvalidSearchRequestException("sort must be 'recent' or 'name'");
        }
        return value;
    }

    private static void requireKnown(String value, List<String> known, String parameter) {
        if (!known.contains(value)) {
            throw new InvalidSearchRequestException(
                    "'" + value + "' is not a value of " + parameter);
        }
    }

    private static List<String> resumeKey(String cursor, boolean byName) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String ordering = ordering(byName);
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor)
                .orElseThrow(() -> new InvalidSearchRequestException(
                        "the cursor is not one this version understands"));
        if (!decoded.ordering().equals(ordering) || decoded.key().size() != 2) {
            throw new InvalidSearchRequestException(
                    "the cursor belongs to a different ordering");
        }
        if (decoded.key().get(1).isBlank()) {
            throw new InvalidSearchRequestException(
                    "the cursor is not one this version understands");
        }
        if (byName) {
            if (decoded.key().get(0).isBlank()) {
                throw new InvalidSearchRequestException(
                        "the cursor is not one this version understands");
            }
        } else {
            // The keyset compares updated_at as text, which is only sound for the canonical
            // spelling Timestamps writes. A parseable-but-different instant would move the page
            // boundary with no error anywhere (the same rule SkillSearchService applies).
            try {
                if (!com.skillmasterai.common.Timestamps.format(
                        com.skillmasterai.common.Timestamps.parse(decoded.key().get(0)))
                        .equals(decoded.key().get(0))) {
                    throw new InvalidSearchRequestException(
                            "the cursor is not one this version understands");
                }
            } catch (java.time.format.DateTimeParseException e) {
                throw new InvalidSearchRequestException(
                        "the cursor is not one this version understands");
            }
        }
        return decoded.key();
    }

    private static String nextCursor(List<SkillCatalogService.CatalogRow> rows, int pageSize,
            boolean byName) {
        if (rows.size() <= pageSize) {
            return null;
        }
        SkillCatalogService.CatalogRow last = rows.get(pageSize - 1);
        return CursorCodec.encode(ordering(byName),
                List.of(byName ? last.name() : last.updatedAt(), last.id()));
    }

    private static String ordering(boolean byName) {
        return byName ? "library:name" : "library:recent";
    }

    /**
     * @param counts total / effective / filtered. The API does not send them; the browser does,
     *               because its three empty states are told apart by these three numbers
     */
    public record Result(List<SkillCardView> cards, String nextCursor, Counts counts) {
        public Result {
            cards = List.copyOf(cards);
        }
    }

    public record Counts(long totalEntries, long effectiveEntries, long filteredTotal) {
        public static final Counts NONE = new Counts(0, 0, 0);
    }
}
