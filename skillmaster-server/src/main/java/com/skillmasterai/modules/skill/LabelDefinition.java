package com.skillmasterai.modules.skill;

/**
 * One value of one platform label dimension (v65, ADR 0192).
 *
 * <p>{@code id} is the stable identity and is also the wire key ({@code dimension:value}). The
 * short name is what a capsule draws; the full sentence is for extraction evidence, the CLI and
 * operators, never for a capsule or a tooltip.
 */
public record LabelDefinition(String id, String dimensionKey, String dimensionName, String value,
        String shortName, String fullName, String group, boolean filterable, String state,
        int sortOrder) {
}
