package com.skillmasterai.usecase.model;

import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;
import java.util.Map;

/**
 * One skill as its author-plane page shows it, already filtered to the caller's rung (ADR 0051).
 *
 * <p><strong>What is here varies by rung, and the two facts that say how are carried rather than
 * re-derived.</strong> {@code versions} is what the caller may see — published only below
 * {@code write}, drafts included from there up — and {@code filesVisible} says whether the selected
 * version's manifest may be shown at all ({@code browse} and above). The page renders the same
 * shape at every rung; these two fields are what make it honest at each one.
 *
 * <p><strong>{@code selected} may be null, and that is an ordinary answer.</strong> A
 * {@code browse}-level caller on a skill whose versions are all drafts gets the skill's L1 and no
 * version: the empty tree ADR 0051 §后果 keeps apart from the consumption plane's 404. An explicit
 * pin that names nothing is a 404 instead, and never reaches this object.
 *
 * <p>There is no equivalent on the consumption plane, and the difference is the whole of ADR 0031:
 * {@link SubmittedSkill} and {@link com.skillmasterai.modules.distribution.SkillDetail} describe one
 * version, and the reader has no say in which.
 *
 * @param namespaceSlug the namespace the address named, which may be somebody else's — a shared or
 *                      public skill lives where its owner put it
 * @param visibility    private | public, from M12's {@code Access} — it is metadata, not content
 *                      (ADR 0053), so it no longer travels on the snapshot
 * @param publicRung    the rung a stranger gets when the skill is public (ADR 0048), or null when it
 *                      is private. Carried so 设置 › 常规 can read back the three-way choice
 * @param rung          the caller's own standing: {@code owner}, or one of the five ladder rungs.
 *                      Carried so the page can decide which sections exist without probing each
 *                      route for a 403 — the design draws nothing a rung cannot reach, and "nothing"
 *                      is only answerable if the page knows the rung. Not a disclosure: it is the
 *                      caller's own access to a skill they already opened
 * @param pendingRung   the rung the caller has asked for on this skill, or null when nothing is
 *                      waiting (ADR 0052). The information page draws 「等待处理」 from it, and it
 *                      has to come from the server rather than from the page's memory of its own
 *                      click — a reload is otherwise indistinguishable from "I never asked"
 * @param selected      the version the address pinned, or {@code latest} as this rung sees it; null
 *                      in the no-published-version case above
 * @param versions      every version the caller may see, newest first
 * @param submitters    submitter ids to handles, for the versions above. Resolved here rather than
 *                      on the wire because a list of ULIDs is a list the page cannot show — the
 *                      same reason the grant list resolves handles
 * @param filesVisible  whether the selected version's file manifest and body address may be shown
 */
public record AuthoredSkill(String namespaceSlug, String name, String title, String description,
        String visibility, String publicRung, String rung, String pendingRung,
        String frontmatterJson, SkillSnapshot selected, List<VersionSummary> versions,
        Map<String, String> submitters, boolean filesVisible, String offlineAt) {

    public AuthoredSkill {
        versions = List.copyOf(versions);
        submitters = Map.copyOf(submitters);
    }
}
