package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.CredentialBinding;
import com.skillmasterai.modules.secret.NotShareableException;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands one credential's use, on one skill, to an accepted collaborator of that skill.
 *
 * <p><strong>Three rules meet here.</strong> The caller must own the credential; the target must be
 * an <em>accepted</em> collaborator — {@code skill_grant} exists and the effective rung is at least
 * {@code invoke}, which M12 answers through its own predicate; and the share is effective only on
 * the skill it names, which is why every binding on that skill gets its own row.
 *
 * <p>The target is resolved here, per user, and then asked through M12 as that person. A public
 * skill's rung alone is not enough: the requirement is that the owner deliberately added them, not
 * that the world can invoke it.
 */
@Component
public class ShareSecretUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AccountDirectory accounts;
    private final AuditLog audit;

    public ShareSecretUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata, AccountDirectory accounts, AuditLog audit) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** @return empty when the skill address names nothing the caller may invoke */
    @Transactional
    public Optional<Result> share(String credentialId, String skillAddress, String handle,
            AuthenticatedSubject subject) {
        var credential = SecretSkills.owned(secrets, credentialId, subject.userId());
        Optional<SecretSkills.Resolved> skill =
                SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
        if (skill.isEmpty()) {
            return Optional.empty();
        }
        SecretSkills.Resolved resolved = skill.get();

        String granteeId = accounts.userIdOf(handle == null ? "" : handle.trim())
                .orElseThrow(() -> new NotShareableException(
                        "no account with that handle, or it is not a collaborator of this skill"));
        if (granteeId.equals(credential.ownerUserId())) {
            throw new NotShareableException("a credential cannot be shared with its own owner");
        }
        requireAcceptedCollaborator(resolved, granteeId);

        String at = Timestamps.now();
        List<CredentialBinding.Share> written = secrets.share(credentialId, resolved.skillId(),
                granteeId, subject.userId(), at);
        audit.record(new AuditEvent(subject.userId(), "credential_share", "credential",
                credentialId, Map.of("skillId", resolved.skillId(), "granteeUserId", granteeId)));

        List<Item> items = written.stream()
                .map(row -> new Item(handle.trim(), skillAddress, row.envName(), row.createdAt()))
                .toList();
        return Optional.of(new Result(items));
    }

    private void requireAcceptedCollaborator(SecretSkills.Resolved resolved, String granteeId) {
        Caller target = new Caller(granteeId,
                namespaces.personalNamespaceOf(granteeId).id());
        Optional<Access> access = metadata.accessFor(resolved.namespaceId(), resolved.name(),
                target);
        boolean accepted = access.isPresent()
                && "shared".equals(access.get().source())
                && access.get().atLeast(GrantRole.INVOKE);
        if (!accepted) {
            throw new NotShareableException(
                    "that account is not an accepted collaborator of this skill; share the skill"
                            + " with them first");
        }
    }

    public record Result(List<Item> items) {
        public Result {
            items = List.copyOf(items);
        }
    }

    public record Item(String user, String skill, String env, String createdAt) {
    }
}
