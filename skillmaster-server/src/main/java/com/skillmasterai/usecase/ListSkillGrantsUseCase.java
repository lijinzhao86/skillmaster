package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.SkillGrant;
import com.skillmasterai.modules.version.SkillSharingService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who a skill is shared with — the owner's view, and only the owner's.
 *
 * <p>{@code SkillSharingService.grantsOf} refuses anybody who may not administer the skill, which
 * means a grantee asking this gets the same answer as a stranger: §4.1's one 404. What is shared
 * with whom is not a grantee's business, and telling them would be telling them about third parties.
 */
@Component
public class ListSkillGrantsUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillSharingService sharing;

    public ListSkillGrantsUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillSharingService sharing) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.sharing = sharing;
    }

    @Transactional(readOnly = true)
    public Optional<List<SharedWith>> grants(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = new Caller(subject.userId(), namespaces
                .personalNamespaceOf(subject.userId()).id());

        return sharing.grantsOf(namespace.get().id(), name, caller).map(rows -> {
            // One batch for the handles: the number of round trips would otherwise be the number of
            // people the skill is shared with, which is the number that grows.
            Map<String, String> handles =
                    accounts.handlesOf(rows.stream().map(SkillGrant::granteeId).toList());
            return rows.stream()
                    // A grant whose account has vanished is dropped rather than rendered as a
                    // blank. Accounts are not deleted today, so this is unreachable — and it stays
                    // written because the alternative is a page that shows a nameless row.
                    .filter(row -> handles.containsKey(row.granteeId()))
                    .map(row -> new SharedWith(handles.get(row.granteeId()), row.role(),
                            row.createdAt()))
                    .toList();
        });
    }

    /** @param createdAt when access was first given, which a changed role does not move */
    public record SharedWith(String handle, String role, String createdAt) {
    }
}
