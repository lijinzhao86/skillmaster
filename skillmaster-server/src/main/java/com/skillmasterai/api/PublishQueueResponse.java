package com.skillmasterai.api;

import com.skillmasterai.usecase.model.PublishQueue;
import java.util.List;

/**
 * The publish queue over the wire (v18).
 *
 * <p>The shape is the page's table: a skill, the version that is live now, and the drafts waiting on
 * it. Grouped by skill rather than flat because the page draws one header line per skill; flattened
 * here, the page would have to regroup what the server already knew.
 *
 * @param truncated whether more skills were waiting than this answer carries — the page must say so
 *                  rather than letting a cut read as "nothing else has drafts"
 */
public record PublishQueueResponse(List<Skill> skills, boolean truncated) {

    /**
     * @param current null when nothing has been published yet — which is not the same as a version
     *                with no number, and the page renders the two differently (TD §接口)
     */
    public record Skill(String namespace, String name, Current current, List<Draft> drafts) {
    }

    /** @param version null on a row published before version names existed; the page writes {@code —} */
    public record Current(String version, String submittedAt) {
    }

    /** @param code the pin, bare — a batch request passes it back unchanged (ADR 0063) */
    public record Draft(String code, String name, String releaseType, String submittedAt) {
    }

    public static PublishQueueResponse of(PublishQueue queue) {
        return new PublishQueueResponse(
                queue.skills().stream()
                        .map(skill -> new Skill(
                                skill.namespace(),
                                skill.name(),
                                skill.currentVersion() == null && skill.currentSubmittedAt() == null
                                        ? null
                                        : new Current(skill.currentVersion(),
                                                skill.currentSubmittedAt()),
                                skill.drafts().stream()
                                        .map(draft -> new Draft(draft.code(), draft.name(),
                                                draft.releaseType(), draft.submittedAt()))
                                        .toList()))
                        .toList(),
                queue.truncated());
    }
}
