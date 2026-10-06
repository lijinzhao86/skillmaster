package com.skillmasterai.api;

/**
 * Which version a write acts on: §4.1's {@code @N}, sent in the body rather than in the path.
 *
 * <p>§4.3's rule for the API plane — a write names the skill, and the version is produced or moved
 * by the operation — applies here too, and for the same reason: a version suffix in the path would
 * make a write look like a read of one version. The number is the immutable alias, not the row id,
 * because it is what the author sees on the page and what the API hands out everywhere else.
 */
record VersionChoice(int number) {
}
