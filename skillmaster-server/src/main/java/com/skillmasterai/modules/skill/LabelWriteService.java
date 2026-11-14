package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.LabelRepository;
import java.util.List;

/**
 * M12's write seam for the fixed rules (v72).
 *
 * <p><strong>What it refuses, and why here.</strong> The vocabulary is M12's, so "is this a real
 * value, and is it one this caller may write" is answered next to the table rather than at the
 * HTTP edge. Rules produce {@code runtime} only; a request naming another dimension is a bug in
 * the caller, not a product feature, and it fails loudly.
 */
public final class LabelWriteService {

    private final LabelRepository repository;

    public LabelWriteService(LabelRepository repository) {
        this.repository = repository;
    }

    /** @return how many rows the final table now has for this skill */
    public int replaceRuntimeLabels(String skillId, List<String> definitionIds) {
        List<String> unique = definitionIds.stream().distinct().toList();
        for (String definitionId : unique) {
            String dimension = repository.dimensionOf(definitionId)
                    .orElseThrow(() -> new UnknownLabelException(definitionId,
                            "no such label definition"));
            if (!"runtime".equals(dimension)) {
                throw new UnknownLabelException(definitionId,
                        "only runtime labels may be written through this seam");
            }
        }
        repository.replace(skillId, unique);
        return unique.size();
    }
}
