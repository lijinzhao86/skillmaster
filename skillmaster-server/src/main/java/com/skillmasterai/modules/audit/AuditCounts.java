package com.skillmasterai.modules.audit;

import java.util.List;

/**
 * M10's read edge for other modules (v58).
 *
 * <p>The only question anyone asks of the write ledger is "how many versions were submitted, by
 * whom, on which day" — the daily rollup needs it to price contribution. The SQL stays here:
 * another module may not name {@code audit_event}.
 */
public interface AuditCounts {

    /** Submissions with {@code at ∈ [from, until)}, grouped by skill, person and UTC day. */
    List<Submission> submissions(String from, String until);

    /**
     * One skill's submissions in {@code [from, until)}, grouped by person — the range view's
     * contribution count. Uses the partial index over {@code (target_id, at)}.
     */
    List<SubmissionCount> submissionsOfSkill(String skillId, String from, String until);

    /**
     * @param skillId the skill that was submitted to
     * @param day     the UTC calendar day, {@code YYYY-MM-DD}
     * @param lastAt  the latest submission in that group
     */
    record Submission(String skillId, String actorUserId, String day, int versions, String lastAt) {
    }

    /** @param lastAt the latest submission by this person for this skill in the window */
    record SubmissionCount(String actorUserId, int versions, String lastAt) {
    }
}
