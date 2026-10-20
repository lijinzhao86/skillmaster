package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.DraftWriteResult;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code skill write}: replaces or creates one file of a draft (v7 做 #6).
 *
 * <p><strong>Whole-file, from the request body.</strong> There is no partial write and no append —
 * the command is the same one Claude Code's {@code Write} performs, and {@code skill edit} is built
 * on top of it in the client rather than as a second server verb (v7 TD §与 Claude Code 的 Edit /
 * Write 对齐). The server therefore has exactly one content-writing primitive, and a bug in its
 * permission or concurrency handling cannot exist in only one of two paths.
 *
 * <p>A write of the root {@code SKILL.md} also updates the <em>version's</em> metadata snapshot —
 * title, description, frontmatter — because that file is the version's own declaration. The skill
 * row is untouched: it belongs to the publish flow (ADR 0049), and a draft edit must not change what
 * the consumption plane reads.
 */
@Component
public class WriteDraftFileUseCase extends DraftFileWrite {

    public WriteDraftFileUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        super(namespaces, versions, metadata, blobs, audit, objectMapper);
    }

    /**
     * @param relpath where inside the skill; already checked for the obvious path problems by the
     *                caller and checked again here, because this is the authority
     * @param bytes   the file's new content, exactly as received
     * @param ifMatch the version digest the caller read, in any of the three accepted spellings
     * @return the row as it now reads, or empty when the pin names nothing this rung may see
     */
    @Transactional
    public Optional<DraftWriteResult> write(String namespaceSlug, String name, String rowId,
            String relpath, byte[] bytes, String ifMatch, AuthenticatedSubject subject) {
        Optional<Access> resolved = accessOf(namespaceSlug, name, subject);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        requirePath(relpath);
        Optional<VersionSummary> opened = versions.openDraft(access, rowId);
        if (opened.isEmpty()) {
            return Optional.empty();
        }

        String baseline = requireIfMatch(ifMatch);
        VersionSummary locked = versions.lockDraft(access, rowId);
        requireBaseline(baseline, locked);

        Manifest current = versions.filesOf(locked);
        Optional<ManifestEntry> existing = current.find(relpath);
        BlobStore.BlobRef blob = blobs.put(bytes);
        boolean binary = new IngestedFile(relpath, bytes).isBinary();

        // Writing the same bytes over the same file is the same state reached twice: no row change,
        // no audit, and — because nothing is written — no chance of the 409 the next check would
        // otherwise raise against the row's own digest.
        if (existing.isPresent() && existing.get().blobSha256().equals(blob.sha256Hex())
                && existing.get().isBinary() == binary) {
            return unchanged(access, locked);
        }

        // Parsed before the row is touched: a malformed SKILL.md must leave the draft exactly as it
        // was, which is what "refused" has to mean for a content write.
        SkillMetadata declaration = relpath.equals(BODY_RELPATH)
                ? documentOf(bytes, access.name())
                : null;

        versions.putFile(locked.id(), new ManifestEntry(relpath, blob.sha256Hex(), blob.size(),
                binary));
        Manifest next = versions.filesOf(locked);
        requireWithinLimits(next);

        VersionSummary after = versions.commitContent(access, locked, next, declaration);
        record(subject, access, locked, after, "write", relpath, null, null);
        return result(access, after);
    }
}
