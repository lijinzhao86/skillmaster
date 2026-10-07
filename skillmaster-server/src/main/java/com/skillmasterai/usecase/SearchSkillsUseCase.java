package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.search.SearchRequest;
import com.skillmasterai.modules.search.SkillCard;
import com.skillmasterai.modules.search.SkillSearchService;
import com.skillmasterai.modules.search.SkillSearchService.SearchPage;
import java.util.List;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Searches the caller's own skills.
 *
 * <p>Another thin one, and deliberately so: the interesting part of search is the query, which
 * belongs to M8, and the ownership rule, which belongs to M4. What the use-case layer adds is the
 * meeting — §3.4 requires the result to be confined to namespaces the caller may read, and M8
 * cannot work that out for itself.
 *
 * <p>Read-only, so the transaction exists for consistency rather than for atomicity: the page and
 * the cursor that follows from it have to come from the same snapshot, or a publish landing
 * mid-page could place a row on both sides of the boundary.
 */
@Component
public class SearchSkillsUseCase {

    private final NamespaceService namespaces;
    private final SkillSearchService search;

    public SearchSkillsUseCase(NamespaceService namespaces, SkillSearchService search) {
        this.namespaces = namespaces;
        this.search = search;
    }

    /**
     * @throws com.skillmasterai.modules.search.InvalidSearchRequestException for a limit below one
     *         or a cursor this version cannot read — both are the caller's to fix, and both are a
     *         400 rather than a silently repaired request
     */
    @Transactional(readOnly = true)
    public SkillListing search(SearchRequest request, AuthenticatedSubject subject) {
        // What the caller may see is two things since ADR 0034: everything in the namespace they
        // own, and the skills shared with them. It travels as one Caller, and M7 applies both halves
        // in the statement that finds the rows — so this layer decides nothing about access.
        Namespace own = namespaces.personalNamespaceOf(subject.userId());
        Caller caller = new Caller(subject.userId(), own.id());

        // §4.2: `namespace` narrows within what the caller may already see and is never a way to
        // widen. It is resolved to ids here, and a namespace they may see nothing in selects nothing
        // rather than somebody else's skills. A slug that names nothing at all is the same nothing —
        // the caller asked about a namespace, not about their right to it.
        //
        // **Each slug is resolved on its own, and one that resolves to nothing contributes nothing.**
        // The alternative — refuse the whole request when any member is unknown — would make
        // `--namespace mine --namespace typo` answer with an empty page, which reads as "you have no
        // skills" rather than as "one of those names is wrong". Composing per element is also the
        // only rule that keeps the safety property obvious: the filter can remove rows from what the
        // access rule allowed, and can never add one, whichever names are in it.
        List<String> filterIds = new ArrayList<>();
        for (String slug : request.namespaceSlugs()) {
            namespaces.bySlug(slug).map(Namespace::id).ifPresent(filterIds::add);
        }
        if (!request.namespaceSlugs().isEmpty() && filterIds.isEmpty()) {
            // Every name given was unknown, so the answer is nothing — said by the same empty page
            // the single-slug case produced, rather than by a 404 that would claim the caller is
            // looking at something that is not there.
            return new SkillListing(new SkillSearchService.SearchPage(List.of(), null), Map.of());
        }
        SearchPage page = search.search(request, caller, filterIds);

        // The slugs for whatever namespaces the page turned out to hold. One batch query, after the
        // page rather than before it, because before it there is nothing to ask about.
        Map<String, String> slugs = namespaces.slugsOf(
                page.skills().stream().map(SkillCard::namespaceId).distinct().toList());
        return new SkillListing(page, slugs);
    }
}
