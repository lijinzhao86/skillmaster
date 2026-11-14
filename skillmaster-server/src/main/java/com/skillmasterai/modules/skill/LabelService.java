package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.LabelRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M12's label seam (v65): the vocabulary, a skill's displayed claims, and its outcome.
 *
 * <p><strong>No policy here beyond the read model.</strong> Extraction decides what to write;
 * this class only answers what a read may show. The definition id is also the wire key, so a
 * filter and a claim cannot drift into two spellings.
 */
public final class LabelService {

    private final LabelRepository repository;

    public LabelService(LabelRepository repository) {
        this.repository = repository;
    }

    /** The filterable vocabulary: enabled and stopped, retired absent. */
    public List<LabelDefinition> vocabulary() {
        return repository.vocabulary();
    }

    /** @return the definition id behind {@code dimension:value}, or empty for an unknown word */
    public Optional<String> definitionIdOf(String key) {
        return repository.definitionIdOf(key);
    }

    /** The displayed claims of one skill, current version and operator-confirmed rows only. */
    public List<LabelValue> labelsOf(String skillId) {
        return repository.labelsOfSkills(List.of(skillId)).getOrDefault(skillId, List.of());
    }

    /** One page's worth of claims, in one query. */
    public Map<String, List<LabelValue>> labelsOfSkills(Collection<String> skillIds) {
        return repository.labelsOfSkills(skillIds);
    }

    /** The skill's one-line expected result, or null when it is absent or not for this version. */
    public String outcomeOf(String skillId) {
        return repository.outcomesOf(List.of(skillId)).get(skillId);
    }

    /** One page's worth of outcomes, in one query. */
    public Map<String, String> outcomesOf(Collection<String> skillIds) {
        return repository.outcomesOf(skillIds);
    }
}
