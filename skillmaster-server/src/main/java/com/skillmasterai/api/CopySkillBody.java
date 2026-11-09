package com.skillmasterai.api;

/**
 * The body both copy routes take (v54).
 *
 * <p>The source is in the path; the destination name is here because the destination itself is
 * never named by the request — it is always the caller's personal namespace, the same rule
 * {@code skill create} follows. {@code version} is optional and names a published version; absent
 * means the live one.
 */
record CopySkillBody(String name, String version) {
}
