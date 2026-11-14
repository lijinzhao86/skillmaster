package com.skillmasterai.ops.labels;

import java.util.List;

/**
 * The gate between "a rule matched" and "a value is written": the quote has to be findable in the
 * facts it claims to come from.
 *
 * <p><strong>Why check what the labeler just built.</strong> The labeler builds evidence from the
 * same lines it matched, so a failure here is a bug, not bad input. Checking anyway is what makes
 * the invariant testable: a rule that starts quoting something else — a summary, a line from
 * another file — fails the same assertion a corrupted claim does.
 */
final class EvidenceCheck {

    private EvidenceCheck() {
    }

    static boolean verify(SkillFacts facts, LabelClaim claim) {
        Evidence evidence = claim.evidence();
        if (evidence.line() == 0) {
            return facts.files().stream()
                    .anyMatch(file -> file.relpath().equals(evidence.quote()));
        }
        if (!"SKILL.md".equals(evidence.file())) {
            return false;
        }
        List<String> lines = facts.skillMd().lines()
                .map(line -> line.endsWith("\r") ? line.substring(0, line.length() - 1) : line)
                .toList();
        if (evidence.line() > lines.size()) {
            return false;
        }
        return lines.get(evidence.line() - 1).strip().equals(evidence.quote().strip());
    }
}
