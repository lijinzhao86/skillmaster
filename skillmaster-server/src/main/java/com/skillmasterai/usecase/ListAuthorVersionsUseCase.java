package com.skillmasterai.usecase;

import com.skillmasterai.modules.account.AccountDirectory;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The author's own version list, for the API plane ({@code /api/v1/author/…}).
 *
 * <p><strong>A different question from {@code skill versions}.</strong> That one is a consumer
 * asking what it may invoke; this is an author asking what exists — drafts, revoked and discarded
 * included, filtered to their rung. The two are expected to disagree on row count.
 *
 * <p>The filter, the search and the page are applied here rather than in SQL because the search
 * matches the submitter's handle, which is M1's word; M7 returns the rows and the access rules, and
 * this turns them into an answer.
 */
@Component
public class ListAuthorVersionsUseCase {

    /** The five derived statuses, in the order the page's filter chips show them. */
    private static final List<String> STATUS_ORDER =
            List.of("live", "draft", "history", "revoked", "discarded");

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AccountDirectory accounts;

    public ListAuthorVersionsUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, AccountDirectory accounts) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.accounts = accounts;
    }

    /**
     * @param statuses derived statuses to keep; empty keeps everything this rung may see
     * @param query    case-insensitive substring over the name, version number, note and submitter
     *                 handle; blank is no filter
     * @param cursor   the {@code number} of the last row of the previous page, or null for the first
     */
    @Transactional(readOnly = true)
    public Optional<Page> list(String namespaceSlug, String name, List<String> statuses,
            String query, int limit, String cursor, AuthenticatedSubject subject) {
        Optional<Access> resolved = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                        Callers.of(namespaces, subject)));
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        if (!access.atLeast(GrantRole.BROWSE)) {
            throw new NotPermittedException("listing '" + name
                    + "' versions needs the `browse` rung");
        }

        List<VersionSummary> all = versions.authorVersions(access);
        Map<String, String> handles = accounts.handlesOf(
                all.stream().map(VersionSummary::submittedBy).distinct().toList());

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("all", all.size());
        for (String status : STATUS_ORDER) {
            counts.put(status, 0);
        }
        for (VersionSummary row : all) {
            counts.merge(row.status(), 1, Integer::sum);
        }

        List<VersionSummary> filtered = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (VersionSummary row : all) {
            if (!statuses.isEmpty() && !statuses.contains(row.status())) {
                continue;
            }
            if (!needle.isEmpty() && !matches(row, handles.get(row.submittedBy()), needle)) {
                continue;
            }
            filtered.add(row);
        }

        // The cursor is an opaque offset into the filtered list. It is not a row id because the
        // filter and the search are part of what the next page means: the client sends back what
        // this page handed it, and never interprets it.
        int start;
        try {
            start = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        } catch (NumberFormatException e) {
            // The cursor is ours, so a value that is not one is a client bug rather than a page
            // that happens to be empty. Say which, instead of letting it become a 500.
            throw new com.skillmasterai.common.RequestException(
                    com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                    "cursor is not a token this endpoint issued");
        }
        start = Math.min(Math.max(start, 0), filtered.size());
        int end = Math.min(filtered.size(), start + limit);
        List<VersionSummary> page = filtered.subList(start, end);
        String next = end < filtered.size() ? String.valueOf(end) : null;

        return Optional.of(new Page(access, page, counts, next, handles));
    }

    private static boolean matches(VersionSummary row, String handle, String needle) {
        return contains(row.draftName(), needle)
                || contains(row.version(), needle)
                || contains(row.note(), needle)
                || contains(handle, needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * @param access  the resolved access, so the controller can name the skill without a second lookup
     * @param counts  the unfiltered visible set, by derived status; {@code all} is every visible row
     * @param next    opaque cursor for the next page, or null on the last one
     * @param handles submitter ids to handles, for rendering
     */
    public record Page(Access access, List<VersionSummary> versions, Map<String, Integer> counts,
            String next, Map<String, String> handles) {
    }
}
