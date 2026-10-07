package com.skillmasterai.modules.distribution;

import java.util.Objects;

/**
 * How much of a comparison may be rendered before it stops being a page and starts being a payload.
 *
 * <p>A diff is the one response in this system with no natural bound: two versions of a skill that
 * share nothing produce output proportional to their combined size, and a skill may legally hold 512
 * files and 16 MiB (M5's ceiling). Serving that as one JSON document is not a slow page — it is a
 * page that does not arrive, on a request whose cost the requester did not ask for. So the caps are
 * here rather than in the renderer, and what they cut is <em>reported</em> rather than left to look
 * like the whole story: see {@link SkillDiff#truncated}.
 *
 * <p>Configuration rather than constants, for the reason §3.4 gives about the ranking weights and
 * §3.1 about token lifetimes: these are numbers a deployment has an opinion about, and tuning them
 * should not need a release. They are also not security limits — nothing here protects an invariant
 * — so a deployment that raises all three has not weakened anything, it has chosen a bigger page.
 *
 * <p>What each one means, precisely, because two of them sound alike:
 *
 * <ul>
 *   <li>{@code maxFileBytes} — one file's size in the version being compared. Past it the file is
 *       <em>not line-diffed at all</em>. It is also the bound on how much text is decoded and how
 *       long the line algorithm has to run, which is why it is a byte count and not a line count:
 *       the cost belongs to the input, not to how it is measured.</li>
 *   <li>{@code maxLines} — the total number of hunk lines in one response. This is what the client
 *       actually has to render, so it is the cap that keeps the response proportional to the page
 *       rather than to the skill.</li>
 *   <li>{@code maxFiles} — how many files are line-diffed. A skill's file <em>list</em> is not
 *       capped: statuses are read from two manifests and cost nothing, so a page can honestly say
 *       "and 411 more files changed" instead of showing an arbitrary hundred.</li>
 * </ul>
 *
 * @param maxFileBytes one file's ceiling, in bytes. Must exceed 0
 * @param maxLines     hunk lines per response. Must exceed 0
 * @param maxFiles     files line-diffed per response. Must exceed 0
 */
public record DiffLimits(int maxFileBytes, int maxLines, int maxFiles) {

    public DiffLimits {
        Objects.requireNonNull(maxFileBytes, "maxFileBytes");
        if (maxFileBytes <= 0 || maxLines <= 0 || maxFiles <= 0) {
            // Zero is refused rather than treated as "no limit": a cap of zero is a configuration
            // mistake that would silently turn every comparison into a list of filenames, and
            // "unlimited" is not a thing this class offers — an unbounded diff is the problem it
            // exists to solve.
            throw new IllegalArgumentException("diff limits must be positive: maxFileBytes="
                    + maxFileBytes + " maxLines=" + maxLines + " maxFiles=" + maxFiles);
        }
    }
}
