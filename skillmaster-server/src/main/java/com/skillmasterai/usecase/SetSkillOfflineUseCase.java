package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stops serving a whole skill, and resumes it.
 *
 * <p>A service switch, not a version state: no {@code skill_version} row changes and the pointer
 * stays where it is. Since v61 a public skill with people using it goes through a 30-day
 * <strong>下线中</strong> ramp first: it keeps serving, every call carries a warning, and it cannot
 * be joined. Private skills and public ones nobody joined still switch off immediately (ADR 0184).
 *
 * <p>Only the owner may set it — {@code offline} takes the skill away from every caller at once.
 */
@Component
public class SetSkillOfflineUseCase {

    private static final int RAMP_DAYS = 30;

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AuditLog audit;
    private final SkillLifecycleNotices notices;
    private final AccountDirectory accounts;

    public SetSkillOfflineUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            AuditLog audit, SkillLifecycleNotices notices, AccountDirectory accounts) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.audit = audit;
        this.notices = notices;
        this.accounts = accounts;
    }

    /**
     * @param offline true to stop serving, false to resume or cancel a ramp
     * @return the resulting state, or empty when the address resolves to nothing
     */
    @Transactional
    public Optional<State> set(String namespaceSlug, String name, boolean offline,
            AuthenticatedSubject subject) {
        Optional<com.skillmasterai.modules.namespace.Namespace> namespace =
                namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = Callers.of(namespaces, subject);
        Optional<Access> access = metadata.accessFor(namespace.get().id(), name, caller);
        if (access.isEmpty()) {
            return Optional.empty();
        }
        if (!access.get().owns()) {
            throw new NotPermittedException("switching '" + name
                    + "' off is the owner's; it stops every caller, not just some of them");
        }
        String skillId = access.get().skillId();
        String now = Timestamps.now();
        String handle = accounts.handleOf(subject.userId()).orElse(null);

        if (!offline) {
            // **The statement is the transition** (v61 audit): two overlapping requests both read
            // the same state, but only the one whose UPDATE changed a row notifies and audits. A
            // ramp and 已下线 are separate writes, so "cancel" is announced only when a live ramp
            // was actually withdrawn — never after the daily job already completed it.
            if (metadata.cancelRamp(skillId)) {
                notices.cancelled(skillId, subject.userId(), handle,
                        namespaceSlug + "/" + name, now);
                audit.record(new AuditEvent(subject.userId(), "skill_online", "skill", skillId,
                        Map.of("name", name)));
                return Optional.of(new State(false, null));
            }
            if (metadata.resume(skillId)) {
                audit.record(new AuditEvent(subject.userId(), "skill_online", "skill", skillId,
                        Map.of("name", name)));
                return Optional.of(new State(false, null));
            }
            // Already serving, or a concurrent change won; report the row as it is now.
            return reRead(namespace.get().id(), name, caller, now);
        }

        // Already off, or already inside the ramp: idempotent, and the second press must not move
        // the date (ADR 0184). A ramp whose date has passed reads as offline even before the job
        // writes offline_at, so it is the same answer.
        if (access.get().offlineAt() != null || due(access.get().offlineDueAt(), now)) {
            return Optional.of(new State(true, null));
        }
        if (access.get().offlineDueAt() != null) {
            return Optional.of(new State(false, access.get().offlineDueAt()));
        }

        boolean publicSkill = "public".equals(access.get().visibility());
        boolean hasVersion = access.get().currentVersionId() != null;
        if (publicSkill && hasVersion && metadata.libraryJoins(skillId) > 0) {
            String dueAt = Timestamps.format(Timestamps.parse(now).plus(RAMP_DAYS, ChronoUnit.DAYS));
            if (!metadata.setPendingOffline(skillId, now, dueAt)) {
                // A concurrent change won: report the row as it actually is, not the date this
                // request failed to write.
                return reRead(namespace.get().id(), name, caller, now);
            }
            // Only the transition notifies: a second press is idempotent and must not send
            // the audience a duplicate (ADR 0184).
            notices.scheduled(skillId, subject.userId(), handle, dueAt, now);
            audit.record(new AuditEvent(subject.userId(), "skill_offline_scheduled", "skill",
                    skillId, Map.of("name", name, "due", dueAt)));
            return Optional.of(new State(false, dueAt));
        }

        if (!metadata.setOffline(skillId, now)) {
            return reRead(namespace.get().id(), name, caller, now);
        }
        audit.record(new AuditEvent(subject.userId(), "skill_offline", "skill", skillId,
                Map.of("name", name)));
        return Optional.of(new State(true, null));
    }

    /**
     * The row as it is now, after a conditional write changed nothing. Empty means it stopped being
     * this caller's — a concurrent delete won — and the address no longer resolves.
     */
    private Optional<State> reRead(String namespaceId, String name, Caller caller, String now) {
        return metadata.accessFor(namespaceId, name, caller).map(access -> {
            if (access.offlineNow()) {
                return new State(true, null);
            }
            return new State(false, access.pendingNow() ? access.offlineDueAt() : null);
        });
    }

    private static boolean due(String dueAt, String now) {
        return dueAt != null && !Timestamps.parse(dueAt).isAfter(Timestamps.parse(now));
    }

    /**
     * What the switch did. {@code offline} true means already off; otherwise {@code pendingUntil}
     * is the ramp's date (null when the skill is serving).
     */
    public record State(boolean offline, String pendingUntil) {
    }
}
