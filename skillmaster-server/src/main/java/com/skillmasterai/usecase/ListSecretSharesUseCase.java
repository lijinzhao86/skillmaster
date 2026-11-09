package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.CredentialBinding;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is receiving this credential, and through which skill and variable — the owner's read-only
 * view. A grantee cannot see this list; only the person who handed the value out can.
 */
@Component
public class ListSecretSharesUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AccountDirectory accounts;

    public ListSecretSharesUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata, AccountDirectory accounts) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Result list(String credentialId, String skillAddress, AuthenticatedSubject subject) {
        SecretSkills.owned(secrets, credentialId, subject.userId());

        String skillIdFilter = null;
        if (skillAddress != null && !skillAddress.isBlank()) {
            Optional<SecretSkills.Resolved> skill =
                    SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
            if (skill.isEmpty()) {
                return new Result(List.of());
            }
            skillIdFilter = skill.get().skillId();
        }

        List<CredentialBinding.Share> rows = secrets.shares(credentialId, skillIdFilter);
        Set<String> granteeIds = new HashSet<>();
        Set<String> skillIds = new HashSet<>();
        rows.forEach(row -> {
            granteeIds.add(row.granteeUserId());
            skillIds.add(row.skillId());
        });
        Map<String, String> handles = accounts.handlesOf(granteeIds);
        Map<String, String> addresses = SecretSkills.addressesOf(skillIds, metadata, namespaces);

        List<Item> items = rows.stream()
                .map(row -> new Item(
                        handles.getOrDefault(row.granteeUserId(), row.granteeUserId()),
                        addresses.getOrDefault(row.skillId(), row.skillId()),
                        row.envName(),
                        row.createdAt()))
                .toList();
        return new Result(items);
    }

    public record Result(List<Item> items) {
        public Result {
            items = List.copyOf(items);
        }
    }

    public record Item(String user, String skill, String env, String createdAt) {
    }
}
