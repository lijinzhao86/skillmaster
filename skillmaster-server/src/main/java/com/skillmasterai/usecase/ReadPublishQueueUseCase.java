package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillCatalogService;
import com.skillmasterai.modules.version.PublishDraft;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.usecase.model.PublishQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every draft waiting on a skill this caller may publish from — the publish queue (v18).
 *
 * <p><strong>Two modules, one answer, and no join.</strong> Which skills are on the page is M12's
 * question (it owns the rung and the projections); which drafts they hold is M7's (it owns the
 * rows). The ownership rule forbids one statement naming both tables, so this composes them: one
 * listing, one batch read for every skill's drafts, and the two zipped here. Asking per skill would
 * be up to five hundred round trips for a page a person simply opens.
 *
 * <p><strong>The row is a draft, not a skill.</strong> One skill with three waiting drafts is three
 * rows on the page, which is why the drafts come back grouped rather than counted.
 *
 * <p><strong>Truncation is a fact about this answer, not a cursor.</strong> A skill the answer does
 * not carry is a skill the page cannot act on, so the page says so. One row more than the limit is
 * asked for, because "exactly 500" and "cut at 500" are both 500 rows and the page cannot tell them
 * apart otherwise.
 */
@Component
public class ReadPublishQueueUseCase {

    /**
     * Its own constant rather than {@code ListAuthoredSkillsUseCase.LIMIT}: that one is private, and
     * the two numbers answer different questions (everything I have, versus one screen of what is
     * waiting). They may move apart, and sharing one would make that impossible to express.
     */
    private static final int LIMIT = 500;

    private final NamespaceService namespaces;
    private final SkillCatalogService catalog;
    private final SkillVersionService versions;

    public ReadPublishQueueUseCase(NamespaceService namespaces, SkillCatalogService catalog,
            SkillVersionService versions) {
        this.namespaces = namespaces;
        this.catalog = catalog;
        this.versions = versions;
    }

    @Transactional(readOnly = true)
    public PublishQueue queue(AuthenticatedSubject subject) {
        Namespace own = namespaces.personalNamespaceOf(subject.userId());
        Caller caller = new Caller(subject.userId(), own.id());

        List<SkillCatalogService.QueueRow> rows = catalog.publishQueue(LIMIT + 1, caller);
        boolean truncated = rows.size() > LIMIT;
        if (truncated) {
            rows = List.copyOf(rows.subList(0, LIMIT));
        }
        if (rows.isEmpty()) {
            return new PublishQueue(List.of(), truncated);
        }

        Map<String, List<PublishDraft>> drafts = versions
                .draftsOf(rows.stream().map(SkillCatalogService.QueueRow::id).toList())
                .stream()
                .collect(Collectors.groupingBy(PublishDraft::skillId));
        Map<String, String> slugs = namespaces.slugsOf(rows.stream()
                .map(SkillCatalogService.QueueRow::namespaceId).collect(Collectors.toSet()));

        List<PublishQueue.Skill> skills = new ArrayList<>(rows.size());
        for (SkillCatalogService.QueueRow row : rows) {
            List<PublishDraft> waiting = drafts.getOrDefault(row.id(), List.of());
            if (waiting.isEmpty()) {
                // The projection said there was a draft and the batch read found none: a discard
                // committed between the two reads. A skill with nothing waiting is the same answer
                // as not being on the page at all.
                continue;
            }
            skills.add(new PublishQueue.Skill(
                    slugs.get(row.namespaceId()),
                    row.name(),
                    row.currentVersion(),
                    row.currentSubmittedAt(),
                    waiting.stream()
                            .map(draft -> new PublishQueue.Draft(draft.code(), draft.name(),
                                    draft.releaseType(), draft.submittedAt()))
                            .toList()));
        }
        return new PublishQueue(List.copyOf(skills), truncated);
    }
}
