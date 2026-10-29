package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.retrieval.EmbeddingIndex;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.PromotionOutcome;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import java.util.HashMap;
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
 * {@code skmr submit} opens a link instead of doing this.
 *
 * <p>Readable, not owned: the namespace gate is {@link NamespaceService#readableNamespaceOf}, the
 * same predicate every other read uses, so publishing into someone else's namespace is not
 * expressible rather than merely refused.
 */
@Component
public class PublishSkillVersionUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AuditLog audit;
    private final EmbeddingIndex embeddings;

    public PublishSkillVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, AuditLog audit, EmbeddingIndex embeddings) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.audit = audit;
        this.embeddings = embeddings;
    }

    /**
     * @param namespaceSlug the address's first segment; must be one the caller may read
     * @param pin           which version to publish, by name or by digest. Any non-discarded version,
     *                      including one that has been published before — that is rollback, and it
     *                      is the same action
     * @param releaseName   the number to assign when the target is a draft without one; null for a
     *                      target that already has a number (ADR 0055). Supplying a different one
     *                      is refused rather than ignored
     * @return what happened, or empty when the address resolves to nothing the caller may publish
     * @throws com.skillmasterai.modules.version.VersionStateException when the version was discarded
     */
    @Transactional
    public Optional<PromotionOutcome> publish(String namespaceSlug, String name, VersionPin pin,
            String releaseName, AuthenticatedSubject subject) {
        Optional<PromotionOutcome> promoted = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                                Callers.of(namespaces, subject))
                        .flatMap(access -> versions.publishVersion(access, pin, releaseName)));

        // Written only when the pointer actually moved. Re-publishing what is already current
        // reaches the same state a second time; an audit row for it would read as a change that did
        // not happen, and the trail exists precisely to be believed.
        //
        // The detail map is built rather than `Map.of` because a version need not have a name
        // (ADR 0033) and the map refuses nulls. The digest is always there and identifies the version
        // exactly; the name is recorded when there is one because it is what a person recognises.
        promoted.filter(PromotionOutcome::changed).ifPresent(outcome -> {
            // The one place a published skill's text changes, so the one place the index is told
            // about it (v19). **It calls the provider, and it is inside this transaction** — that
            // is the decision, not an oversight: a publish that commits has its vector, and one
            // whose embedding fails rolls back everything, pointer and audit included.
            embeddings.refresh(outcome.skillId());
            Map<String, Object> detail = new HashMap<>();
            detail.put("name", name);
            detail.put("digest", outcome.digest());
            if (outcome.version() != null) {
                detail.put("version", outcome.version());
            }
            audit.record(new AuditEvent(subject.userId(), "publish", "skill", outcome.skillId(),
                    detail));
        });
        return promoted;
    }
}
