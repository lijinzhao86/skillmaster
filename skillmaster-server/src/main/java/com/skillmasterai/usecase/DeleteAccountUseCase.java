package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDeletion;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.account.AccountRegistrar;
import com.skillmasterai.modules.account.AuthThrottle;
import com.skillmasterai.modules.account.Throttle;
import com.skillmasterai.modules.account.ThrottledException;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.notification.NotificationDraft;
import com.skillmasterai.modules.notification.NotificationKinds;
import com.skillmasterai.modules.notification.NotificationService;
import com.skillmasterai.modules.skill.SkillCatalogService;
import com.skillmasterai.modules.token.ApiKeys;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes an account: immediate effect, thirty-day retention, the content rules of ADR 0175.
 *
 * <p><strong>Two moments, one use case for the first.</strong> This one runs while the person is
 * still signed in and everything happens now: the account stops acting, its skills stop being
 * visible, its API keys die, and the people using those skills are told. The second moment — the
 * actual deletion of data and content — belongs to {@code PurgeAccountsUseCase}, thirty days later.
 *
 * <p><strong>The password is asked for again, and the username too.</strong> A signed-in browser on
 * a shared machine must not be enough to delete an account: both halves are checked, and either
 * failure is an ordinary invalid-credentials answer. The throttle is keyed by the account rather
 * than the address, because a signed-in caller can guess from anywhere.
 *
 * <p>The order is deliberate: everything that must be known before the rows change — the namespace,
 * who was using which skill — is read first. Marking the skills deleted makes them invisible, which
 * is correct, but also makes the recipients unlistable.
 */
@Component
public class DeleteAccountUseCase {

    private final AccountRegistrar accounts;
    private final AccountDirectory directory;
    private final AccountDeletion deletion;
    private final AuthThrottle throttle;
    private final NamespaceService namespaces;
    private final SkillCatalogService skills;
    private final ApiKeys apiKeys;
    private final NotificationService notifications;
    private final AuditLog audit;

    public DeleteAccountUseCase(AccountRegistrar accounts, AccountDirectory directory,
            AccountDeletion deletion, AuthThrottle throttle, NamespaceService namespaces,
            SkillCatalogService skills, ApiKeys apiKeys, NotificationService notifications,
            AuditLog audit) {
        this.accounts = accounts;
        this.directory = directory;
        this.deletion = deletion;
        this.throttle = throttle;
        this.namespaces = namespaces;
        this.skills = skills;
        this.apiKeys = apiKeys;
        this.notifications = notifications;
        this.audit = audit;
    }

    /**
     * @return false for every way the credentials can fail — wrong username, wrong password — one
     *         answer for both, so the endpoint cannot be used to confirm a password
     * @throws ThrottledException when this account has spent its attempts for the window
     */
    @Transactional(noRollbackFor = ThrottledException.class)
    public boolean delete(String userId, Request request) {
        if (throttle.countAccountDeletion(userId) instanceof Throttle.Refused refused) {
            throw new ThrottledException(refused.retryAfterSeconds());
        }
        String handle = directory.handleOf(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "the session names user " + userId + ", which has no account"));
        if (request.username() == null || !handle.equals(request.username())) {
            return false;
        }
        if (request.password() == null || !accounts.passwordMatches(userId, request.password())) {
            return false;
        }

        String at = Timestamps.now();
        // **The transition is the gate.** Two tabs can both present the right password; only one
        // of them moves `active → deleted`. Without this check both would read the same recipients
        // and send every one of them two identical notices.
        if (!deletion.markDeleted(userId, at)) {
            // **Not active, but that is not enough to call it deleted.** A suspended account also
            // refuses the transition, and answering success there would tell the caller their
            // account is gone while nothing was removed. Only the deleted state is idempotent
            // success.
            return deletion.isDeleted(userId);
        }

        String namespaceId = namespaces.personalNamespaceOf(userId).id();
        // **Read before the skills stop being visible.** The notices are for the people whose
        // workflow this breaks, and after the update there is no query that can find them.
        List<SkillCatalogService.UnavailableSkill> affected =
                skills.unavailableSkillsIn(namespaceId, userId);

        int marked = skills.markDeletedInNamespace(namespaceId, at);
        int revokedKeys = apiKeys.revokeAllFor(userId);
        notifyAffected(userId, handle, affected, at);

        audit.record(new AuditEvent(userId, "account_delete", "user", userId,
                Map.of("skills_marked_deleted", Integer.toString(marked),
                        "api_keys_revoked", Integer.toString(revokedKeys))));
        return true;
    }

    /**
     * One notice per skill, to everyone outside the namespace who was using it.
     *
     * <p>The copy is written here because this is the only place that knows why: the author is
     * gone. It is an announcement — nothing to accept or decline — and it carries the skill id so
     * the client can name the skill.
     */
    private void notifyAffected(String userId, String handle,
            List<SkillCatalogService.UnavailableSkill> affected, String at) {
        for (SkillCatalogService.UnavailableSkill skill : affected) {
            if (skill.recipients().isEmpty()) {
                continue;
            }
            notifications.emit(new NotificationDraft(
                    NotificationKinds.SKILL_UNAVAILABLE,
                    // No subject key: this is not a request that can be resolved, and two skills
                    // named "x" from two authors must not collapse into one row.
                    null,
                    userId,
                    handle,
                    skill.skillId(),
                    null,
                    "这个 skill 已不可用",
                    "作者 " + handle + " 已注销账号，这个 skill 不能再使用。",
                    null,
                    skill.recipients()), at);
        }
    }

    /** @param username the account's handle; both this and the password must be right */
    public record Request(String username, String password) {
    }
}
