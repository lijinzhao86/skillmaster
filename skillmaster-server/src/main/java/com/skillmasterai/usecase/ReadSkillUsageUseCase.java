package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.activity.UsageHistory;
import com.skillmasterai.modules.audit.AuditCounts;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.metrics.UsageSnapshot;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillGrant;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillSharingService;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The usage shown on a skill's page (v58).
 *
 * <p>Two questions, two audiences: the owner's "who uses this, and how much", and anyone's "what
 * did <em>I</em> do with this skill". They meet here because both need the same resolved
 * {@code Access} — and resolving it twice is how the two answers could disagree about which skill
 * was asked about.
 *
 * <p><strong>Visibilities are answered as nothing, not as forbidden.</strong> A non-owner asking
 * for the owner view gets an empty {@code Optional}, which the controller turns into the same 404
 * an unknown skill gets. That is ADR 0179's rule: the existence of somebody else's usage is not
 * something to confirm.
 */
@Component
public class ReadSkillUsageUseCase {

    private static final String OWNER = "所有者";

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillSharingService sharing;
    private final AccountDirectory accounts;
    private final SkillVersionService versions;
    private final SourceService sources;
    private final UsageHistory history;
    private final AuditCounts auditCounts;

    ReadSkillUsageUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillSharingService sharing, AccountDirectory accounts, SkillVersionService versions,
            SourceService sources, UsageHistory history, AuditCounts auditCounts) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.sharing = sharing;
        this.accounts = accounts;
        this.versions = versions;
        this.sources = sources;
        this.history = history;
        this.auditCounts = auditCounts;
    }

    /** The owner's aggregate and users table, or empty for anyone else. */
    @Transactional(readOnly = true)
    public Optional<UsageHistory.OwnerUsage> ownerUsage(String namespaceSlug, String name,
            AuthenticatedSubject subject, UsageHistory.Window window) {
        Resolved resolved = resolve(namespaceSlug, name, subject);
        if (resolved == null || !resolved.namespace().ownerUserId().equals(subject.userId())) {
            return Optional.empty();
        }
        Access access = resolved.access();
        int joins = sources.joins(access.skillId());
        if ("public".equals(access.visibility())) {
            // The platform signal (ADR 0152) is cumulative and lives on M15's snapshots; this page
            // asks the other question — "how much in this window" — so it aggregates the window and
            // buckets before leaving (ADR 0182).
            UsageHistory.PublicRange range = history.skillRange(access.skillId(), window);
            // The instant, not a pre-cut date: the client renders it in the display timezone, and
            // cutting the UTC day here would show yesterday's date for a local-early-morning call.
            return Optional.of(new UsageHistory.OwnerUsage(true, null, null, joins, range.last(),
                    UsageSnapshot.floorOf(range.calls()),
                    UsageSnapshot.floorOf(range.accounts()), List.of()));
        }
        return Optional.of(privateOwner(resolved, joins, window, subject));
    }

    /** One person's own use of this skill, or empty when they cannot see it at all. */
    @Transactional(readOnly = true)
    public Optional<UsageHistory.Mine> mine(String namespaceSlug, String name,
            AuthenticatedSubject subject, UsageHistory.Window window) {
        Resolved resolved = resolve(namespaceSlug, name, subject);
        if (resolved == null) {
            return Optional.empty();
        }
        UsageHistory.Mine raw = history.mine(resolved.access().skillId(), subject.userId(), window);
        Map<String, String> states = versions.versionsOf(resolved.access()).stream()
                .collect(Collectors.toMap(VersionSummary::version, VersionSummary::status,
                        (first, second) -> first));
        List<UsageHistory.MineVersion> rows = raw.byVersion().stream()
                .map(row -> new UsageHistory.MineVersion(row.version(),
                        states.getOrDefault(row.version(), ""), row.calls(), row.last()))
                .toList();
        return Optional.of(new UsageHistory.Mine(raw.calls(), rows.size(), raw.latest(), rows));
    }

    private UsageHistory.OwnerUsage privateOwner(Resolved resolved, int joins,
            UsageHistory.Window window, AuthenticatedSubject subject) {
        Access access = resolved.access();
        String skillId = access.skillId();
        String ownerId = resolved.namespace().ownerUserId();

        // **The range view reads the live log, not the daily rollup.** The rollup is keyed by UTC
        // day and stops at yesterday; a period chosen in local days needs the instant and today.
        // The rollup itself stays — it is ADR 0179 decision 4's 1-year aggregate for internal
        // trends, it is just no longer this page's read path.
        Map<String, UsageHistory.ActorInvokes> callsByActor = history
                .collaboratorDays(skillId, window).stream()
                .collect(Collectors.toMap(UsageHistory.ActorInvokes::actorUserId,
                        Function.identity(), (first, second) -> first));
        Map<String, AuditCounts.SubmissionCount> submittedByActor = auditCounts
                .submissionsOfSkill(skillId, window.since(), window.until()).stream()
                .collect(Collectors.toMap(AuditCounts.SubmissionCount::actorUserId,
                        Function.identity(), (first, second) -> first));

        List<SkillGrant> grants = sharing.grantsOf(resolved.namespace().id(), access.name(),
                Callers.of(namespaces, subject)).orElse(List.of());
        List<String> ids = new ArrayList<>();
        ids.add(ownerId);
        grants.forEach(grant -> ids.add(grant.granteeId()));
        Map<String, String> handles = new HashMap<>(accounts.handlesOf(ids));

        // The owner's row is the same query 「我的使用」 runs, so the two numbers cannot disagree.
        UsageHistory.Mine own = history.mine(skillId, ownerId, window);
        AuditCounts.SubmissionCount ownSubmitted = submittedByActor.get(ownerId);
        UsageHistory.Collaborator ownerRow = new UsageHistory.Collaborator(
                handles.getOrDefault(ownerId, ownerId), true, OWNER, own.calls(),
                ownSubmitted == null ? 0 : ownSubmitted.versions(),
                latestOf(own.latest(), ownSubmitted == null ? null : ownSubmitted.lastAt()));

        List<UsageHistory.Collaborator> collaborators = new ArrayList<>();
        for (SkillGrant grant : grants) {
            String grantee = grant.granteeId();
            UsageHistory.ActorInvokes calls = callsByActor.get(grantee);
            AuditCounts.SubmissionCount submitted = submittedByActor.get(grantee);
            collaborators.add(new UsageHistory.Collaborator(
                    handles.getOrDefault(grantee, grantee), false, grant.role(),
                    calls == null ? 0 : calls.invoke(),
                    submitted == null ? 0 : submitted.versions(),
                    latestOf(calls == null ? null : calls.last(),
                            submitted == null ? null : submitted.lastAt())));
        }
        Comparator<UsageHistory.Collaborator> order = Comparator
                .comparingLong(UsageHistory.Collaborator::invoke).reversed()
                .thenComparing(UsageHistory.Collaborator::last,
                        Comparator.nullsLast(Comparator.reverseOrder()));
        collaborators.sort(order);

        List<UsageHistory.Collaborator> users = new ArrayList<>();
        users.add(ownerRow);
        users.addAll(collaborators);

        // The totals describe the skill, not the current grant list: somebody whose access was
        // revoked still used it, and the owner asking "how much is this skill used" means everyone.
        long calls = own.calls()
                + callsByActor.values().stream()
                        .filter(row -> !row.actorUserId().equals(ownerId))
                        .mapToLong(UsageHistory.ActorInvokes::invoke).sum();
        int active = (own.calls() > 0 ? 1 : 0)
                + (int) callsByActor.values().stream()
                        .filter(row -> !row.actorUserId().equals(ownerId) && row.invoke() > 0)
                        .count();
        String last = java.util.stream.Stream.concat(
                        callsByActor.values().stream().map(UsageHistory.ActorInvokes::last),
                        java.util.stream.Stream.of(own.latest()))
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null);
        return new UsageHistory.OwnerUsage(false, calls, active, joins, last, null, null,
                users);
    }

    private static String latestOf(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }

    private Resolved resolve(String namespaceSlug, String name, AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return null;
        }
        Optional<Access> access = metadata.accessFor(namespace.get().id(), name,
                Callers.of(namespaces, subject));
        return access.map(found -> new Resolved(namespace.get(), found)).orElse(null);
    }

    private record Resolved(Namespace namespace, Access access) {
    }
}
