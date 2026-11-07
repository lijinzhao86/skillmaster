package com.skillmasterai.usecase.sync;


import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.support.AbstractIT;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.PublishSkillVersionUseCase;
import com.skillmasterai.usecase.SubmitSkillUseCase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture for the sync pipeline integration tests.
 *
 * <p>Split out when the naming tests pushed one class past the file-size limit. The base is not a
 * test class: it holds the real database wiring, the in-memory source and the assertions' helpers,
 * so the two suites exercise the same pipeline without a second copy of the setup.
 */
abstract class SyncPipelineFixture extends AbstractIT {

    static final String OFFICIAL_NAMESPACE = "01M3HTGC79XSQK3TS3ZF0BG05F";
    static final String OFFICIAL_USER = "01M3HTG7GDZGTDME9B136ZVAW4";

    @Autowired
    SubmitSkillUseCase submit;
    @Autowired
    DraftRecorder drafts;
    @Autowired
    SkillMetadataService metadata;
    @Autowired
    SkillVersionService versions;
    @Autowired
    SourceService sources;
    @Autowired
    NamespaceService namespaces;
    @Autowired
    ObjectMapper json;
    @Autowired
    PublishSkillVersionUseCase publish;

    final FakeContentSource fake = new FakeContentSource();
    SecurityScanner scanner;
    String suffix;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream rules = new ClassPathResource("sync/security-rules.yml").getInputStream()) {
            scanner = new SecurityScanner(SecurityRules.load(rules));
        }
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ---------------------------------------------------------------- helpers

    SyncPipeline pipeline(List<SyncEntry> entries, int concurrency) {
        ContentSourceRegistry registry = new ContentSourceRegistry(List.of(fake));
        SyncDraftWriter writer = new SyncDraftWriter(submit, drafts, metadata, versions, sources);
        return new SyncPipeline(entries, registry, sources, versions, namespaces,
                new LicenseGate(json), scanner, writer, concurrency, 200, 1_048_576,
                10_485_760);
    }

    static SyncEntry entry(String locator, String path) {
        return entry(locator, path, SyncEntry.Naming.NONE);
    }

    static SyncEntry entry(String locator, String path, SyncEntry.Naming naming) {
        return new SyncEntry("fake", locator, path, SyncEntry.Mode.ONE, List.of(), "", naming);
    }

    static FetchedFile file(String path, String content) {
        return new FetchedFile(path, content.getBytes(StandardCharsets.UTF_8));
    }

    static LicenseFile license(String spdx) {
        return new LicenseFile(spdx, spdx, "text", "LICENSE", "https://example/LICENSE");
    }

    static String skillMd(String name, String license) {
        return "---\nname: " + name + "\ndescription: " + name + " skill\n"
                + (license == null ? "" : "license: " + license + "\n") + "---\n";
    }

    static String skillMdWithLicenses(String name, String top, String nested) {
        return "---\nname: " + name + "\ndescription: " + name + " skill\n"
                + "license: " + top + "\nmetadata:\n  license: " + nested + "\n---\n";
    }

    static String skillMdDeclaringSource(String name, String kind, String locator) {
        return "---\nname: " + name + "\ndescription: " + name + " skill\n"
                + "metadata:\n  source:\n    kind: " + kind + "\n    locator: " + locator
                + "\n    path: ''\n---\n";
    }

    long skillCount(String name) {
        return count("SELECT count(*) FROM skill WHERE namespace_id = :ns AND name = :name",
                Map.of("ns", OFFICIAL_NAMESPACE, "name", name));
    }

    long draftCount(String name) {
        return count("SELECT count(*) FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name AND v.state = 'draft'", Map.of("name", name));
    }

    long discardedCount(String name) {
        return count("SELECT count(*) FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name AND v.state = 'discarded'", Map.of("name", name));
    }

    long versionCount(String name) {
        return count("SELECT count(*) FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name", Map.of("name", name));
    }

    long versionFiles(String name) {
        return count("SELECT count(*) FROM version_file vf JOIN skill_version v ON v.id ="
                + " vf.version_id JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name", Map.of("name", name));
    }

    byte[] fileContent(String name, String relpath) {
        return jdbc.sql("SELECT bc.bytes FROM version_file vf"
                        + " JOIN blob_content bc ON bc.sha256 = vf.blob_sha256"
                        + " JOIN skill_version v ON v.id = vf.version_id"
                        + " JOIN skill s ON s.id = v.skill_id"
                        + " WHERE s.name = :name AND vf.relpath = :path"
                        + " ORDER BY v.number DESC LIMIT 1")
                .param("name", name)
                .param("path", relpath)
                .query((rs, rowNum) -> rs.getBytes(1))
                .optional()
                .orElse(null);
    }

    String licenseStatus(String name) {
        return text("SELECT l.status FROM skill_version_license l JOIN skill_version v ON"
                + " v.id = l.version_id JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name ORDER BY v.number DESC LIMIT 1", name);
    }

    String licenseSpdx(String name) {
        return text("SELECT l.spdx FROM skill_version_license l JOIN skill_version v ON"
                + " v.id = l.version_id JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name ORDER BY v.number DESC LIMIT 1", name);
    }

    String changelog(String name) {
        return text("SELECT v.changelog FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name ORDER BY v.number LIMIT 1", name);
    }

    String latestChangelog(String name) {
        return text("SELECT v.changelog FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name ORDER BY v.number DESC LIMIT 1", name);
    }

    String currentVersion(String name) {
        return text("SELECT current_version_id FROM skill WHERE name = :name", name);
    }

    String latestVersionId(String name) {
        return text("SELECT v.id FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name ORDER BY v.number DESC LIMIT 1", name);
    }

    String versionSource(String name) {
        return text("SELECT v.source FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE s.name = :name ORDER BY v.number DESC LIMIT 1", name);
    }

    boolean snapshotMarked(String versionId) {
        return jdbc.sql("SELECT written_by_sync FROM skill_source_snapshot"
                        + " WHERE skill_version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> rs.getBoolean(1))
                .single();
    }

    String snapshotRevisionOf(String versionId) {
        return jdbc.sql("SELECT revision FROM skill_source_snapshot WHERE skill_version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    String snapshotStatus(String versionId) {
        return jdbc.sql("SELECT status FROM skill_source_snapshot WHERE skill_version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    String snapshotSignals(String versionId) {
        return jdbc.sql("SELECT signals FROM skill_source_snapshot WHERE skill_version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    String snapshotFetchedAt(String versionId) {
        return jdbc.sql("SELECT fetched_at FROM skill_source_snapshot"
                        + " WHERE skill_version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    String snapshotRevision(String name) {
        return text("SELECT snap.revision FROM skill_source_snapshot snap"
                + " JOIN skill_source src ON src.id = snap.source_id"
                + " JOIN skill s ON s.id = src.skill_id WHERE s.name = :name"
                + " ORDER BY snap.fetched_at DESC LIMIT 1", name);
    }

    String text(String sql, String name) {
        return jdbc.sql(sql).param("name", name)
                .query((rs, rowNum) -> rs.getString(1)).optional().orElse(null);
    }

    Map<String, Object> origin(String name) {
        return jdbc.sql("SELECT src.kind, src.locator, src.path FROM skill_source src"
                        + " JOIN skill s ON s.id = src.skill_id WHERE s.name = :name"
                        + " AND src.role = 'origin'")
                .param("name", name)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "kind", rs.getString("kind"),
                        "locator", rs.getString("locator"),
                        "path", rs.getString("path")))
                .single();
    }

    /** A source that answers from memory, so the base can be tested without a network. */
    static final class FakeContentSource implements ContentSource {

        final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        final Map<String, List<String>> children = new HashMap<>();
        final Set<String> failing = new java.util.HashSet<>();
        final Set<String> licenseMissing = new java.util.HashSet<>();
        final Set<String> failingSignals = new java.util.HashSet<>();
        final Map<String, String> signals = new HashMap<>();
        final AtomicInteger fetchCalls = new AtomicInteger();
        final AtomicInteger signalsCalls = new AtomicInteger();

        static final String DEFAULT_SIGNALS =
                "[{\"key\":\"stars\",\"label\":\"Stars\",\"kind\":\"count\",\"value\":\"1234\","
                        + "\"display\":\"1.2k\"}]";

        record Snapshot(String revision, List<FetchedFile> files, LicenseFile license) {
        }

        void put(String locator, String path, String revision, List<FetchedFile> files,
                LicenseFile license) {
            snapshots.put(locator + "|" + path, new Snapshot(revision, files, license));
        }

        @Override
        public String kind() {
            return "fake";
        }

        @Override
        public boolean supportsChildren() {
            return true;
        }

        @Override
        public SourceBinding binding(SyncEntry entry) {
            return new SourceBinding("fake", entry.locator(), entry.path(), "Fake",
                    "https://fake/" + entry.locator());
        }

        @Override
        public ChildrenResult children(SyncEntry entry) {
            List<String> names = children.get(entry.locator() + "|" + entry.path());
            return names == null ? new ChildrenOk(List.of()) : new ChildrenOk(names);
        }

        @Override
        public RevisionResult revision(SyncEntry entry) {
            if (failing.contains(entry.locator())) {
                return new FetchFailed("upstream_io");
            }
            Snapshot snapshot = snapshots.get(entry.locator() + "|" + entry.path());
            return snapshot == null ? new FetchFailed("not_found")
                    : new RevisionOk(snapshot.revision());
        }

        @Override
        public SignalsResult signals(SyncEntry entry) {
            signalsCalls.incrementAndGet();
            if (failingSignals.contains(entry.locator())) {
                return new FetchFailed("upstream_io");
            }
            return new SignalsOk(signals.getOrDefault(entry.locator(), DEFAULT_SIGNALS));
        }

        @Override
        public FetchResult fetch(SyncEntry entry, String revision) {
            fetchCalls.incrementAndGet();
            if (licenseMissing.contains(entry.locator())) {
                return new FetchFailed("license_path_missing");
            }
            Snapshot snapshot = snapshots.get(entry.locator() + "|" + entry.path());
            return snapshot == null ? new FetchFailed("not_found")
                    : new Fetched(snapshot.files(), snapshot.revision(), snapshot.license());
        }
    }
}
