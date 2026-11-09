package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.ShareNotFoundException;
import com.skillmasterai.modules.secret.NotShareableException;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdraws every live share of this credential, on this skill, for one person. It takes effect on
 * the next lease with no grace window, which is the promise the sharing screen makes.
 */
@Component
public class UnshareSecretUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AccountDirectory accounts;
    private final AuditLog audit;

    public UnshareSecretUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata, AccountDirectory accounts, AuditLog audit) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** @return false when the skill address names nothing the caller may invoke */
    @Transactional
    public boolean unshare(String credentialId, String skillAddress, String handle,
            AuthenticatedSubject subject) {
        SecretSkills.owned(secrets, credentialId, subject.userId());
        Optional<SecretSkills.Resolved> skill =
                SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
        if (skill.isEmpty()) {
            return false;
        }
        SecretSkills.Resolved resolved = skill.get();
        String granteeId = accounts.userIdOf(handle == null ? "" : handle.trim())
                .orElseThrow(() -> new NotShareableException(
                        "no account with that handle, so there is no share to withdraw"));
        // **Nothing live is a 404, not a quiet success.** The same rule unbinding follows: a
        // revocation that revoked nothing used to answer 204, so a person who mistyped the handle —
        // or who revoked in another tab a moment earlier — was told the share was gone while the
        // real grantee kept receiving the value. "There was nothing to revoke" is an answer the
        // caller has to be able to see.
        if (!secrets.unshare(credentialId, resolved.skillId(), granteeId, Timestamps.now())) {
            throw new ShareNotFoundException();
        }
        audit.record(new AuditEvent(subject.userId(), "credential_unshare", "credential",
                credentialId, Map.of("skillId", resolved.skillId(),
                        "granteeUserId", granteeId)));
        return true;
    }
}
