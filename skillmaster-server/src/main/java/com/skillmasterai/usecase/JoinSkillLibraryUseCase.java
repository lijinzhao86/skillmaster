package com.skillmasterai.usecase;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillStanding;
import com.skillmasterai.modules.skill.SkillLibraryService;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adding a public skill to the caller's library, as a reference (v14, ADR 0082).
 *
 * <p><strong>The boundary is the skill's own standing, not a new rule.</strong> Joinable means:
 * visible, public, published, online, not deleted, not created by the caller, and not already
 * granted to them. {@link SkillStanding} already answers all of it; this use case only decides
 * which refusal each state gets.
 *
 * <p>A repeat is a no-op, including when the entry is dormant — the reference is the fact, and
 * "already in your library" is the honest answer. A skill that is already there for a better reason
 * (created or shared) is a 409: joining would add nothing and would misdescribe the entry.
 */
@Component
public class JoinSkillLibraryUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillLibraryService library;
    private final AuditLog audit;

    public JoinSkillLibraryUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillLibraryService library, AuditLog audit) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.library = library;
        this.audit = audit;
    }

    /**
     * @return empty when the address names nothing the caller may see (the controller's 404);
     *         otherwise whether the request changed anything — a repeat is {@code false} so the
     *         client can say "已经在你的库里。"
     */
    @Transactional
    public Optional<Boolean> join(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<Organize.Target> resolved =
                Organize.resolve(namespaces, metadata, namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        SkillStanding standing = resolved.get().standing();

        // The row, not the displayed source, is the no-op test (PRD #3): a grant received after
        // joining raises the source to "shared" while the reference is still there.
        if (library.joined(subject.userId(), standing.skillId())) {
            return Optional.of(false);
        }
        if (standing.inLibrary()) {
            throw new ConflictException(ErrorCode.SKILL_NOT_JOINABLE,
                    "'" + name + "' is already in your library as " + standing.source()
                            + "; joining would not add anything");
        }
        if (!standing.discoverable()) {
            throw new ConflictException(ErrorCode.SKILL_NOT_JOINABLE,
                    "'" + name + "' cannot be joined right now; it must be public, published and"
                            + " online");
        }

        // The insert's answer is the result: if a concurrent request inserted first, this one is
        // the no-op it became, and nothing is audited twice.
        if (!library.join(subject.userId(), standing.skillId(), Timestamps.now())) {
            return Optional.of(false);
        }
        audit.record(AuditEvent.of(subject.userId(), "library_join", "skill", standing.skillId()));
        return Optional.of(true);
    }
}
