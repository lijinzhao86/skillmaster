package com.skillmasterai.usecase;

import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestException;
import com.skillmasterai.modules.ingest.SkillSidecar;
import com.skillmasterai.modules.ingest.SkillUpload;
import com.skillmasterai.modules.ingest.SkillUploadValidator;
import com.skillmasterai.modules.ingest.ValidatedUpload;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.SubmitOutcome;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Adds a version to a skill that already exists, which is how an {@code editor} contributes
 * (ADR 0034).
 *
 * <p><strong>Why this is a second use case rather than a parameter on
 * {@link SubmitSkillUseCase}.</strong> That one's target comes from the token's subject — never
 * from the request — and its note says why: a target a request can name is a cross-namespace write
 * primitive. This one names a target, so the only thing standing between it and that primitive is
 * that it cannot create a skill: it resolves an existing one, and only one the caller has been given
 * write access to. Keeping the two apart means neither can acquire the other's property by editing
 * an argument — {@code submit} has no way to name a target, and this has no way to bring a name into
 * being.
 */
@Component
public class SubmitSkillVersionUseCase {

    private final SkillUploadValidator validator;
    private final NamespaceService namespaces;
    private final BlobStore blobs;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final AuditLog audit;
    private final ObjectMapper objectMapper;

    public SubmitSkillVersionUseCase(SkillUploadValidator validator, NamespaceService namespaces,
            BlobStore blobs, SkillVersionService versions, SkillMetadataService metadata,
            AuditLog audit, ObjectMapper objectMapper) {
        this.validator = validator;
        this.namespaces = namespaces;
        this.blobs = blobs;
        this.versions = versions;
        this.metadata = metadata;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * @param namespaceSlug the address's first segment, which for a shared skill is somebody else's
     * @param name          the address's second segment. **Must equal the {@code name} in the
     *                      upload's own frontmatter** — see below
     * @return the version that now exists, or empty when there is no such skill for this caller
     * @throws IngestException when the address and the content name different skills
     * @throws com.skillmasterai.modules.skill.NotPermittedException when the caller may read it and
     *         may not write it
     */
    @Transactional
    public Optional<SubmittedSkill> submitVersion(String namespaceSlug,
            String name, byte[] zip, String draftName, String note,
            AuthenticatedSubject subject) {
        ValidatedUpload validated = validator.validate(zip);
        SkillUpload upload = validated.upload();
        SkillSidecar sidecar = validated.sidecar();
        // The address answers "which skill"; the package answers "what was it called" when the
        // request did not say. The request's value wins (ADR 0069).
        String versionName = VersionLabels.nameOrPackaged(draftName,
                sidecar == null ? null : sidecar.name());
        String label = VersionLabels.noteOrPackaged(note, sidecar == null ? null : sidecar.note());

        // The address and the content are two sources for one fact, and they must agree. It used to
        // be one source — the name came from the frontmatter and the target from the token — so this
        // check had nothing to compare. Submitting `foo`'s content to `bar` would otherwise silently
        // add a version to `bar` carrying `foo`'s metadata, and nothing downstream would notice:
        // every read resolves by the address.
        if (!name.equals(upload.name())) {
            // Both names travel, so the page can say which side to change instead of quoting one
            // and leaving the reader to guess the other (v9 PRD 做 #4).
            throw new IngestException("the address names '" + name + "' but SKILL.md"
                    + " declares '" + upload.name() + "'; a version is added to the skill the address"
                    + " names, so the two have to be the same skill",
                    List.of(new com.skillmasterai.common.ApiError.Detail("files[0].relpath",
                                    "name_mismatch"),
                            new com.skillmasterai.common.ApiError.Detail("address_name", name),
                            new com.skillmasterai.common.ApiError.Detail("content_name",
                                    upload.name())));
        }

        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        Caller caller = new Caller(subject.userId(), namespaces
                .personalNamespaceOf(subject.userId()).id());
        Optional<Access> resolved = metadata.accessFor(namespace.get().id(), name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        SkillMetadata skill = Uploads.metadataOf(objectMapper, upload);
        Manifest manifest = Uploads.store(blobs, upload);
        String at = com.skillmasterai.common.Timestamps.now();

        Optional<SubmitOutcome> outcome = Optional.of(versions.underExclusiveWrite(
                () -> versions.submitVersion(access, skill, manifest, versionName, label,
                        subject.userId(), "zip", at).orElseThrow()));
        outcome.ifPresent(recorded -> audit.record(new AuditEvent(subject.userId(), "submit",
                "skill", recorded.skillId(),
                Map.of("name", name, "digest", recorded.digest(), "created", recorded.created()))));

        return outcome.map(recorded -> new SubmittedSkill(recorded.skillId(),
                upload.name(), namespace.get().slug(), recorded.version(), recorded.id(),
                recorded.draftName(), recorded.note(), recorded.digest(), recorded.fileCount(),
                recorded.totalBytes(), recorded.submittedAt(), recorded.created(),
                recorded.skillCreated(), recorded.state(), recorded.number()));
    }
}
