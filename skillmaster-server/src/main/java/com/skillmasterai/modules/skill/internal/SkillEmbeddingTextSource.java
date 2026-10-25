package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.modules.retrieval.EmbeddingTextSource;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The embedding text, read where it lives: M12's own table, through M13's interface.
 *
 * <p><strong>Why the implementation is here and the interface is there.</strong> M13 may not name
 * {@code skill} (§2.5 rule 1, enforced by {@code TableOwnershipTest}); M12 may not be reached for
 * its repository. An interface owned by the consumer and implemented by the owner is the
 * arrangement that satisfies both without either module reaching into the other.
 *
 * <p>The text is what a keyword search already reads — {@code name}, {@code title},
 * {@code description}, {@code when_to_use} — and nothing else. Bodies and files stay out for the
 * same reason M8 keeps them out: embedding them would let a skill's content be reconstructed from
 * its vector's neighbours.
 *
 * <p><strong>Only a published skill has text.</strong> {@code current_version_id} null means every
 * version is a draft, and a draft's description is not what anybody searches for; returning empty
 * is what keeps drafts out of the index instead of embedding something nobody may see.
 */
public final class SkillEmbeddingTextSource implements EmbeddingTextSource {

    private final JdbcClient jdbc;

    public SkillEmbeddingTextSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> publishedText(String skillId) {
        return jdbc.sql("""
                SELECT name, title, description, when_to_use
                  FROM skill
                 WHERE id = :skillId
                   AND deleted_at IS NULL
                   AND current_version_id IS NOT NULL
                """)
                .param("skillId", skillId)
                .query((rs, rowNum) -> compose(rs.getString("name"), rs.getString("title"),
                        rs.getString("description"), rs.getString("when_to_use")))
                .optional();
    }

    /**
     * One string, because an embedding is one vector.
     *
     * <p>Each field is labelled rather than concatenated bare: without the label a title and a
     * description run together into a phrase the author never wrote, and the vector starts
     * answering for text that does not exist. Empty fields are dropped so a skill without a
     * {@code when_to_use} does not get a dangling label.
     */
    static String compose(String name, String title, String description, String whenToUse) {
        StringJoiner out = new StringJoiner("\n");
        add(out, "name", name);
        add(out, "title", title);
        add(out, "description", description);
        add(out, "when to use", whenToUse);
        return out.toString();
    }

    private static void add(StringJoiner out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.add(label + ": " + value.strip());
        }
    }
}
