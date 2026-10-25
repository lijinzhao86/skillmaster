package com.skillmasterai.modules.retrieval;

import java.util.Optional;

/**
 * Where M13 gets the text it embeds, without reading anybody else's table.
 *
 * <p><strong>Implemented by M12, declared here.</strong> The text lives on {@code skill}, which M13
 * may not name (§2.5 rule 1). The obvious alternative — M13 queries {@code skill} itself — would
 * put that table in a statement outside its owner's package. So the dependency points this way:
 * M12 implements this interface and M13 only knows the interface. That is also why the direction
 * of the call at publish time can be M12 → M13 without a cycle.
 *
 * <p>The text is the <em>published</em> skill's: name, title, description and when-to-use. Drafts
 * are not embedded (decided 2026-10-25): a skill has one vector, and a draft is not what search
 * shows. A skill that has never been published therefore has no vector, and a keyword search is
 * the only way to find it — which is exactly what {@code semantic=false} is for.
 */
public interface EmbeddingTextSource {

    /**
     * @return the text of the skill's current published version, or empty when the skill is
     *         missing, deleted, or has never been published
     *
     * <p><strong>Just the text.</strong> The skill has one published text at a time, so there is no
     * version to name and nothing for the caller to compare — "is this the text we already
     * indexed?" is answered by the hash of this string.
     */
    Optional<String> publishedText(String skillId);
}
