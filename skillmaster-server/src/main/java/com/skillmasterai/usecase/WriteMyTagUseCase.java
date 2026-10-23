package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.SkillTagService.Definition;
import com.skillmasterai.modules.skill.TagNames;
import com.skillmasterai.modules.skill.TagNotAttachedException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaching and detaching a private tag on one skill (v14, ADR 0083).
 *
 * <p><strong>A private tag belongs to the library, not to the skill.</strong> A visible skill the
 * caller has not added answers 403 with a way out; an entry that is dormant answers 409. That split
 * is the whole of {@link Organize}, and both writes use it — a tag that cannot be attached from this
 * state cannot be detached from it either.
 *
 * <p><strong>Attaching may create the definition.</strong> The create goes through
 * {@link EnsureMyTagDefinitionUseCase}, which takes the account's advisory lock first; the skill's
 * lock is taken after it. The fixed order — user, then skill — is what keeps two attaches on the
 * same skill from deadlocking with two creates for the same person (TD §并发与上限).
 */
@Component
public class WriteMyTagUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillTagService tags;
    private final EnsureMyTagDefinitionUseCase ensure;
    private final AuditLog audit;

    public WriteMyTagUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillTagService tags, EnsureMyTagDefinitionUseCase ensure, AuditLog audit) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.tags = tags;
        this.ensure = ensure;
        this.audit = audit;
    }

    /**
     * @return empty for an address the caller may not see; otherwise whether the tag was attached —
     *         false means it was already on this skill
     */
    @Transactional
    public Optional<Boolean> attach(String namespaceSlug, String name, String rawTag,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var standing = resolved.get().standing();
        requireWritable(standing, namespaceSlug);

        // Definition first (the user lock), then the skill lock: the fixed order.
        Definition definition = ensure.ensureForAttach(subject.userId(), rawTag);
        tags.lockTagsOf(standing.skillId());

        if (tags.attachmentExists(subject.userId(), definition.id(), standing.skillId())) {
            return Optional.of(false);
        }
        if (tags.countAttachments(subject.userId(), standing.skillId())
                >= SkillTagService.MAX_MY_TAGS_PER_SKILL) {
            throw new com.skillmasterai.modules.skill.SkillRequestException(
                    "name", "tag_limit_reached");
        }
        tags.attachMy(definition.id(), standing.skillId(), Timestamps.now());
        audit.record(new AuditEvent(subject.userId(), "tag_my_attach", "my_tag",
                definition.id(), detail(definition.id(), standing.skillId())));
        return Optional.of(true);
    }

    /** @return false when the address names nothing the caller may see (the controller's 404) */
    @Transactional
    public boolean detach(String namespaceSlug, String name, String rawTag,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return false;
        }
        var standing = resolved.get().standing();
        requireWritable(standing, namespaceSlug);

        TagNames.Named word = tags.parseName(rawTag);
        Definition definition = tags.findDefinitionByName(subject.userId(), word.key())
                .orElseThrow(TagNotAttachedException::new);
        if (!tags.detachMy(subject.userId(), definition.id(), standing.skillId())) {
            throw new TagNotAttachedException();
        }
        audit.record(new AuditEvent(subject.userId(), "tag_my_detach", "my_tag", definition.id(),
                detail(definition.id(), standing.skillId())));
        return true;
    }

    private static void requireWritable(com.skillmasterai.modules.skill.SkillStanding standing,
            String namespaceSlug) {
        if (!standing.inLibrary()) {
            throw Organize.notInLibrary(standing, namespaceSlug);
        }
        if (!standing.active()) {
            throw Organize.dormant(standing.name());
        }
    }

    /**
     * The audit row carries ids only. A private tag's name is the person's own organization and the
     * product says it must not travel — not into another user's read, and not into a log line
     * (PRD #22, TD §审计).
     */
    private static Map<String, Object> detail(String definitionId, String skillId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("definition_id", definitionId);
        detail.put("skill_id", skillId);
        return detail;
    }
}
