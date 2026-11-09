package com.skillmasterai.usecase;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.ConflictException;
import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.SkillOfflineException;
import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.SkillDocument;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillCopy;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.SubmitOutcome;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.model.CopiedSkill;
import com.skillmasterai.usecase.sync.SkillNameTransform;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One action: a version of somebody else's skill becomes a new private skill of my own (v54, ADR
 * 0173).
 *
 * <p><strong>A snapshot, not an import.</strong> The source manifest is reused as-is: content
 * addressing (ADR 0005) means the new version shares the source's blobs, so nothing is downloaded,
 * re-zipped or re-normalised. The one thing that may change is the root {@code SKILL.md}'s
 * {@code name}, and only when the target address differs from what the document declares (ADR
 * 0160's rewrite rule). Under the same name the bytes are identical, and that is worth being able
 * to prove.
 *
 * <p><strong>Why {@code createOwned} + {@code submit} rather than {@code SubmitSkillUseCase}.</strong>
 * The import path's {@code findOrCreate} appends content to an existing skill with that name; a copy
 * must refuse one instead. {@code createOwned} is exactly "nobody has this name", and {@code
 * submit} is the same submission every other entry point uses — there is no third pipeline.
 *
 * <p><strong>Reads need {@code browse}, not {@code invoke}.</strong> The source's file list is what
 * gets copied; handing it to an {@code invoke}-only caller would disclose files the author did not
 * make browsable. The owner is refused outright: changing one's own skill is a new version, not a
 * copy, and the copy would only be a renamed duplicate.
 */
@Component
public class CopySkillUseCase {

    private static final String SUBMITTED_VIA = "web";
    private static final String BODY_RELPATH = "SKILL.md";
    private static final String DRAFT_NAME_PREFIX = "复制自 ";
    /** The version-name limit everywhere else (ADR 0055); the address is trimmed to fit it. */
    private static final int DRAFT_NAME_MAX = 40;

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillVersionService versions;
    private final BlobStore blobs;
    private final SourceService sources;
    private final AuditLog audit;
    private final ObjectMapper objectMapper;

    public CopySkillUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillVersionService versions, BlobStore blobs, SourceService sources, AuditLog audit,
            ObjectMapper objectMapper) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.versions = versions;
        this.blobs = blobs;
        this.sources = sources;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * @param namespaceSlug the source address's first segment
     * @param name          the source address's second segment
     * @param targetName    the new skill's name, in the caller's own namespace
     * @param pin           which source version; {@link VersionPin.Latest} reads the live one
     * @return empty when the source is not visible to this caller (§4.1's one 404)
     * @throws NotPermittedException when the caller owns the source, or holds less than
     *                               {@code browse}
     * @throws SkillOfflineException when the source is switched off
     */
    @Transactional
    public Optional<CopiedSkill> copy(String namespaceSlug, String name, String targetName,
            VersionPin pin, AuthenticatedSubject subject) {
        String target = requireTargetName(targetName);

        Optional<Namespace> sourceNamespace = namespaces.bySlug(namespaceSlug);
        if (sourceNamespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = Callers.of(namespaces, subject);
        Optional<Access> resolved =
                metadata.accessFor(sourceNamespace.get().id(), name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        if (access.owns()) {
            throw new NotPermittedException("copying '" + access.name() + "' is for somebody"
                    + " else's skill; change your own by submitting a version",
                    List.of(new ApiError.Detail("reason", "owner")));
        }
        if (!access.atLeast(GrantRole.BROWSE)) {
            throw new NotPermittedException("copying '" + access.name() + "' needs the `browse`"
                    + " rung; the `invoke` rung cannot list the files a copy is made of",
                    List.of(new ApiError.Detail("reason", "browse_required")));
        }
        // The consumption plane's rule, after the visibility lookup so an unreadable skill still
        // answers 404 rather than disclosing that it exists but is switched off.
        if (access.offlineAt() != null) {
            throw new SkillOfflineException("'" + access.name() + "' is offline; its owner has"
                    + " stopped serving it. Try again later.");
        }

        Optional<SkillSnapshot> found = versions.resolveForConsumption(access, pin);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SkillSnapshot snapshot = found.get();

        Manifest manifest;
        SkillMetadata skill;
        if (target.equals(contentNameOf(snapshot))) {
            manifest = snapshot.manifest();
            skill = new SkillMetadata(target, snapshot.title(), snapshot.description(),
                    snapshot.frontmatterJson());
        } else {
            Rewritten rewritten = rewrite(snapshot, target);
            manifest = rewritten.manifest();
            skill = rewritten.metadata();
        }
        DraftFileWrite.requireWithinLimits(manifest);

        String sourceAddress = sourceNamespace.get().slug() + "/" + snapshot.name();
        String versionName = VersionLabels.name(versionNameFor(sourceAddress));
        Namespace own = namespaces.personalNamespaceOf(subject.userId());
        String at = Timestamps.now();

        // Blobs for a rewritten SKILL.md are already stored above, before the GC lock — the order
        // every submission takes (see CreateSkillUseCase). The section below is the only part that
        // touches the skill row and version_bookkeeping.
        SubmitOutcome outcome = versions.underExclusiveWrite(() -> {
            String skillId = metadata.createOwned(own.id(), skill, subject.userId(), at);
            return versions.submit(skillId, skill, manifest, versionName, null, subject.userId(),
                    SUBMITTED_VIA, at, null);
        });

        sources.copyLicense(snapshot.id(), outcome.id());
        sources.recordCopy(new SkillCopy(outcome.skillId(), outcome.id(), snapshot.skillId(),
                snapshot.id(), sourceAddress, snapshot.addressSuffix(), subject.userId(), at));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source_address", sourceAddress);
        detail.put("source_version", snapshot.addressSuffix());
        detail.put("name", target);
        detail.put("draft", outcome.id());
        detail.put("created", outcome.created());
        audit.record(new AuditEvent(subject.userId(), "copy", "skill", outcome.skillId(), detail));

        return Optional.of(new CopiedSkill(outcome.skillId(), own.slug(), target, outcome.id(),
                outcome.draftName(), outcome.version(), sourceAddress, snapshot.addressSuffix()));
    }

    /**
     * The name the source's {@code SKILL.md} actually declares.
     *
     * <p>Read from the version's stored frontmatter rather than the address: a draft may edit
     * {@code SKILL.md} without renaming the skill, so the two can legitimately differ. Comparing
     * against the address would then copy a document whose {@code name} disagrees with its new
     * home — the exact drift ADR 0160 exists to prevent.
     */
    private String contentNameOf(SkillSnapshot snapshot) {
        String json = snapshot.frontmatterJson();
        if (json == null || json.isBlank()) {
            return snapshot.name();
        }
        JsonNode name = objectMapper.readTree(json).get("name");
        return name != null && name.isTextual() && !name.asText().isBlank()
                ? name.asText() : snapshot.name();
    }

    /**
     * Rewrites the one {@code name} field and returns a manifest that points at the new bytes.
     *
     * <p>The rest of the source manifest is untouched: every other path keeps the source's blob,
     * so this is one new blob, not a second copy of the skill.
     */
    private Rewritten rewrite(SkillSnapshot snapshot, String target) {
        ManifestEntry body = snapshot.manifest().find(BODY_RELPATH)
                .orElseThrow(() -> new IllegalStateException("published version " + snapshot.id()
                        + " has no root SKILL.md"));
        byte[] original = blobs.get(body.blobSha256());
        byte[] rewritten;
        try {
            rewritten = SkillNameTransform.rewriteSkillMd(original, target);
        } catch (SkillNameTransform.NotRewritable failure) {
            throw new ConflictException(ErrorCode.NAME_NOT_REWRITABLE,
                    "this skill's SKILL.md cannot be renamed safely (" + failure.getMessage()
                            + "); copy it under its own name instead");
        }
        BlobStore.BlobRef stored = blobs.put(rewritten);
        ManifestEntry renamed = new ManifestEntry(BODY_RELPATH, stored.sha256Hex(),
                stored.size(), false);
        List<ManifestEntry> entries = snapshot.manifest().entries().stream()
                .map(entry -> BODY_RELPATH.equals(entry.relpath()) ? renamed : entry)
                .toList();

        SkillDocument.Document document = SkillDocument.parse(rewritten);
        SkillMetadata skill = new SkillMetadata(target, document.title(),
                document.description(), objectMapper.writeValueAsString(document.frontmatter()));
        return new Rewritten(Manifest.of(entries), skill);
    }

    private record Rewritten(Manifest manifest, SkillMetadata metadata) {
    }

    private static String requireTargetName(String raw) {
        String name = raw == null ? "" : raw.strip();
        try {
            SkillNameTransform.requireValid(name);
        } catch (SkillNameTransform.InvalidName failure) {
            throw new RequestException(ErrorCode.INVALID_NAME,
                    "a copy needs a target name: 1-64 lowercase letters, digits and single"
                            + " hyphens, not starting or ending with one, and not using the"
                            + " reserved 'skillmaster' prefix",
                    List.of(new ApiError.Detail("name", failure.reason())));
        }
        return name;
    }

    /** 「复制自 <address>」, cut to the version-name limit the rest of the system uses. */
    private static String versionNameFor(String sourceAddress) {
        String raw = DRAFT_NAME_PREFIX + sourceAddress;
        if (raw.codePointCount(0, raw.length()) <= DRAFT_NAME_MAX) {
            return raw;
        }
        return raw.substring(0, raw.offsetByCodePoints(0, DRAFT_NAME_MAX));
    }
}
