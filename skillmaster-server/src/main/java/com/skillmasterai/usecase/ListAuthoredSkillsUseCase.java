package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillCatalogService;
import com.skillmasterai.usecase.model.AuthoredSkillSummary;
import java.util.List;
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
 * the caller's own is the only possible answer; taking one would invite a route that accepts somebody
 * else's slug. The detail and body endpoints do take one, and resolve it with the same rule.
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
        return catalog.authorPage(namespace.id(), LIMIT).stream()
                .map(row -> new AuthoredSkillSummary(namespace.slug(), row.name(), row.title(),
                        row.description(), row.visibility(), row.currentNumber(), row.currentDigest(),
                        row.drafts(), row.newestDraft(), row.latestSubmittedAt()))
                .toList();
    }
}
