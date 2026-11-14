package com.skillmasterai.ops.ports;

import java.util.List;
import java.util.Optional;

/**
 * The server's side of labeling: which public skills exist, what their current version says, and
 * where the final set is written.
 *
 * <p>Only {@code runtime} labels are replaced; the endpoint is a whole-set replace, so a value that
 * no longer matches disappears without ops having to compute a delta.
 */
public interface LabelSource {

    Page publicSkills(String cursor, int limit);

    Optional<Content> content(String namespace, String name);

    /**
     * @param versionId the version these labels were computed from
     * @return true when the server applied them; false when that version is not the live one
     */
    boolean replaceLabels(String namespace, String name, String versionId,
            List<String> definitionIds);

    record Page(List<SkillRef> items, String nextCursor) {
    }

    record SkillRef(String skillId, String namespace, String name, String versionId) {
    }

    record Content(String skillId, String versionId, String skillMd, List<FileFact> files) {
    }

    record FileFact(String relpath, long size, boolean binary) {
    }
}
