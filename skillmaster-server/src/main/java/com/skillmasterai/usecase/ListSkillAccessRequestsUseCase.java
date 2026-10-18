package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.AccessRequest;
import com.skillmasterai.modules.skill.SkillAccessService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everyone waiting on a skill — the {@code admin} rung's queue (ADR 0052).
 *
 * <p>Handles rather than ids, resolved in one batch for the reason the grant list resolves them that
 * way: the number of round trips would otherwise be the number of people waiting. A request whose
 * account has vanished is dropped rather than rendered as a blank row; accounts are not deleted
 * today, so that branch is unreachable and stays written anyway.
 */
@Component
public class ListSkillAccessRequestsUseCase {

    private final NamespaceService namespaces;
    private final AccountDirectory accounts;
    private final SkillAccessService access;

    public ListSkillAccessRequestsUseCase(NamespaceService namespaces, AccountDirectory accounts,
            SkillAccessService access) {
        this.namespaces = namespaces;
        this.accounts = accounts;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Optional<List<WaitingWith>> requests(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> access.pendingFor(namespace.id(), name,
                        Callers.of(namespaces, subject)))
                .map(rows -> {
                    Map<String, String> handles = accounts.handlesOf(
                            rows.stream().map(AccessRequest::requesterId).toList());
                    return rows.stream()
                            .filter(row -> handles.containsKey(row.requesterId()))
                            .map(row -> new WaitingWith(handles.get(row.requesterId()),
                                    row.wantedRole(), row.createdAt()))
                            .toList();
                });
    }

    /**
     * @param handle    the requester, as a person reads it
     * @param wantedRole the rung they asked for
     * @param createdAt RFC3339 UTC, when the current request was made
     */
    public record WaitingWith(String handle, String wantedRole, String createdAt) {
    }
}
