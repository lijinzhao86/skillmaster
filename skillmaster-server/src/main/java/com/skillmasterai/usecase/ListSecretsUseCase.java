package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.secret.CredentialBinding;
import com.skillmasterai.modules.secret.CredentialRef;
import com.skillmasterai.modules.secret.SecretService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's credentials — labels, bindings, today's lease count and timestamps. Never a value.
 *
 * <p>The optional skill filter is applied to the bindings, not to the credential: one value bound
 * to two skills is still one row, and it may appear in both filtered lists.
 */
@Component
public class ListSecretsUseCase {

    private final SecretService secrets;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;

    public ListSecretsUseCase(SecretService secrets, NamespaceService namespaces,
            SkillMetadataService metadata) {
        this.secrets = secrets;
        this.namespaces = namespaces;
        this.metadata = metadata;
    }

    @Transactional(readOnly = true)
    public Result list(String skillAddress, AuthenticatedSubject subject) {
        String skillIdFilter = null;
        if (skillAddress != null && !skillAddress.isBlank()) {
            Optional<SecretSkills.Resolved> skill =
                    SecretSkills.byAddress(skillAddress, subject, namespaces, metadata);
            // An address the caller cannot see filters everything out; it is not an error, because
            // a listing endpoint that 404s on one optional filter is harder to use than an empty
            // list.
            if (skill.isEmpty()) {
                return new Result(List.of());
            }
            skillIdFilter = skill.get().skillId();
        }

        List<CredentialRef> credentials = secrets.list(subject.userId(), skillIdFilter);
        Map<String, List<CredentialBinding>> bindings = new HashMap<>();
        Set<String> skillIds = new HashSet<>();
        for (CredentialRef credential : credentials) {
            List<CredentialBinding> rows = secrets.bindings(credential.id());
            bindings.put(credential.id(), rows);
            rows.forEach(row -> skillIds.add(row.skillId()));
        }
        Map<String, String> addresses = SecretSkills.addressesOf(skillIds, metadata, namespaces);

        String day = LocalDate.now(ZoneOffset.UTC).toString();
        List<Item> items = credentials.stream()
                .map(credential -> new Item(
                        credential.id(),
                        credential.name(),
                        credential.perDayLimit(),
                        secrets.usedToday(credential.id(), day),
                        credential.createdAt(),
                        credential.rotatedAt(),
                        credential.lastUsedAt(),
                        bindings.getOrDefault(credential.id(), List.of()).stream()
                                .map(row -> new BindingView(
                                        addresses.getOrDefault(row.skillId(), row.skillId()),
                                        row.envName()))
                                .toList()))
                .toList();
        return new Result(items);
    }

    public record Result(List<Item> items) {
        public Result {
            items = List.copyOf(items);
        }
    }

    public record Item(String id, String name, int perDay, int used, String createdAt,
            String rotatedAt, String lastUsedAt, List<BindingView> bindings) {
        public Item {
            bindings = List.copyOf(bindings);
        }
    }

    public record BindingView(String skill, String env) {
    }
}
