package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.activity.UsageHistory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.metrics.UsageQuery;
import com.skillmasterai.modules.metrics.UsageScope;
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
    private final UsageQuery publicUsage;
    private final UsageHistory history;

    ReadSkillUsageUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillSharingService sharing, AccountDirectory accounts, SkillVersionService versions,
            SourceService sources, UsageQuery publicUsage, UsageHistory history) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.sharing = sharing;
        this.accounts = accounts;
        this.versions = versions;
        this.sources = sources;
        this.publicUsage = publicUsage;
        this.history = history;
    }

    /** The owner's aggregate and users table, or empty for anyone else. */
    @Transactional(readOnly = true)
    public Optional<UsageHistory.OwnerUsage> ownerUsage(String namespaceSlug, String name,
            AuthenticatedSubject subject, String at) {
        Resolved resolved = resolve(namespaceSlug, name, subject);
        if (resolved == null || !resolved.namespace().ownerUserId().equals(subject.userId())) {
            return Optional.empty();
        }
        Access access = resolved.access();
        int joins = sources.joins(access.skillId());
        if ("public".equals(access.visibility())) {
            UsageSnapshot snapshot = publicUsage
                    .snapshots(List.of(access.skillId()), UsageScope.PUBLIC)
                    .get(access.skillId());
            return Optional.of(new UsageHistory.OwnerUsage(true, null, null, joins,
                    snapshot == null || snapshot.lastUsed() == null ? null
                            : snapshot.lastUsed().toString(),
                    snapshot == null ? 0 : snapshot.callsBucketFloor(),
                    snapshot == null ? 0 : snapshot.invokers30dBucketFloor(),
                    List.of()));
        }
        return Optional.of(privateOwner(resolved, joins, at, subject));
    }

    /** One person's own use of this skill, or empty when they cannot see it at all. */
    @Transactional(readOnly = true)
    public Optional<UsageHistory.Mine> mine(String namespaceSlug, String name,
            AuthenticatedSubject subject, String at) {
        Resolved resolved = resolve(namespaceSlug, name, subject);
        if (resolved == null) {
            return Optional.empty();
        }
        UsageHistory.Mine raw = history.mine(resolved.access().skillId(), subject.userId(), at);
        Map<String, String> states = versions.versionsOf(resolved.access()).stream()
                .collect(Collectors.toMap(VersionSummary::version, VersionSummary::status,
                        (first, second) -> first));
        List<UsageHistory.MineVersion> rows = raw.byVersion().stream()
                .map(row -> new UsageHistory.MineVersion(row.version(),
                        states.getOrDefault(row.version(), ""), row.calls(), row.last()))
                .toList();
        return Optional.of(new UsageHistory.Mine(raw.calls(), rows.size(), raw.latest(), rows));
    }

    private UsageHistory.OwnerUsage privateOwner(Resolved resolved, int joins, String at,
            AuthenticatedSubject subject) {
        Access access = resolved.access();
        String skillId = access.skillId();
        String ownerId = resolved.namespace().ownerUserId();

        List<UsageHistory.UserDay> days = history.userDays(skillId, at);
        Map<String, UsageHistory.UserDay> byActor = days.stream()
                .collect(Collectors.toMap(UsageHistory.UserDay::actorUserId, Function.identity(),
                        (first, second) -> first));

        List<SkillGrant> grants = sharing.grantsOf(resolved.namespace().id(), access.name(),
                Callers.of(namespaces, subject)).orElse(List.of());
        List<String> ids = new ArrayList<>();
        ids.add(ownerId);
        grants.forEach(grant -> ids.add(grant.granteeId()));
        Map<String, String> handles = new HashMap<>(accounts.handlesOf(ids));

        // The owner's own number comes from the live detail log, not the daily rollup: it is the
        // row the page highlights, and it has to agree with 「我的使用」 today, not tomorrow.
        UsageHistory.Mine own = history.mine(skillId, ownerId, at);
        UsageHistory.Collaborator ownerRow = new UsageHistory.Collaborator(
                handles.getOrDefault(ownerId, ownerId), true, OWNER, own.calls(),
                byActor.containsKey(ownerId) ? byActor.get(ownerId).submitted() : 0,
                own.latest() != null ? own.latest()
                        : byActor.containsKey(ownerId) ? byActor.get(ownerId).last() : null);

        List<UsageHistory.Collaborator> collaborators = new ArrayList<>();
        for (SkillGrant grant : grants) {
            UsageHistory.UserDay day = byActor.get(grant.granteeId());
            collaborators.add(new UsageHistory.Collaborator(
                    handles.getOrDefault(grant.granteeId(), grant.granteeId()), false,
                    grant.role(), day == null ? 0 : day.invoke(),
                    day == null ? 0 : day.submitted(), day == null ? null : day.last()));
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
        // The owner's own share comes from the live detail log (today included), while the rollup
        // covers the other people through yesterday.
        long calls = own.calls()
                + days.stream().filter(day -> !day.actorUserId().equals(ownerId))
                        .mapToLong(UsageHistory.UserDay::invoke).sum();
        int active = (own.calls() > 0 ? 1 : 0)
                + (int) days.stream()
                        .filter(day -> !day.actorUserId().equals(ownerId) && day.invoke() > 0)
                        .count();
        String last = java.util.stream.Stream.concat(
                        days.stream().map(UsageHistory.UserDay::last),
                        java.util.stream.Stream.of(own.latest()))
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null);
        return new UsageHistory.OwnerUsage(false, calls, active, joins, last, null, null,
                users);
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
