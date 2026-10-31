package com.skillmasterai.usecase;

import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.SkillCatalogService.CatalogRow;
import com.skillmasterai.modules.skill.SkillTagService;
import com.skillmasterai.usecase.model.SkillCardView;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Puts the three halves of a card together: the row, its namespace slug, and its tags.
 *
 * <p><strong>Three modules meet here, which is why this is a use-case-layer component.</strong> The
 * row is M12's, the slug is M4's, and the tags are M12's again. A repository cannot produce the
 * slug, and a controller must not be where a listing is decided.
 *
 * <p><strong>Tags are fetched in two batches for the whole page.</strong> The alternative — a
 * subquery per card — would put the number of round trips in the hands of the page size.
 *
 * <p><strong>Private tags follow the entry.</strong> A row whose library entry is not effective
 * carries none: the tags still exist in the database, but the product does not show them until the
 * entry works again (PRD §库与标签的生命周期).
 */
@Component
public class SkillCardAssembler {

    private final NamespaceService namespaces;
    private final SkillTagService tags;

    public SkillCardAssembler(NamespaceService namespaces, SkillTagService tags) {
        this.namespaces = namespaces;
        this.tags = tags;
    }

    public List<SkillCardView> assemble(List<CatalogRow> rows, String userId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> slugs = namespaces.slugsOf(
                rows.stream().map(CatalogRow::namespaceId).distinct().toList());
        List<String> ids = rows.stream().map(CatalogRow::id).toList();
        Map<String, List<SkillTagService.PublicTagView>> publicTags =
                tags.publicTagsOfSkills(ids);
        Map<String, List<SkillTagService.MyTagView>> myTags = tags.myTagsOfSkills(userId, ids);

        return rows.stream().map(row -> new SkillCardView(
                row.id(),
                slugs.getOrDefault(row.namespaceId(), row.namespaceId()),
                row.name(),
                row.description(),
                row.whenToUse(),
                row.source(),
                /* **What the caller may do, not just what was granted** (v35): the higher of the
                   grant and the rung the skill hands to everybody. The order of the ladder is
                   `GrantRole`'s alone, so it is folded there rather than in SQL. Null for the
                   owner — owning it is what "no namespace prefix" already says. Null too when the
                   caller has no relationship to the skill at all (`source` is null, the discovery
                   plane): "your rung" is a library fact, and handing back the public rung there
                   would be a different claim wearing the same field. */
                row.source() == null || "created".equals(row.source())
                        ? null
                        : GrantRole.higher(row.grantRole(), row.publicRung()),
                row.state(),
                row.version(),
                row.updatedAt(),
                row.deletedAt(),
                row.categoryKey() == null ? null
                        : new SkillCardView.Category(row.categoryKey(), row.categoryName()),
                publicTags.getOrDefault(row.id(), List.of()).stream()
                        .map(tag -> new SkillCardView.PublicTag(tag.key(), tag.name()))
                        .toList(),
                row.active()
                        ? myTags.getOrDefault(row.id(), List.of()).stream()
                                .map(tag -> new SkillCardView.MyTag(tag.id(), tag.name()))
                                .toList()
                        : List.of())).toList();
    }
}
