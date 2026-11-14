package com.skillmasterai.api.inner;

import com.skillmasterai.modules.ingest.SkillDocument;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.LicenseStatus;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import com.skillmasterai.usecase.sync.FetchedFile;
import com.skillmasterai.usecase.sync.LicenseDecision;
import com.skillmasterai.usecase.sync.PreparedEntry;
import com.skillmasterai.usecase.sync.SecurityReport;
import com.skillmasterai.usecase.sync.SyncDraftWriter;
import com.skillmasterai.usecase.sync.SyncEntry;
import com.skillmasterai.usecase.sync.WriteResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The submission door for the operations service ({@code /inner/v1}, ADR 0198).
 *
 * <p>It is an adapter, not a second write path: the archive is turned back into a
 * {@link PreparedEntry} and handed to {@link SyncDraftWriter}, so digest, normalisation, the
 * version state machine and the audit trail are the ones a person's upload also takes.
 *
 * <p><strong>What it trusts.</strong> The scan and the license decision come from ops and are not
 * repeated here — the two processes are one trust domain, and a second scan would be a second rule
 * set to keep in step. What it does not trust is shape: the archive root must name the same skill
 * the metadata does.
 */
@RestController
@RequestMapping("/inner/v1/sync")
public class InnerSyncController {

    /** The same ceiling an ordinary upload gets; a submission that exceeds it is refused early. */
    private static final long MAX_ARCHIVE_BYTES = 520L * 1024L * 1024L;
    /** Inflation ceilings, so a small archive cannot expand the user-facing process out of heap. */
    private static final long MAX_INFLATED_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 4096;

    private final SyncDraftWriter writer;
    private final NamespaceService namespaces;
    private final ObjectMapper json;

    public InnerSyncController(SyncDraftWriter writer, NamespaceService namespaces,
            ObjectMapper json) {
        this.writer = writer;
        this.namespaces = namespaces;
        this.json = json;
    }

    @PostMapping(path = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> submit(@RequestParam("metadata") String metadata,
            @RequestParam("archive") MultipartFile archive) {
        InnerSubmission submission;
        try {
            submission = json.readValue(metadata, InnerSubmission.class);
        } catch (RuntimeException unreadable) {
            return error(HttpStatus.BAD_REQUEST, "invalid_submission", "metadata is not JSON");
        }
        if (archive.getSize() > MAX_ARCHIVE_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "invalid_submission", "archive is too large");
        }
        List<FetchedFile> files;
        try {
            files = unzip(archive.getBytes());
        } catch (IOException unreadable) {
            return error(HttpStatus.BAD_REQUEST, "invalid_submission", "archive is not a zip");
        }
        if (files.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_submission", "archive is empty");
        }
        SkillDocument.Document document;
        try {
            document = SkillDocument.parse(skillMd(files));
        } catch (RuntimeException invalid) {
            return error(HttpStatus.BAD_REQUEST, "invalid_submission", invalid.getMessage());
        }
        if (!document.name().equals(submission.platform_name())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_submission",
                    "archive root names " + document.name() + ", metadata says "
                            + submission.platform_name());
        }
        Namespace official = namespaces.official()
                .orElseThrow(() -> new IllegalStateException("no official namespace"));

        PreparedEntry prepared = new PreparedEntry(
                new SyncEntry(submission.binding().kind(), submission.binding().locator(), "",
                        SyncEntry.Mode.ONE, List.of(), "", SyncEntry.Naming.NONE),
                new SourceBinding(submission.binding().kind(), submission.binding().locator(),
                        submission.binding().path(), "", ""),
                submission.revision(), files, document, license(submission.license()),
                new SecurityReport(List.of(), List.of()), submission.platform_name(), List.of(),
                submission.signals());
        WriteResult written = writer.write(prepared, official);
        if ("blocked".equals(written.result())) {
            return error(HttpStatus.CONFLICT, written.reason(), written.reason());
        }
        return ResponseEntity.ok(Map.of(
                "skill_id", written.skillId(),
                "draft_id", written.versionId(),
                "platform_name", submission.platform_name(),
                "created", written.created(),
                "replayed", !written.created()));
    }

    private static LicenseDecision license(License license) {
        LicenseStatus status;
        try {
            status = LicenseStatus.valueOf(license.status());
        } catch (RuntimeException unknown) {
            status = LicenseStatus.MISSING;
        }
        return new LicenseDecision(LicenseDecision.Action.SYNC, license.spdx(), license.name(),
                license.text(), license.path(), license.url(), license.copyright(), status,
                license.detail() == null ? "{}" : license.detail(), "");
    }

    private static byte[] skillMd(List<FetchedFile> files) {
        return files.stream()
                .filter(file -> file.relpath().equals("SKILL.md"))
                .findFirst()
                .map(FetchedFile::content)
                .orElseThrow(() -> new IllegalStateException("no SKILL.md at the archive root"));
    }

    /** Strips the single root directory the archive carries. */
    private static List<FetchedFile> unzip(byte[] archive) throws IOException {
        List<FetchedFile> files = new ArrayList<>();
        long inflated = 0;
        try (InputStream input = new ByteArrayInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = entry.getName();
                int slash = path.indexOf('/');
                String relpath = slash < 0 ? path : path.substring(slash + 1);
                for (String segment : relpath.split("/")) {
                    if (segment.equals("..") || segment.isEmpty()) {
                        throw new IOException("unsafe path in the archive: " + path);
                    }
                }
                long budget = MAX_INFLATED_BYTES - inflated + 1;
                byte[] content = zip.readNBytes((int) Math.min(budget, Integer.MAX_VALUE));
                inflated += content.length;
                if (inflated > MAX_INFLATED_BYTES || files.size() >= MAX_ENTRIES
                        || content.length >= budget) {
                    throw new IOException("archive expands past the limits");
                }
                files.add(new FetchedFile(relpath, content));
            }
        }
        return files;
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "message", message == null ? "" : message)));
    }

    public record InnerSubmission(Binding binding, String revision, String platform_name,
            License license, String signals) {
    }

    public record Binding(String kind, String locator, String path) {
    }

    public record License(String spdx, String name, String copyright, String status, String text,
            String path, String url, String detail) {
    }
}
