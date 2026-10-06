package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.PromotionOutcome;
import com.skillmasterai.modules.version.SkillVersionService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one version of one skill — the high-risk half of ADR 0031.
 *
 * <p><strong>There is no API-plane route to this, and that is the design.</strong> Publishing
 * changes what every agent reading through `/api/v1` will get, so it is reached only from the
 * browser plane, where the request carries a session cookie (HttpOnly) and a double-submit CSRF
 * token. A CLI that could publish would make "high risk" a matter of wording, which is why
 * {@code skillmaster submit} opens a link instead of doing this.
 *
 * <p>Readable, not owned: the namespace gate is {@link NamespaceService#readableNamespaceOf}, the
 * same predicate every other read uses, so publishing into someone else's namespace is not
 * expressible rather than merely refused.
 */
@Component
public class PublishSkillVersionUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final AuditLog audit;

    public PublishSkillVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            AuditLog audit) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.audit = audit;
    }

    /**
     * @param namespaceSlug the address's first segment; must be one the caller may read
     * @param number        which version to publish. Any non-discarded version, including one that
     *                      has been published before — that is rollback, and it is the same action
     * @return what happened, or empty when the address resolves to nothing the caller may publish
     * @throws com.skillmasterai.modules.version.VersionStateException when the version was discarded
     */
    @Transactional
    public Optional<PromotionOutcome> publish(String namespaceSlug, String name, int number,
            AuthenticatedSubject subject) {
        Optional<PromotionOutcome> promoted =
                namespaces.readableNamespaceOf(subject.userId(), namespaceSlug)
                        .flatMap(namespace -> versions.publishVersion(namespace.id(), name, number));

        // Written only when the pointer actually moved. Re-publishing what is already current
        // reaches the same state a second time; an audit row for it would read as a change that did
        // not happen, and the trail exists precisely to be believed.
        promoted.filter(PromotionOutcome::changed).ifPresent(outcome -> audit.record(
                new AuditEvent(subject.userId(), "publish", "skill", outcome.skillId(),
                        Map.of("name", name, "number", outcome.number(), "digest", outcome.digest()))));
        return promoted;
    }
}
