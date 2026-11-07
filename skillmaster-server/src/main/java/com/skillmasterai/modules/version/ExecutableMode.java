package com.skillmasterai.modules.version;

/**
 * Which files in a version may be executed: the ones under {@code scripts/}.
 *
 * <p><strong>The permission bits follow the path, not the file.</strong> A skill's code lives in
 * {@code scripts/} and nowhere else (that is the convention {@code skmr run} resolves against), so
 * "is this executable" is a question about where the file sits. Deriving it means no two ingests of
 * the same version can disagree: the same relpaths always produce the same modes, whatever the
 * upload's zip said, whatever the console editor did, whatever the sync found upstream.
 *
 * <p><strong>Editors decide content, not permissions.</strong> A person editing a draft cannot make
 * a file executable or take that away, because there is nothing to edit: the answer is the path. That
 * is the point — a version whose files are identical must not be able to differ in how they run.
 *
 * <p>The cost, stated where the rule is: a data file that happens to live under {@code scripts/}
 * (an input CSV beside the script that reads it) is stored as executable too. Nothing reads it as a
 * program; the bit is simply not a description of intent at that granularity.
 */
public final class ExecutableMode {

    /** 0755: a file under {@code scripts/}. */
    public static final int EXECUTABLE = 0755;

    /** 0644: every other file. */
    public static final int PLAIN = 0644;

    private static final String CODE_DIRECTORY = "scripts/";

    private ExecutableMode() {
    }

    /** The mode a version stores for a file at {@code relpath}. */
    public static int of(String relpath) {
        return relpath != null && relpath.startsWith(CODE_DIRECTORY) ? EXECUTABLE : PLAIN;
    }
}
