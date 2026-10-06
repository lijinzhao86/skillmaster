package com.skillmasterai.usecase;

import com.skillmasterai.common.Sha256Hex;
import com.skillmasterai.modules.audit.AuditEvent;
import com.skillmasterai.modules.audit.AuditLog;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.ingest.IngestedFile;
import com.skillmasterai.modules.ingest.SkillUpload;
import com.skillmasterai.modules.ingest.SkillUploadValidator;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillMetadata;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.SubmitOutcome;
import com.skillmasterai.usecase.model.SubmittedSkill;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Submits one skill from an uploaded zip.
 *
 * <p><strong>Submitting is not publishing.</strong> This use case records a draft version and stops
 * (ADR 0031) — the pointer does not move, the skill row is not rewritten, and nothing the
 * consumption plane can see has changed when it returns. Publishing is
 * {@link PublishSkillVersionUseCase}, it lives behind the browser plane, and it is deliberate that
 * there is no request shape that reaches it from here.
 *
 * <p>This class is the whole reason §2.5 has a use-case layer. Submitting spans five modules —
 * validate (M5), resolve the namespace (M4), store the bytes (M6), record the version (M7), leave a
 * trace (M10) — and the only correct place for a boundary that covers all five is one level above
 * all of them. Every module it calls is written to be called inside a transaction; none of them
 * opens one.
 *
 * <p><strong>The bytes go from the request straight into the blob store and are never written
 * anywhere else</strong>: no temp file, no normalisation, no re-encoding. ADR 0005 makes that a
 * correctness requirement, not hygiene — anything that alters the bytes alters the digest, and the
 * digest is what clients trust.
 */
@Component
public class SubmitSkillUseCase {

    private final SkillUploadValidator validator;
    private final NamespaceService namespaces;
    private final BlobStore blobs;
    private final SkillVersionService versions;
    private final AuditLog audit;
    private final ObjectMapper objectMapper;

    public SubmitSkillUseCase(SkillUploadValidator validator, NamespaceService namespaces,
            BlobStore blobs, SkillVersionService versions, AuditLog audit, ObjectMapper objectMapper) {
        this.validator = validator;
        this.namespaces = namespaces;
        this.blobs = blobs;
        this.versions = versions;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** @param zip the uploaded archive, exactly as received */
    @Transactional
    public SubmittedSkill submit(byte[] zip, AuthenticatedSubject subject) {
        SkillUpload upload = validator.validate(zip);

        // Submitting into another namespace is not something the API can express: the target comes
        // from the token's subject, never from a request parameter. Otherwise a submission would be
        // a cross-namespace write primitive. The skill is found or created by name within it, so
        // submitting again against the same name adds a version to the same skill.
        Namespace namespace = namespaces.personalNamespaceOf(subject.userId());

        List<ManifestEntry> entries = new ArrayList<>(upload.files().size());
        for (IngestedFile file : inStoreOrder(upload.files())) {
            BlobStore.BlobRef ref = blobs.put(file.bytes());
            entries.add(new ManifestEntry(file.relpath(), ref.sha256Hex(), ref.size(), file.isBinary()));
        }

        SkillMetadata metadata = new SkillMetadata(upload.name(), upload.title(), upload.description(),
                objectMapper.writeValueAsString(upload.frontmatter()));

        SubmitOutcome outcome = versions.submit(
                namespace.id(), metadata, Manifest.of(entries), subject.userId(), "zip");

        // In this same transaction on purpose: an audit row that can commit while the change it
        // describes rolls back is worse than no audit row, because it reads as evidence.
        audit.record(new AuditEvent(subject.userId(), "submit", "skill", outcome.skillId(),
                Map.of("name", upload.name(), "digest", outcome.digest(), "created", outcome.created())));

        return new SubmittedSkill(outcome.skillId(), upload.name(), namespace.slug(),
                outcome.number(), outcome.digest(), outcome.fileCount(), outcome.totalBytes(),
                outcome.submittedAt(), outcome.created(), outcome.state());
    }

    /**
     * The order the bytes are stored in, which is load-bearing rather than cosmetic.
     *
     * <p>Storing a file inserts or touches a row keyed by its digest, and {@code ON CONFLICT DO
     * NOTHING} against a row another transaction has inserted but not committed <em>waits for that
     * transaction</em>. Two submissions that share files would therefore deadlock on each other if
     * they took those rows in different orders — which is exactly what happens when the order comes
     * from the archive, because two authors' zips list the same shared files differently. Ordering
     * by something derived from the content makes every submitter agree, and agreed order is what
     * makes a cycle impossible.
     *
     * <p>The digest has to be computed here to establish that order, so it is computed twice for
     * each file — {@link BlobStore#put} computes its own and must keep doing so, because M6's
     * guarantee is that no caller can record a digest that does not describe the bytes stored.
     * Sorting by {@code relpath} would not do: two skills sharing the same content under different
     * names would then order it differently and the cycle would be back.
     *
     * <p>Hashed once per file before sorting rather than inside the comparator, which is what makes
     * that "twice" true: a comparator that extracted the digest inline would re-hash both operands
     * on every comparison — around fifteen times per file at the 512-file ceiling, which is
     * hundreds of megabytes of SHA-256 spent on an ordering nobody ever sees.
     *
     * <p>The manifest is unaffected: {@link Manifest} sorts by {@code relpath} itself, so the
     * version digest does not depend on the order chosen here.
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
