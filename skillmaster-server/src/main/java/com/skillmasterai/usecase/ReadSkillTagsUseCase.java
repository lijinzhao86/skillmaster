package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillCategoryService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.modules.skill.SkillTagService.CategoryView;
import com.skillmasterai.modules.skill.SkillTagService.MyTags;
import com.skillmasterai.modules.skill.SkillTagService.TagGroups;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three groups of words on one skill (v14).
 *
 * <p><strong>Order is the product's: category, public tags, private tags.</strong> The first two are
 * readable by anyone who can see the skill; the third only while this caller's library entry is
 * effective.
 *
 * <p><strong>The private group reports why it is missing rather than simply being absent.</strong>
 * {@code not_in_library} and {@code dormant} send a person to two different next steps, and the
 * console shows a different sentence for each. An invisible skill is still a 404 and says nothing
 * about existing (TD §响应形状).
 */
@Component
public class ReadSkillTagsUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillTagService tags;
    private final SkillCategoryService categories;

    public ReadSkillTagsUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillTagService tags, SkillCategoryService categories) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.tags = tags;
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public Optional<TagGroups> read(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        return Organize.resolve(namespaces, metadata, namespaceSlug, name, subject).map(target -> {
            var standing = target.standing();
            CategoryView category = categoryOf(standing.skillId());
            List<SkillTagService.PublicTagView> publicTags =
                    tags.publicTagsOfSkill(standing.skillId());

            MyTags myTags;
            if (!standing.inLibrary()) {
                myTags = new MyTags(false, "not_in_library", List.of());
            } else if (!standing.active()) {
                myTags = new MyTags(false, "dormant", List.of());
            } else {
                myTags = new MyTags(true, null,
                        tags.myTagsOnSkill(subject.userId(), standing.skillId()));
            }
            var library = new SkillTagService.LibraryView(
                    standing.source(),
                    standing.active(),
                    !standing.inLibrary() && standing.discoverable(),
                    "joined".equals(standing.source()));
            return new TagGroups(category, publicTags, myTags, library);
        });
    }

    private CategoryView categoryOf(String skillId) {
        String key = categories.categoryOf(skillId);
        if (key == null) {
            return null;
        }
        return categories.byKey(key)
                .map(row -> new CategoryView(row.key(), row.name()))
                .orElse(new CategoryView(key, null));
    }
}
