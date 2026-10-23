package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.SkillTagService.ManagedPublicTagRow;
import com.skillmasterai.modules.skill.SkillTagService.MyTagRow;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two tabs of the console's tag page (v14).
 *
 * <p><strong>Two lists with two counts that are not the same number.</strong> The private tab counts
 * attachments whose entry is effective for the owner; the public tab counts the caller's own
 * created, effective skills that carry the word. The second is the author's view of their own
 * words — somebody else's skill carrying "pdf" is not the caller's to manage (PRD §展示与计数口径).
 *
 * <p>Public tags are read-only here: attaching and detaching happen on the skill's information
 * page or through the CLI, not on this list.
 */
@Component
public class WebTagsUseCase {

    private final NamespaceService namespaces;
    private final SkillTagService tags;
    private final MyTagDefinitionUseCase definitions;

    public WebTagsUseCase(NamespaceService namespaces, SkillTagService tags,
            MyTagDefinitionUseCase definitions) {
        this.namespaces = namespaces;
        this.tags = tags;
        this.definitions = definitions;
    }

    /** The default tab: every private definition, its sentence, and its effective count. */
    @Transactional(readOnly = true)
    public List<MyTagRow> myTags(String q, AuthenticatedSubject subject) {
        return definitions.list(q, null, subject);
    }

    /** The public tab: the words this person used on their own effective skills, read-only. */
    @Transactional(readOnly = true)
    public List<ManagedPublicTagRow> publicTags(String q, AuthenticatedSubject subject) {
        return tags.managedPublicTags(Callers.of(namespaces, subject), q);
    }
}
