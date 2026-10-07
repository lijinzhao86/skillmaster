package com.skillmasterai.api;

import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;

/**
 * §4.2's version list: which versions of one skill this caller may invoke.
 *
 * <p>It exists so that a client can pin deliberately rather than only by default. A bare address
 * means "whatever is current" (ADR 0033: {@code latest} is a dist-tag, and it moves), so without a
 * list there is no way to learn the name of a superseded version — and a superseded version is
 * exactly what someone wants after a bad publish, because it is still published and still
 * addressable.
 *
 * <p><strong>Published versions only, and the pointer is not the filter.</strong> A version the
 * pointer has moved off is the reason this endpoint exists, so filtering on {@code is_current} would
 * empty it. Drafts and discarded versions are absent because neither is addressable on this plane at
 * all (ADR 0031); the author's own version list is where those are news.
 *
 * <p>No pagination. A version is created by a person pressing submit and then pressing publish, so a
 * skill's list is bounded by human effort rather than by anything a client can race — which is why
 * this differs from the listing endpoint, where the same argument does not hold and a cursor does.
 *
 * @param versions newest submission first, as the author's own list is: "what did I just do" is the
 *                 question both lists answer, and neither sorts by version name
 */
public record SkillVersionsResponse(List<Version> versions) {

    /**
     * One version a client can pin.
     *
     * @param name        the author's version name, or null when they declared none (ADR 0033). A
     *                    null is not a hole: for such a version the digest below is the only thing it
     *                    can be addressed by, and it is always there
     * @param digest      prefixed with {@code sha256:}, as §4.2 shows every digest. This is a pin a
     *                    client may send, not merely a checksum — and for a nameless version the pin
     *                    it <em>has</em> to send
     * @param publishedAt when it first went live, RFC3339 UTC. The first time, not the last: a
     *                    version that was published, superseded and published again by a rollback has
     *                    one publication date, which is why this cannot be read off the pointer's
     *                    movements
     * @param isCurrent   whether the skill's pointer names it — the one a bare address resolves to.
     *                    False for every superseded version, all of which are still readable by their
     *                    own pin
     */
    public record Version(String name, String digest, String publishedAt, boolean isCurrent) {
    }

    public static SkillVersionsResponse of(List<VersionSummary> summaries) {
        return new SkillVersionsResponse(summaries.stream()
                .map(summary -> new Version(
                        summary.version(),
                        // Storage keeps the bare hex ADR 0005's formula produces; the API presents
                        // it prefixed, as §4.2 shows.
                        "sha256:" + summary.digest(),
                        // state_at on a published row is when it left draft, and only publishing
                        // does that — see markPublished's COALESCE.
                        summary.stateAt(),
                        summary.isCurrent()))
                .toList());
    }
}
