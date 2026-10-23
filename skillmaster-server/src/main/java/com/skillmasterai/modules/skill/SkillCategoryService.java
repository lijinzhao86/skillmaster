package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.CategoryRepository;
import java.util.List;
import java.util.Optional;

/**
 * M12's shelf seam: the platform vocabulary and a skill's one assignment (v14, ADR 0078).
 *
 * <p><strong>The vocabulary is read-only here and everywhere.</strong> Categories change by
 * migration; a user only chooses one or clears it. The write side is a single nullable column on
 * {@code skill}, so there is no second table and no second identity.
 *
 * <p><strong>Retired names stay taken.</strong> {@link #nameTaken} reads the whole vocabulary
 * including retired words: retiring a word removes it from the picker, not from the namespace of
 * names, and a private tag must never take a word a shelf used to carry.
 */
public final class SkillCategoryService {

    private final CategoryRepository repository;

    public SkillCategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    /** The picker's words: enabled and stopped, in vocabulary order, retired absent. */
    public List<CategoryRow> vocabulary() {
        return repository.withPublicTotals();
    }

    public Optional<CategoryRow> byKey(String key) {
        return repository.byKey(key);
    }

    /** Every word, retired included — for resolving a filter name without computing counts. */
    public List<CategoryRow> all() {
        return repository.all();
    }

    /** The skill's current shelf, or null for none. */
    public String categoryOf(String skillId) {
        return repository.categoryOf(skillId).orElse(null);
    }

    /**
     * Whether a private or public tag may not use this normalized word.
     *
     * <p>Compared on the normalized form rather than the raw name, because the rule is about
     * reading: "文档与演示" and a full-width spelling of it are one word to a person.
     */
    public boolean nameTaken(String nameKey) {
        for (CategoryRow row : repository.all()) {
            if (TagNames.key(TagNames.display(row.name())).equals(nameKey)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether the assignment changed; a concurrent identical write answers false */
    public boolean set(String skillId, String key) {
        return repository.set(skillId, key);
    }

    /** @return whether a shelf was removed; a concurrent clear answers false */
    public boolean clear(String skillId) {
        return repository.clear(skillId);
    }

    /**
     * @param state enabled / stopped / retired. Stopped words are shown and cannot be newly
     *              assigned; retired ones are not shown at all
     */
    public record CategoryRow(String key, String name, String state, long publicTotal) {
    }
}
