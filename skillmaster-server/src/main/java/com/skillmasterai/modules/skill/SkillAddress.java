package com.skillmasterai.modules.skill;

/**
 * A skill's address without its content: the namespace that makes the name unique, and the name.
 *
 * <p>M12 publishes this rather than the row because a caller that has only a {@code skill_id} —
 * today, a notification — needs to show where it points without reading {@code skill}.
 */
public record SkillAddress(String namespaceId, String name) {
}
