package com.skillmasterai.modules.version;

import java.util.List;

/**
 * One skill as the author plane's page needs it, already filtered to the caller's rung (ADR 0051).
 *
 * <p><strong>The shape is the same at every rung; what varies is what is in it.</strong> At
 * {@code invoke} the answer carries the skill's L1 and its published versions, with no file
 * manifest; at {@code browse} the manifest joins them; at {@code write} drafts appear in both the
 * selection and the list. That is why this object exists instead of the caller reading the parts
 * separately and deciding for itself — the filtering is the same rule the access predicate enforces,
 * and two places applying it would be two places to forget one half.
 *
 * @param title the selected version's title when one resolved, and the skill row's copy otherwise.
 *        The fallback is the empty-tree case ADR 0051 keeps apart from a 404: a skill with nothing
 *        published still has a name and a description to show
 * @param description the selected version's description, or the row's copy in the same fallback
 * @param frontmatterJson the selected version's frontmatter, or the row's copy in the same fallback
 * @param selected the version the address pinned, or {@code latest} as this rung sees it. Null when
 *        nothing the caller may see resolves — a {@code browse}-level caller on a skill whose
 *        versions are all drafts. Null is an ordinary answer here, not a miss
 * @param versions every version the caller may see, newest first: drafts only from {@code write}
 *        up, published ones below it
 * @param filesVisible whether the file manifest is part of the answer — {@code browse} and above.
 *        Kept as its own fact rather than inferred from {@code selected}, because a caller below
 *        that rung can have a selected version and still must not receive its files
 */
public record AuthoredSkillView(
        String skillId,
        String namespaceId,
        String name,
        String title,
        String description,
        String frontmatterJson,
        SkillSnapshot selected,
        List<VersionSummary> versions,
        boolean filesVisible,
        String offlineAt) {

    public AuthoredSkillView {
        versions = List.copyOf(versions);
    }
}
