package com.skillmasterai.usecase;

import com.skillmasterai.modules.skill.SkillCategoryService;
import com.skillmasterai.modules.skill.SkillCategoryService.CategoryRow;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The category vocabulary with its public counts (v14).
 *
 * <p>One call gives the whole list: ten words today, so a page or a cursor would be machinery for a
 * set that fits in one response. Enabled and stopped words are both returned (the picker greys the
 * stopped ones, the CLI writes {@code （停用）}); retired words are not returned at all.
 *
 * <p>{@code public_total} counts discoverable skills only — the same "公域" the CLI column names,
 * not the management page's "used in" (PRD §展示与计数口径).
 */
@Component
public class ListCategoriesUseCase {

    private final SkillCategoryService categories;

    public ListCategoriesUseCase(SkillCategoryService categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<CategoryRow> list() {
        return categories.vocabulary();
    }
}
