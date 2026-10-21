package com.skillmasterai.usecase;

import java.nio.charset.StandardCharsets;

/**
 * The smallest valid {@code SKILL.md}: frontmatter with the two required fields, and a title.
 *
 * <p><strong>One implementation, two callers.</strong> v8 writes it when a draft is created from
 * an empty base; v9 writes it when a skill is created from the browser form. Both produce a
 * document that goes through the same {@code SkillDocument.parse}, so the quoting rules here are
 * the difference between "a description YAML carries" and a refusal at the far end.
 */
final class SkillMdTemplate {

    private SkillMdTemplate() {
    }

    /**
     * @param name required. A null is rendered as an empty scalar rather than dereferenced here:
     *             {@code SkillDocument.parse} is the one place that says which field is missing
     *             ({@code required_field_missing}), and the browser route takes its fields from a
     *             JSON body, where an omitted key is simply null.
     */
    static byte[] render(String name, String description, String title) {
        String skillName = name == null ? "" : name;
        String body = "---\nname: " + yamlQuote(skillName) + "\ndescription: "
                + yamlQuote(description == null ? "" : description) + "\n---\n\n# "
                + (title == null || title.isBlank() ? skillName : title) + "\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A double-quoted YAML scalar. Newlines and tabs are escaped rather than written raw: a raw line
     * break would end the scalar and change the document's shape, and the parse above would then
     * refuse a description that is perfectly ordinary in the skill row.
     */
    private static String yamlQuote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    // C0, DEL and the C1 range: YAML treats U+0085 as a line break and folds it
                    // inside a double-quoted scalar, so it has to travel as an escape too.
                    if (character < 0x20 || character == 0x7f
                            || (character >= 0x80 && character <= 0x9f)) {
                        quoted.append(String.format("\\x%02X", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
