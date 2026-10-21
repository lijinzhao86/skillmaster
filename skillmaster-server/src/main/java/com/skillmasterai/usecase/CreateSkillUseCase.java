package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.ingest.SkillDocument;
import com.skillmasterai.modules.ingest.SkillUpload;
import com.skillmasterai.modules.ingest.SkillUploadValidator;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.SubmitOutcome;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * One action: a skill and its first draft (ADR 0070).
 *
 * <p><strong>Why not two steps.</strong> A skill with no version is a row every surface has to
 * special-case — it lists, it holds a name, its address resolves to nothing — and it exists only
 * because the user closed a tab. v6's content model says a version has content; the way to make
 * that true is to create both in one transaction.
 *
 * <p><strong>The draft is a real document.</strong> The body is the same minimal {@code SKILL.md}
 * v8 writes for an empty base, parsed by the same {@code SkillDocument} and stored by the same
 * {@code Uploads} helpers as an upload — so the editor opens a real file, and the name and
 * description rules are the ones every other path uses.
 *
 * <p><strong>Lock order matches submission.</strong> Blobs are stored before the GC lock (a
 * concurrent submit of the same bytes may already be waiting for it), then the section holds the
 * GC lock before the {@code skill} row lock. The first draft is written by the existing
 * {@link SkillVersionService#submit}, which is the method that already means "record a version for
 * a skill M12 has just found or created" — no parallel {@code submitInitial} exists to drift from
 * it.
 *
 * <p>The version-code collision retry lives outside this transaction, in the controller's
 * {@code DraftRecorder}, for the same reason every other submission puts it there.
 */
@Component
public class CreateSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillVersionService versions;
    private final BlobStore blobs;
    private final AuditLog audit;
    private final ObjectMapper objectMapper;

    public CreateSkillUseCase(NamespaceService namespaces, SkillMetadataService metadata,
            SkillVersionService versions, BlobStore blobs, AuditLog audit,
            ObjectMapper objectMapper) {
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.versions = versions;
        this.blobs = blobs;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * @param description required: it is {@code SKILL.md}'s {@code description}, which every
     *                    skill must have
     * @param versionName the first draft's name, required (ADR 0055)
     */
    @Transactional
    public SubmittedSkill create(String name, String description, String versionName,
            AuthenticatedSubject subject) {
        String version = VersionLabels.name(versionName);
        Namespace namespace = namespaces.personalNamespaceOf(subject.userId());

        byte[] body = SkillMdTemplate.render(name, description, name);
        SkillDocument.Document document = SkillDocument.parse(body);
        IngestedFile file = new IngestedFile(SkillUploadValidator.SKILL_MD, body);
        SkillUpload upload = new SkillUpload(document.name(), document.title(),
                document.description(), document.frontmatter(), List.of(file));

        SkillMetadata skill = Uploads.metadataOf(objectMapper, upload);
        Manifest manifest = Uploads.store(blobs, upload);
        String at = Timestamps.now();

        // Blobs are already stored; the section below is the only part that touches the skill row
        // and version_bookkeeping, and it takes the GC lock first — the order a concurrent
        // submission takes, so the two cannot deadlock.
        SubmitOutcome outcome = versions.underExclusiveWrite(() -> {
            String skillId = metadata.createOwned(namespace.id(), skill, subject.userId(), at);
            return versions.submit(skillId, skill, manifest, version, null, subject.userId(),
                    "web", at);
        });

        audit.record(new AuditEvent(subject.userId(), "submit", "skill", outcome.skillId(),
                Map.of("name", upload.name(), "digest", outcome.digest(),
                        "created", outcome.created())));

        return new SubmittedSkill(outcome.skillId(), upload.name(), namespace.slug(),
                outcome.version(), outcome.id(), outcome.draftName(), outcome.note(),
                outcome.digest(), outcome.fileCount(), outcome.totalBytes(), outcome.submittedAt(),
                outcome.created(), outcome.skillCreated(), outcome.state(), outcome.number());
    }
}
