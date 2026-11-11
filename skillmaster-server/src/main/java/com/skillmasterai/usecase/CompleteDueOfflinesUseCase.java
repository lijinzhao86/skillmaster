package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Materialises the 下线中 ramps whose date has arrived (v61, ADR 0184).
 *
 * <p><strong>The read path does not wait for this.</strong> A due ramp already reads as offline
 * everywhere the derivation runs, so a job that is a day late changes no answer a caller gets; what
 * it adds is the fact on the row and the notice that tells people the skill is gone. That is why
 * the job can afford to be a daily sweep rather than an exact-time scheduler.
 *
 * <p><strong>One transaction per skill, and the claim is the gate.</strong> {@link #completeOne}
 * runs in its own transaction (the job calls it through this bean, so the proxy applies), and the
 * state change plus the notice commit together: a crash rolls both back and tomorrow retries, and
 * a row is claimed by exactly one runner. A failure on one skill must not stop the sweep, which is
 * the job's loop, not this class's.
 */
@Component
public class CompleteDueOfflinesUseCase {

    private final SkillMetadataService metadata;
    private final NamespaceService namespaces;
    private final SkillLifecycleNotices notices;
    private final AccountDirectory accounts;

    public CompleteDueOfflinesUseCase(SkillMetadataService metadata, NamespaceService namespaces,
            SkillLifecycleNotices notices, AccountDirectory accounts) {
        this.metadata = metadata;
        this.namespaces = namespaces;
        this.notices = notices;
        this.accounts = accounts;
    }

    /** The ramps whose date has passed and that no runner has materialised yet. */
    public List<String> dueIds(String now) {
        return metadata.dueOfflineIds(now);
    }

    /**
     * Claims and completes one skill.
     *
     * @return false when another runner claimed it first, or it was deleted or taken down
     *         meanwhile — every one of those is "nothing to do", not an error
     */
    @Transactional
    public boolean completeOne(String skillId, String now) {
        Optional<SkillMetadataService.DueOffline> claimed = metadata.claimDueOffline(skillId, now);
        if (claimed.isEmpty()) {
            return false;
        }
        SkillMetadataService.DueOffline row = claimed.get();
        Optional<Namespace> namespace = namespaces.byId(row.namespaceId());
        if (namespace.isEmpty()) {
            // The namespace is gone but the row is not — the purge removes both, so this is a
            // half-state worth knowing about, and there is no owner to name in the notice.
            return true;
        }
        notices.completed(row.skillId(), namespace.get().ownerUserId(),
                accounts.handleOf(namespace.get().ownerUserId()).orElse(null),
                namespace.get().slug() + "/" + row.name(), row.offlineAt());
        return true;
    }
}
