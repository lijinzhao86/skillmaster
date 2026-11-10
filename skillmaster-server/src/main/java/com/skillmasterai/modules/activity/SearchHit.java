package com.skillmasterai.modules.activity;

/**
 * One result of a search, in the order the caller saw it (v58).
 *
 * <p>The address is the display copy; the id is what correlation matches on, because a rename
 * after the search must not break the attribution.
 */
public record SearchHit(String skillId, String address) {
}
