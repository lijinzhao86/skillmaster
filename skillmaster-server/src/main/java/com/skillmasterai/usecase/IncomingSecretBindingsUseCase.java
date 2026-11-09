package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.IncomingBinding;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The variables this person receives from somebody else's credentials on one skill.
 *
 * <p><strong>Read-only, and the only listing in v52 that is about a credential its caller does not
 * own.</strong> The console's skill pane needs it to answer 「这个 skill 为什么能跑」 for a
 * collaborator who never bound anything: without it the pane would list nothing, and the person
 * would conclude the skill has no credentials while it quietly receives two.
 *
 * <p><strong>Two steps, two modules, one answer.</strong> M16 says which variables and whose
 * credentials; M1 turns the owner ids into handles; this class is where they meet. Handles are
 * resolved in one call for the whole list ({@link AccountDirectory#handlesOf}) rather than one per
 * row — the number of rows is the number of people who shared something, which is exactly the
 * number that grows.
 *
 * <p>Nothing here is authorized twice: the address is resolved through {@link SecretSkills}, the
 * same gate every credential write uses, so a skill this caller cannot invoke answers empty.
 */
@Component
public class IncomingSecretBindingsUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final AccountDirectory accounts;

    public IncomingSecretBindingsUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata, AccountDirectory accounts) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Result list(String skillAddress, AuthenticatedSubject subject) {
        if (skillAddress == null || skillAddress.isBlank()) {
            return new Result(List.of());
        }
        Optional<SecretSkills.Resolved> skill =
                SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
        // The same answer as the owner-listing's filter: an address this caller cannot reach is an
        // empty list, not a 404 on an optional filter.
        if (skill.isEmpty()) {
            return new Result(List.of());
        }
        SecretSkills.Resolved resolved = skill.get();
        // **A grant only counts while the collaboration does.** The lease and the metadata read
        // both decide that from M12's `Access.source`; this listing has to use the same rule or it
        // answers a different question than the thing it explains — 「这个 skill 为什么能跑」 would
        // keep naming a credential that no longer reaches the caller. Asked here rather than in the
        // SQL, because M16 does not know what a collaboration is.
        if (!"shared".equals(resolved.access().source())) {
            return new Result(List.of());
        }
        List<IncomingBinding> rows = secrets.incoming(subject.userId(), resolved.skillId(),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        Map<String, String> handles = accounts.handlesOf(
                rows.stream().map(IncomingBinding::ownerUserId).distinct().toList());
        return new Result(rows.stream()
                .map(row -> new Row(row.credentialId(), row.name(),
                        handles.getOrDefault(row.ownerUserId(), row.ownerUserId()),
                        resolved.namespaceSlug() + "/" + resolved.name(), row.envName(),
                        row.perDay(), row.used()))
                .toList());
    }

    /** One row: which credential, whose, on which skill, into which variable. Never a value. */
    public record Row(String credentialId, String name, String owner, String skill, String env,
            int perDay, int used) {
    }

    public record Result(List<Row> items) {
        public Result {
            items = List.copyOf(items);
        }
    }
}
