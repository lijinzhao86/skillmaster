package com.skillmasterai.modules.canon;

/**
 * Entries a zip carries that are not content.
 *
 * <p><strong>Dropped, not refused.</strong> These are artefacts of the tool that made the archive,
 * not decisions the author made: a Finder-made zip always has {@code __MACOSX/}, every copied
 * directory on a Mac has a {@code .DS_Store}, and a save from Word leaves {@code ~$name.docx}. None
 * of them belongs in a skill, and refusing the upload over one would be a refusal the author could
 * only satisfy by learning what each is.
 *
 * <p><strong>Dropped with a report.</strong> The upload answers with the paths it ignored, so the
 * author is not left wondering whether a file made it. Silently dropping content is what §3.3
 * forbids; dropping a known artefact and saying so is the difference.
 *
 * <p>The match is per segment rather than per prefix: {@code pdf-tools/.git/config} is the same
 * mistake as {@code .git/config} at the root, and stripping a single top directory happens after
 * this rule, so the segment is where the name actually is.
 */
public final class Junk {

    private Junk() {
    }

    public static boolean isJunk(String relpath) {
        for (String segment : relpath.split("/")) {
            if (segment.equals("__MACOSX") || segment.equals(".git") || segment.equals(".svn")) {
                return true;
            }
            if (segment.equals(".DS_Store") || segment.equals("Thumbs.db")
                    || segment.equals("desktop.ini")) {
                return true;
            }
            // macOS resource forks and Office lock files: same two shapes, anywhere in the tree.
            if (segment.startsWith("._") || segment.startsWith("~$")) {
                return true;
            }
        }
        return false;
    }
}
