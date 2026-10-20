package com.skillmasterai.modules.ingest;

import java.util.Optional;

/**
 * The rules a path inside a skill has to obey, in one place (v7 TD §路径与上限).
 *
 * <p><strong>Extracted so the write path and the ingest path cannot disagree.</strong> A relpath a
 * submission would have refused must not become writable through {@code PUT}, and a relpath a write
 * accepts must not be one the zip reader would refuse. The two uses are the same question asked at
 * different times, so the answer lives here rather than in either caller.
 *
 * <p>The rules are §1.3's plus the ones the archive format itself forces: no absolute paths, no
 * {@code ..} segment (traversal), no backslash (a Windows zip's separator, which would be one
 * opaque segment here), no empty segment. Everything else — nested directories, a nested
 * {@code SKILL.md}, a file and a directory sharing a name — is permitted, because ingest has always
 * permitted it and a write path that tightened the rule unilaterally would make existing drafts
 * unwritable (v7 TD §路径与上限).
 *
 * <p><strong>{@link #check} returns the problem rather than raising it.</strong> The two callers
 * answer in different error vocabularies — an archive answers {@code invalid_upload}, a write route
 * answers {@code invalid_path} — and a rule that picked one would make the other either wrong or a
 * translation layer. The rule, the field and the issue travel together; the exception is the
 * caller's to choose.
 */
public final class SkillPaths {

    private SkillPaths() {
    }

    private static final String SEPARATOR = "/";
    private static final String PARENT = "..";

    /**
     * @param field where the caller should point a person at, which is the path itself for both
     * @param issue the stable reason a client can branch on
     */
    public record Issue(String field, String issue, String message) {
    }

    public static Optional<Issue> check(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of(new Issue("path", "empty_name", "a file needs a path inside the skill"));
        }
        if (name.startsWith(SEPARATOR)) {
            return Optional.of(new Issue(name, "absolute_path",
                    "a file's path must be relative to the skill root"));
        }
        if (name.contains("\\")) {
            return Optional.of(new Issue(name, "backslash_in_path",
                    "a file's path uses '/' as its separator; '\\' is not accepted"));
        }
        for (String segment : name.split(SEPARATOR, -1)) {
            if (segment.equals(PARENT)) {
                return Optional.of(new Issue(name, "path_traversal",
                        "a file's path cannot escape the skill root"));
            }
            if (segment.isEmpty()) {
                return Optional.of(new Issue(name, "empty_path_segment",
                        "a file's path has an empty segment"));
            }
        }
        return Optional.empty();
    }

    /**
     * The ingest path's spelling of the same rule: an {@link IngestException}, which the upload
     * route answers as {@code invalid_upload} with the issue in its details.
     */
    public static String validate(String name) {
        check(name).ifPresent(issue -> {
            throw new IngestException(issue.message(), issue.field(), issue.issue());
        });
        return name;
    }
}
