package com.skillmasterai.modules.skill;

/**
 * One displayed label claim on a skill (v65).
 *
 * <p>The read model returns only short names. {@code full} stays in the transport for the CLI and
 * operators, but the console deliberately does not render it (ADR 0192 #8).
 */
public record LabelValue(String key, String value, String label, String full, String group,
        /**
         * Whether this word may be filtered on. A dimension can be display-only while its
         * vocabulary is still being reviewed (v65 TD §扩展), and the interface must not draw a
         * filter link for a word the server would refuse as unknown.
         */
        boolean filterable) {
}
