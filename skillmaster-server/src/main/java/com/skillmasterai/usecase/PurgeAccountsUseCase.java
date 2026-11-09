package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDeletion;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.metrics.UsageErasure;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillPersonalData;
import com.skillmasterai.modules.token.ApiKeys;
import com.skillmasterai.modules.version.SkillVersionService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second moment of an account deletion: deletes what the retention window kept (v56, ADR 0175).
 *
 * <p><strong>One account, one transaction.</strong> A failure on one account must not block the
 * others until tomorrow, and a half-purged account is a state neither the text nor the tests can
 * describe. The job loops; this class never does.
 *
 * <p><strong>The order is the design's, and every step is load-bearing.</strong> The namespace goes
 * first — its skills cascade, which is where the person's content (including what they shared) is
 * removed. Then the rows that name them as a person: keys, notifications, credentials, membership,
 * and M1's own phone and password columns. Only then is the account row de-identified; it is never
 * deleted, because other people's content and audit rows still point at its id.
 *
 * <p>The blob sweep runs inside the same transaction as the version-file deletes, under the lock
 * {@link SkillVersionService#underExclusiveWrite} takes — the arrangement ADR 0010 decision 4
 * requires, and the purge is just another caller of it.
 */
@Component
public class PurgeAccountsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeAccountsUseCase.class);

    private final AccountDeletion deletion;
    private final NamespaceService namespaces;
    private final ApiKeys apiKeys;
    private final NotificationService notifications;
    private final SecretService secrets;
    private final SkillPersonalData skillRows;
    private final UsageErasure usage;
    private final SkillVersionService versions;
    private final AuditLog audit;

    public PurgeAccountsUseCase(AccountDeletion deletion, NamespaceService namespaces,
            ApiKeys apiKeys, NotificationService notifications, SecretService secrets,
            SkillPersonalData skillRows, UsageErasure usage, SkillVersionService versions,
            AuditLog audit) {
        this.deletion = deletion;
        this.namespaces = namespaces;
        this.apiKeys = apiKeys;
        this.notifications = notifications;
        this.secrets = secrets;
        this.skillRows = skillRows;
        this.usage = usage;
        this.versions = versions;
        this.audit = audit;
    }

    /** Accounts whose deletion window ended at or before {@code cutoff}, oldest first. */
    @Transactional(readOnly = true)
    public List<String> due(String cutoff) {
        return deletion.dueForPurge(cutoff);
    }

    /** The cutoff for a window of {@code retention}, in the shape {@code deleted_at} is stored. */
    public static String cutoff(AccountRetention retention) {
        return Timestamps.format(java.time.Instant.now().minus(retention.personalData()));
    }

    /** Deletes one account's kept data and content, then de-identifies the account row. */
    @Transactional
    public void purgeOne(String userId) {
        // **The claim is the lock.** Two instances run this job; whichever moves `purged_at` from
        // null owns the work, and the other returns without taking the global sweep lock.
        if (!deletion.claimForPurge(userId, Timestamps.now())) {
            LOG.info("account purge: {} already claimed", userId);
            return;
        }
        versions.underExclusiveWrite(() -> {
            namespaces.purgePersonalNamespace(userId);
            namespaces.removeMembershipsOf(userId);
            apiKeys.deleteAllFor(userId);
            notifications.deleteAllFor(userId);
            notifications.anonymizeActor(userId);
            int secretsDeleted = secrets.deleteAllFor(userId);
            int sharesDeleted = secrets.deleteSharesFor(userId);
            // The rows in *other people's* namespaces: grants received, invitations addressed to
            // them, requests they made, skills they saved, tags they wrote. The namespace cascade
            // above cannot reach any of these.
            skillRows.deleteFor(userId);
            usage.deleteAllFor(userId);
            deletion.purgePersonalData(userId);
            // The audit rows stay — they are the security record — but whatever a handle or a name
            // was written into goes with the rest of the personal data (ADR 0175).
            audit.eraseDetailsFor(userId);
            audit.record(new AuditEvent(userId, "account_purge", "user", userId,
                    Map.of("service_credentials_deleted", Integer.toString(secretsDeleted),
                            "credential_shares_deleted", Integer.toString(sharesDeleted))));
            return null;
        });
        LOG.info("account purge: {} purged", userId);
    }
}
