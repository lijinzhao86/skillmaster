package com.skillmasterai.api;

/**
 * Which version a write acts on: §4.1's version suffix, sent in the body rather than in the path.
 *
 * <p>§4.3's rule for the API plane — a write names the skill, and the version is produced or moved
 * by the operation — applies here too, and for the same reason: a version suffix in the path would
 * make a write look like a read of one version. The value is the same suffix an address carries
 * ({@code 1.2.3} or {@code sha256:…}), not the row id, because it is what the author sees on the
 * page and what the API hands out everywhere else.
 *
 * <p><strong>A suffix rather than a version name</strong>, so that a version whose author declared
 * no name can still be published and discarded (ADR 0033). Its digest is always there; its name may
 * not be. Parsed by the same grammar the path uses, so the two cannot drift.
 */
record VersionChoice(String version) {
}
