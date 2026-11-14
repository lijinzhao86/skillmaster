package com.skillmasterai.usecase.label;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.LabelWriteService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service-to-service half of labeling (v72): what the operations service may see and write.
 *
 * <p><strong>No caller, because the plane is the caller.</strong> These methods do not take an
 * {@code Access}: the only route into them is {@code /inner/v1}, which answers on a private port
 * with its own token (ADR 0198). The public predicate still applies — the list is the discovery
 * plane's, and a non-official namespace is refused rather than labeled.
 *
 * <p>What the operation service gets is the minimum the rules need: the root {@code SKILL.md} and
 * the manifest. Not other files' bytes, and not a second copy of the vocabulary.
 */
@Component
public class InnerLabelUseCase {

    /** A {@code SKILL.md} larger than this is not a skill description anyone reads. */
    private static final long MAX_SKILL_MD_BYTES = 512L * 1024L;

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillVersionService versions;
    private final LabelWriteService labels;
    private final BlobStore blobs;

    public InnerLabelUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillVersionService versions, LabelWriteService labels, BlobStore blobs) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.versions = versions;
        this.labels = labels;
        this.blobs = blobs;
    }

    public record PublicSkill(String skillId, String namespace, String name,
            String currentVersionId) {
    }

    /** One page; {@code nextCursor} is the raw page's last id, even if a row was filtered out. */
    public record Page(List<PublicSkill> items, String nextCursor) {
    }

    public record FileFact(String relpath, long size, boolean binary) {
    }

    public record Content(String skillId, String versionId, String skillMd, List<FileFact> files) {
    }

    public Page publicSkills(String cursor, int limit) {
        List<SkillMetadataService.PublicSkill> rows = metadata.publicSkills(cursor, limit);
        // One lookup per page: there is exactly one official namespace (ADR 0146), and only it can
        // hold a public skill — so the filter is an id comparison, not a query per row.
        Optional<Namespace> official = namespaces.official();
        List<PublicSkill> items = new java.util.ArrayList<>();
        if (official.isPresent()) {
            for (SkillMetadataService.PublicSkill row : rows) {
                if (row.namespaceId().equals(official.get().id())) {
                    items.add(new PublicSkill(row.skillId(), official.get().slug(), row.name(),
                            row.currentVersionId()));
                }
            }
        }
        String next = rows.size() < limit ? null : rows.getLast().skillId();
        return new Page(items, next);
    }

    /** Empty when the address names no skill or it has nothing published; 403 when not official. */
    public Optional<Content> content(String namespaceSlug, String name) {
        Optional<Namespace> namespace = official(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> skillId = metadata.idOf(namespace.get().id(), name);
        if (skillId.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> current = metadata.currentVersionId(skillId.get());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Optional<SkillSnapshot> snapshot = versions.innerSnapshot(skillId.get(),
                namespace.get().id(), name, current.get(), current.get());
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        ManifestEntry skillMd = snapshot.get().manifest().find("SKILL.md").orElse(null);
        if (skillMd == null || skillMd.isBinary()) {
            throw new UnreadableSkillMdException(name, "no text SKILL.md at the archive root");
        }
        if (skillMd.size() > MAX_SKILL_MD_BYTES) {
            throw new UnreadableSkillMdException(name, "SKILL.md is larger than the limit");
        }
        byte[] bytes = blobs.get(skillMd.blobSha256());
        List<FileFact> files = snapshot.get().manifest().entries().stream()
                .map(entry -> new FileFact(entry.relpath(), entry.size(), entry.isBinary()))
                .toList();
        return Optional.of(new Content(skillId.get(), current.get(),
                new String(bytes, StandardCharsets.UTF_8), files));
    }

    /**
     * Replaces the skill's whole set with the values the rules produced, but only when the version
     * they describe is the one readers get.
     *
     * <p><strong>Why the version matters.</strong> An import produces a draft; a live skill keeps
     * serving its current version until somebody publishes the draft. Writing the draft's labels
     * would describe content nobody is serving, so a version that is not current is answered
     * {@code applied=false} and the caller keeps the claims until a refresh sees the published
     * version. A skill with nothing published has no such conflict and is written immediately.
     *
     * <p>One transaction: a half-replaced set would describe neither version. The vocabulary check
     * is M12's, so an id that is not a real {@code runtime} value fails here rather than becoming an
     * orphan row.
     */
    @Transactional
    public Optional<Replaced> replaceLabels(String namespaceSlug, String name, String versionId,
            List<String> definitionIds) {
        Optional<Namespace> namespace = official(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> skillId = metadata.idOf(namespace.get().id(), name);
        return skillId.map(id -> {
            Optional<String> current = metadata.currentVersionId(id);
            List<String> unique = definitionIds.stream().distinct().toList();
            if (current.isPresent() && !current.get().equals(versionId)) {
                return new Replaced(id, 0, unique, false);
            }
            return new Replaced(id, labels.replaceRuntimeLabels(id, unique), unique, true);
        });
    }

    /** What the write endpoint answers: the skill, how many rows it now has, the set, and whether it applied. */
    public record Replaced(String skillId, int replaced, List<String> labels, boolean applied) {
    }

    /** Empty for an unknown namespace; throws for a known one that may not hold public skills. */
    private Optional<Namespace> official(String slug) {
        Optional<Namespace> namespace = namespaces.bySlug(slug);
        if (namespace.isPresent() && !namespace.get().official()) {
            throw new NotOfficialNamespaceException(slug);
        }
        return namespace;
    }
}
