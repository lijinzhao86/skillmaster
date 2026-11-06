package com.skillmasterai.modules.skill;

/**
 * Where a skill came from: one origin, and optionally mirrors of the same content.
 *
 * <p>The row is deliberately generic. A new platform is a new {@code kind} and a new adapter; it
 * adds no column here and no branch in the page.
 */
public record SkillSource(String id, String skillId, String role, String kind, String locator,
        String path, String label, String url) {

    public static final String ORIGIN = "origin";
    public static final String MIRROR = "mirror";

    public boolean isOrigin() {
        return ORIGIN.equals(role);
    }
}
