package com.skillmasterai.modules.skill.internal;

import java.util.List;
import java.util.Map;

/**
 * The filters the library and discovery listings share (v14).
 *
 * <p>They live here rather than in each repository because "which rows match" must be one answer:
 * the API's keyset listing, the browser's offset listing, the recommendation batch and the three
 * counters all read the same clauses. A second copy would be a second definition of a multi-word
 * query, and the first symptom would be a count that disagrees with a page.
 *
 * <p>Every clause names only M12's tables, and every value travels as a bound parameter. The
 * methods append to a caller-owned parameter map because the SQL fragments and their parameters
 * are one idea — splitting them is how a placeholder comes to have no value.
 */
final class SqlFilters {

    private SqlFilters() {
    }

    /**
     * The text query, one AND per word: every word has to match somewhere.
     *
     * <p>Within a word the fields are alternatives — name, title, description, {@code when_to_use},
     * and the skill's public tag names (ADR 0084). The tag test is an {@code EXISTS} rather than a
     * join because a skill may carry several tags, and a join would multiply its row.
     */
    static String text(String queryText, Map<String, Object> params) {
        if (queryText == null || queryText.isBlank()) {
            return "";
        }
        StringBuilder clauses = new StringBuilder();
        String[] words = queryText.strip().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String name = "word" + i;
            params.put(name, LikePattern.containing(words[i]));
            clauses.append(" AND ( s.name ILIKE :").append(name).append(' ').append(LikePattern.ESCAPE_CLAUSE)
                    .append(" OR s.title ILIKE :").append(name).append(' ').append(LikePattern.ESCAPE_CLAUSE)
                    .append(" OR s.description ILIKE :").append(name)
                    .append(' ').append(LikePattern.ESCAPE_CLAUSE)
                    .append(" OR s.when_to_use ILIKE :").append(name)
                    .append(' ').append(LikePattern.ESCAPE_CLAUSE)
                    .append(" OR EXISTS (SELECT 1 FROM skill_public_tag pt")
                    .append(" WHERE pt.skill_id = s.id AND pt.tag_name ILIKE :").append(name)
                    .append(' ').append(LikePattern.ESCAPE_CLAUSE).append(") )");
        }
        return clauses.toString();
    }

    /** "Any of these shelves": a skill is on one, so two categories widen the result. */
    static String categories(List<String> categoryKeys, Map<String, Object> params) {
        if (categoryKeys == null || categoryKeys.isEmpty()) {
            return "";
        }
        params.put("categoryKeys", categoryKeys.toArray(String[]::new));
        return " AND s.category_key = ANY(:categoryKeys)";
    }

    /** "All of these words": each key is its own EXISTS, so two tags narrow. */
    static String publicTags(List<String> publicTagKeys, Map<String, Object> params) {
        if (publicTagKeys == null || publicTagKeys.isEmpty()) {
            return "";
        }
        StringBuilder clauses = new StringBuilder();
        for (int i = 0; i < publicTagKeys.size(); i++) {
            String name = "tagKey" + i;
            params.put(name, publicTagKeys.get(i));
            clauses.append(" AND EXISTS (SELECT 1 FROM skill_public_tag pt")
                    .append(" WHERE pt.skill_id = s.id AND pt.tag_key = :").append(name).append(")");
        }
        return clauses.toString();
    }

    /** "All of these private tags", addressed by definition id; owner checked in the same clause. */
    static String myTags(List<String> definitionIds, Map<String, Object> params) {
        if (definitionIds == null || definitionIds.isEmpty()) {
            return "";
        }
        StringBuilder clauses = new StringBuilder();
        for (int i = 0; i < definitionIds.size(); i++) {
            String name = "myTagId" + i;
            params.put(name, definitionIds.get(i));
            clauses.append(" AND EXISTS (SELECT 1 FROM my_tag_attachment a")
                    .append(" JOIN my_tag_definition d ON d.id = a.definition_id")
                    .append(" WHERE a.skill_id = s.id AND a.definition_id = :").append(name)
                    .append(" AND d.user_id = :callerId)");
        }
        return clauses.toString();
    }
}
