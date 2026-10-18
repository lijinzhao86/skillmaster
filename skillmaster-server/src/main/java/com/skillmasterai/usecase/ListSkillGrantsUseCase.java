package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillGrant;
import com.skillmasterai.modules.skill.SkillSharingService;
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

    /** One page of the collaborators list; the page size the design fixes. */
    public static final int PAGE_SIZE = 10;

    /**
     * @param q    a substring to match against handles, or null/blank for everyone. Filtering here
     *             rather than in the browser is deliberate: the address carries it ({@code &q=}),
     *             and a client that pulled the whole list to filter it would be showing a page the
     *             server never agreed to
     * @param page one-based page number; null or below 1 means the first page
     */
    @Transactional(readOnly = true)
    public Optional<GrantPage> grants(String namespaceSlug, String name, String q, Integer page,
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
            List<SharedWith> matched = rows.stream()
                    // A grant whose account has vanished is dropped rather than rendered as a
                    // blank. Accounts are not deleted today, so this is unreachable — and it stays
                    // written because the alternative is a page that shows a nameless row.
                    .filter(row -> handles.containsKey(row.granteeId()))
                    .map(row -> new SharedWith(handles.get(row.granteeId()), row.role(),
                            row.createdAt()))
                    .filter(row -> q == null || q.isBlank()
                            || row.handle().toLowerCase(java.util.Locale.ROOT)
                                    .contains(q.trim().toLowerCase(java.util.Locale.ROOT)))
                    .toList();

            int wanted = page == null || page < 1 ? 1 : page;
            int from = Math.min((wanted - 1) * PAGE_SIZE, matched.size());
            int to = Math.min(from + PAGE_SIZE, matched.size());
            return new GrantPage(matched.subList(from, to), matched.size(), wanted, PAGE_SIZE);
        });
    }

    /** @param createdAt when access was first given, which a changed role does not move */
    public record SharedWith(String handle, String role, String createdAt) {
    }

    /**
     * One page of the list, plus what a pager needs to say where it is.
     *
     * @param total   how many people match the query, before the page is cut
     * @param page    the page actually returned, normalised to at least 1
     * @param pageSize the design's fixed page size
     */
    public record GrantPage(List<SharedWith> grants, int total, int page, int pageSize) {
    }
}
