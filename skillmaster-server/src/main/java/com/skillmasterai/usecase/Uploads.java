package com.skillmasterai.usecase;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.ingest.SkillUpload;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * What both submission paths do to an upload before M7 sees it: store the bytes, and describe them.
 *
 * <p>Shared rather than written out twice because every line here is an invariant that has to hold
 * identically on both — the order the blobs are written in (which decides the order {@code ON
 * CONFLICT} waits in, and so whether two concurrent submissions of the same content can deadlock),
 * and the fact that the metadata is the version's rather than the skill's (ADR 0031). A second copy
 * is a second place for either to drift.
 */
final class Uploads {

    private Uploads() {
    }

    /** Stores every file and returns the manifest they make. */
    static Manifest store(BlobStore blobs, SkillUpload upload) {
        List<ManifestEntry> entries = new ArrayList<>(upload.files().size());
        for (IngestedFile file : inStoreOrder(upload.files())) {
            BlobStore.BlobRef ref = blobs.put(file.bytes());
            entries.add(new ManifestEntry(file.relpath(), ref.sha256Hex(), ref.size(),
                    file.isBinary()));
        }
        return Manifest.of(entries);
    }

    /**
     * The version's own copy of the metadata.
     *
     * <p>A submission must not change anything the consumption plane can see (ADR 0031), so this
     * travels on the version and is projected onto the skill row only when somebody publishes it.
     */
    static SkillMetadata metadataOf(ObjectMapper objectMapper, SkillUpload upload) {
        return new SkillMetadata(upload.name(), upload.title(), upload.description(),
                objectMapper.writeValueAsString(upload.frontmatter()), upload.version());
    }

    /**
     * The files in the order they are stored.
     *
     * <p>By content hash rather than by the order the zip happened to list them, so two submissions
     * of the same content take the same locks in the same order.
     */
    private static List<IngestedFile> inStoreOrder(List<IngestedFile> files) {
        return files.stream()
                .map(file -> Map.entry(Sha256Hex.of(file.bytes()), file))
                .sorted(Comparator.<Map.Entry<String, IngestedFile>, String>comparing(Map.Entry::getKey)
                        .thenComparing(entry -> entry.getValue().relpath()))
                .map(Map.Entry::getValue)
                .toList();
    }
}
