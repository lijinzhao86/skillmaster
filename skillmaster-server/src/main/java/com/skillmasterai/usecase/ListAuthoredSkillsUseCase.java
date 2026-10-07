package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillCatalogService;
import com.skillmasterai.usecase.model.AuthoredSkillSummary;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the caller has submitted, drafts included (ADR 0031).
 *
 * <p>The consumption plane lists a namespace's skills through M8, which ranks them by what a query
 * matched. This lists the same table for their owner, so there is nothing to match: no query text, no
 * weights, no cursor. What it does carry is the two facts the author came for — what is waiting, and
 * whether anything is live at all — which is the shape {@link SkillCatalogService#authorPage} exists
 * to produce.
 *
 * <p><strong>Which namespace is not a parameter.</strong> There is no address here to name one, so
 * the caller's own is where it starts; taking one would invite a route that accepts somebody else's
 * slug. The detail and body endpoints do take one, and resolve it with the same rule.
 *
 * <p>What it returns is no longer one namespace's worth (ADR 0034): a skill shared with the caller as
 * an {@code editor} is theirs to add versions to, so it belongs on the page they work from. Every row
 * therefore carries its own namespace, resolved here rather than assumed.
 *
 * <p>The limit is fixed rather than client-supplied: v1 gives an author as many skills as they have
 * submitted, and a page that silently shows the first N of them is worse than one that shows all.
 * When that stops being true the answer is a cursor, not a bigger number.
 */
@Component
public class ListAuthoredSkillsUseCase {

    private static final int LIMIT = 500;

    private final NamespaceService namespaces;
    private final SkillCatalogService catalog;

    public ListAuthoredSkillsUseCase(NamespaceService namespaces, SkillCatalogService catalog) {
        this.namespaces = namespaces;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<AuthoredSkillSummary> list(AuthenticatedSubject subject) {
        Namespace namespace = namespaces.personalNamespaceOf(subject.userId());
        Caller caller = new Caller(subject.userId(), namespace.id());
        List<SkillCatalogService.AuthorRow> rows = catalog.authorPage(LIMIT, caller);

        // Each row's *own* namespace, not the caller's — since ADR 0034 this listing also holds the
        // skills shared with them as an editor, and labelling one of those with the caller's slug
        // would give it an address that resolves to a different skill of theirs, or to none. One
        // batch rather than a lookup per row, and the same shape as the consumption listing's.
        Map<String, String> slugs = namespaces.slugsOf(
                rows.stream().map(SkillCatalogService.AuthorRow::namespaceId).toList());

        return rows.stream()
                .map(row -> new AuthoredSkillSummary(
                        slugs.getOrDefault(row.namespaceId(), row.namespaceId()),
                        row.name(), row.title(), row.description(), row.visibility(),
                        row.currentVersion(), row.currentDigest(), row.drafts(),
                        row.newestDraftVersion(), row.newestDraftDigest(), row.latestSubmittedAt()))
                .toList();
    }
}
