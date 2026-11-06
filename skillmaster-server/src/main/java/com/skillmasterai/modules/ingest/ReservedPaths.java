package com.skillmasterai.modules.ingest;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;

/**
 * Paths the platform keeps for itself (ADR 0069).
 *
 * <p><strong>One directory today: {@code .skillmaster/}.</strong> It holds the version metadata
 * sidecar (ADR 0069) and, since v49, the generated license sidecar. An upload strips the whole
 * directory before the content is stored — none of it is a file of the skill, and it must never
 * reach a manifest, a digest, or the file list.
 *
 * <p><strong>The check is not in {@link SkillPaths}.</strong> That rule is shared by the zip reader
 * and by the delete / rename <em>sources</em> of the write path; putting this one there would refuse
 * an upload carrying a sidecar and would make legacy data impossible to clean up. So it lives here,
 * with two callers that mean different things: the validator treats a file at this path as metadata
 * (or refuses it as content), and the write path refuses it as a <em>target</em>.
 */
public final class ReservedPaths {

    /** The sidecar's relpath, relative to the skill root. */
    public static final String SIDECAR = ".skillmaster/metadata.json";

    private ReservedPaths() {
    }

    /** Only the version metadata at the exact path is parsed as metadata. */
    public static boolean isSidecar(String relpath) {
        return SIDECAR.equals(relpath);
    }

    /** Everything under `.skillmaster/` is platform-owned and never becomes skill content. */
    public static boolean isReserved(String relpath) {
        return relpath != null && relpath.startsWith(".skillmaster/");
    }

    /**
     * Refuses a write, move or copy <em>to</em> a reserved path.
     *
     * <p>Answers 400 {@code reserved_path}, the top-level code the write routes use — the upload
     * path answers {@code invalid_upload} with an issue of the same name, because the two are
     * different requests: one is a client choosing a path, the other is a package describing one.
     *
     * <p><strong>Deletes and renames' sources are not checked.</strong> Legacy versions may hold a
     * file at this path (it was an ordinary name before ADR 0069); the ability to remove it is the
     * only way such data ever leaves.
     */
    public static void requireNotReserved(String relpath) {
        if (isReserved(relpath)) {
            throw new RequestException(ErrorCode.RESERVED_PATH,
                    "'.skillmaster/' is reserved for the platform's metadata;"
                            + " it cannot be written as skill content");
        }
    }
}
