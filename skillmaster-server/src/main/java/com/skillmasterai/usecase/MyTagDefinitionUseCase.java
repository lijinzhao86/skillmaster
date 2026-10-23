package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.SkillTagService.Definition;
import com.skillmasterai.modules.skill.TagDefinitionNotFoundException;
import com.skillmasterai.modules.skill.TagNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and editing the caller's private-tag definitions (v14, ADR 0083).
 *
 * <p><strong>Definition deletion exists only on the browser plane</strong> (ADR 0079): it removes
 * every attachment, including the dormant ones, and cannot be undone. Every other action here has a
 * CLI command as well.
 *
 * <p><strong>Rename and describe take the account's advisory lock.</strong> They are writes to the
 * same set the ceiling counts, so they share the serialization point with create and auto-create
 * (TD §并发与上限). The lock is cheap and the rule is one thing, not three.
 */
@Component
public class MyTagDefinitionUseCase {

    private final NamespaceService namespaces;
    private final SkillTagService tags;
    private final EnsureMyTagDefinitionUseCase ensure;
    private final AuditLog audit;

    public MyTagDefinitionUseCase(NamespaceService namespaces, SkillTagService tags,
            EnsureMyTagDefinitionUseCase ensure, AuditLog audit) {
        this.namespaces = namespaces;
        this.tags = tags;
        this.ensure = ensure;
        this.audit = audit;
    }

    /**
     * All definitions, with their effective-attachment counts and sentences; ≤100 by construction.
     *
     * <p>{@code name}, when present, resolves **one** definition by the same normalization the
     * write path uses (NFKC, trim, collapse, lower-case) and ignores {@code q}. The CLI's rename and
     * describe need this: they address a word, and the server is the only place that decides that
     * `ＰＤＦ` and `pdf` are the same definition.
     */
    @Transactional(readOnly = true)
    public List<SkillTagService.MyTagRow> list(String q, String name,
            AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        if (name != null) {
            return tags.myTagByKey(caller, TagNames.key(TagNames.display(name)));
        }
        return tags.myTags(caller, q);
    }

    /** The explicit create — {@code POST /api/v1/me/tags}. */
    @Transactional
    public Definition create(String name, String description, AuthenticatedSubject subject) {
        return ensure.create(name, description, subject);
    }

    /**
     * Renames and/or describes one definition in one transaction.
     *
     * <p><strong>One transaction is the point.</strong> A {@code PATCH} may carry both fields, and
     * two separately committed writes would leave the name changed after the sentence was refused —
     * a 400 that still edited the row. {@code null} means "not this field"; an empty description
     * clears it (TD Q6).
     *
     * @throws TagDefinitionNotFoundException when the id names nothing the caller owns
     * @throws com.skillmasterai.modules.skill.SkillRequestException for a taken or refused word
     */
    @Transactional
    public Definition update(String id, String rawName, String rawDescription,
            AuthenticatedSubject subject) {
        tags.lockDefinitionsOf(subject.userId());
        Definition current = tags.findDefinition(subject.userId(), id)
                .orElseThrow(TagDefinitionNotFoundException::new);

        String name = current.name();
        String nameKey = current.nameKey();
        if (rawName != null) {
            TagNames.Named parsed = tags.parseName(rawName);
            tags.requireNameAvailable(subject.userId(), parsed.key(), id);
            tags.updateName(id, parsed.name(), parsed.key(), Timestamps.now());
            name = parsed.name();
            nameKey = parsed.key();

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", id);
            detail.put("name_length", name.codePointCount(0, name.length()));
            audit.record(new AuditEvent(subject.userId(), "tag_definition_rename", "my_tag", id,
                    detail));
        }

        String description = current.description();
        if (rawDescription != null) {
            description = tags.parseDescription(rawDescription);
            tags.updateDescription(id, description, Timestamps.now());

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", id);
            detail.put("description_length", description == null
                    ? 0 : description.codePointCount(0, description.length()));
            audit.record(new AuditEvent(subject.userId(), "tag_definition_describe", "my_tag", id,
                    detail));
        }
        return new Definition(id, name, nameKey, description);
    }

    /** Deletes the definition and, by cascade, every attachment — dormant ones included. */
    @Transactional
    public void delete(String id, AuthenticatedSubject subject) {
        tags.lockDefinitionsOf(subject.userId());
        if (!tags.deleteDefinition(subject.userId(), id)) {
            throw new TagDefinitionNotFoundException();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", id);
        audit.record(new AuditEvent(subject.userId(), "tag_definition_delete", "my_tag", id,
                detail));
    }
}
