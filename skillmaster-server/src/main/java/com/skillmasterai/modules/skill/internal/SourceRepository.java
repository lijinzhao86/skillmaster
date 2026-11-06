package com.skillmasterai.modules.skill.internal;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.common.Ulid;
import com.skillmasterai.modules.skill.LicenseStatus;
import com.skillmasterai.modules.skill.SkillLicense;
import com.skillmasterai.modules.skill.SkillSource;
import com.skillmasterai.modules.skill.SourceSnapshot;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads and writes the four v49 tables; all of them belong to M12. */
public final class SourceRepository {

    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String currentVersionId(String skillId) {
        return jdbc.sql("SELECT current_version_id FROM skill WHERE id = :skill")
                .param("skill", skillId)
                .query((rs, rowNum) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    public Optional<SkillSource> findOrigin(String skillId) {
        return jdbc.sql("SELECT id, skill_id, role, kind, locator, path, label, url FROM skill_source"
                        + " WHERE skill_id = :skill AND role = 'origin'")
                .param("skill", skillId)
                .query((rs, rowNum) -> new SkillSource(
                        rs.getString("id"), rs.getString("skill_id"), rs.getString("role"),
                        rs.getString("kind"), rs.getString("locator"), rs.getString("path"),
                        rs.getString("label"), rs.getString("url")))
                .optional();
    }

    /**
     * Stores the origin, replacing an older one only when the connection actually changed. A
     * replacement cascades that source's snapshots: they were facts about a different platform.
     */
    public SkillSource putOrigin(String skillId, String kind, String locator, String path,
            String label, String url) {
        Optional<SkillSource> current = findOrigin(skillId);
        if (current.isPresent()) {
            SkillSource existing = current.get();
            if (existing.kind().equals(kind) && existing.locator().equals(locator)) {
                jdbc.sql("UPDATE skill_source SET path = :path, label = :label, url = :url"
                                + " WHERE id = :id")
                        .param("path", path)
                        .param("label", label)
                        .param("url", url)
                        .param("id", existing.id())
                        .update();
                return new SkillSource(existing.id(), existing.skillId(), existing.role(),
                        existing.kind(), existing.locator(), path, label, url);
            }
            jdbc.sql("DELETE FROM skill_source WHERE id = :id")
                    .param("id", existing.id())
                    .update();
        }
        String id = Ulid.generate();
        jdbc.sql("INSERT INTO skill_source (id, skill_id, role, kind, locator, path, label,"
                        + " url, created_at) VALUES (:id, :skill, 'origin', :kind, :locator,"
                        + " :path, :label, :url, :now)")
                .param("id", id)
                .param("skill", skillId)
                .param("kind", kind)
                .param("locator", locator)
                .param("path", path)
                .param("label", label)
                .param("url", url)
                .param("now", Timestamps.now())
                .update();
        return new SkillSource(id, skillId, SkillSource.ORIGIN, kind, locator, path, label, url);
    }

    public void putSnapshot(String sourceId, String versionId, String revision, String signals,
            String status) {
        jdbc.sql("INSERT INTO skill_source_snapshot (source_id, skill_version_id, revision,"
                        + " signals, status, fetched_at)"
                        + " VALUES (:source, :version, :revision, :signals, :status, :now)"
                        + " ON CONFLICT (source_id, skill_version_id) DO UPDATE SET"
                        + " revision = COALESCE(skill_source_snapshot.revision, EXCLUDED.revision),"
                        + " signals = CASE WHEN EXCLUDED.status IN ('error', 'stale')"
                        + "   THEN skill_source_snapshot.signals ELSE EXCLUDED.signals END,"
                        + " status = EXCLUDED.status, fetched_at = EXCLUDED.fetched_at")
                .param("source", sourceId)
                .param("version", versionId)
                .param("revision", revision)
                .param("signals", signals)
                .param("status", status)
                .param("now", Timestamps.now())
                .update();
    }

    public Optional<SourceSnapshot> findSnapshot(String sourceId, String versionId) {
        return jdbc.sql("SELECT source_id, skill_version_id, revision, signals, status, fetched_at"
                        + " FROM skill_source_snapshot WHERE source_id = :source"
                        + " AND skill_version_id = :version")
                .param("source", sourceId)
                .param("version", versionId)
                .query((rs, rowNum) -> new SourceSnapshot(
                        rs.getString("source_id"), rs.getString("skill_version_id"),
                        rs.getString("revision"), rs.getString("signals"),
                        rs.getString("status"), rs.getString("fetched_at")))
                .optional();
    }

    public void putLicense(SkillLicense license) {
        jdbc.sql("INSERT INTO skill_version_license (version_id, spdx, name, copyright, status,"
                        + " license_text, source_path, source_url, detail, fetched_at)"
                        + " VALUES (:version, :spdx, :name, :copyright, :status, :text, :path,"
                        + " :url, :detail, :now)"
                        + " ON CONFLICT (version_id) DO UPDATE SET spdx = EXCLUDED.spdx,"
                        + " name = EXCLUDED.name, copyright = EXCLUDED.copyright,"
                        + " status = EXCLUDED.status, license_text = EXCLUDED.license_text,"
                        + " source_path = EXCLUDED.source_path, source_url = EXCLUDED.source_url,"
                        + " detail = EXCLUDED.detail, fetched_at = EXCLUDED.fetched_at")
                .param("version", license.versionId())
                .param("spdx", license.spdx())
                .param("name", license.name())
                .param("copyright", license.copyright())
                .param("status", license.status().wire())
                .param("text", license.licenseText())
                .param("path", license.sourcePath())
                .param("url", license.sourceUrl())
                .param("detail", license.detail())
                .param("now", Timestamps.now())
                .update();
    }

    public Optional<SkillLicense> findLicense(String versionId) {
        return jdbc.sql("SELECT version_id, spdx, name, copyright, status, license_text,"
                        + " source_path, source_url, detail, fetched_at"
                        + " FROM skill_version_license WHERE version_id = :version")
                .param("version", versionId)
                .query((rs, rowNum) -> new SkillLicense(
                        rs.getString("version_id"), rs.getString("spdx"), rs.getString("name"),
                        rs.getString("copyright"),
                        LicenseStatus.fromWire(rs.getString("status")),
                        rs.getString("license_text"), rs.getString("source_path"),
                        rs.getString("source_url"), rs.getString("detail"),
                        rs.getString("fetched_at")))
                .optional();
    }

    public Map<String, SkillLicense> licensesOf(Collection<String> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SkillLicense> found = new HashMap<>();
        jdbc.sql("SELECT version_id, spdx, name, copyright, status, license_text, source_path,"
                        + " source_url, detail, fetched_at FROM skill_version_license"
                        + " WHERE version_id = ANY(:ids)")
                .param("ids", versionIds.toArray(String[]::new))
                .query((rs, rowNum) -> new SkillLicense(
                        rs.getString("version_id"), rs.getString("spdx"), rs.getString("name"),
                        rs.getString("copyright"),
                        LicenseStatus.fromWire(rs.getString("status")),
                        rs.getString("license_text"), rs.getString("source_path"),
                        rs.getString("source_url"), rs.getString("detail"),
                        rs.getString("fetched_at")))
                .list()
                .forEach(license -> found.put(license.versionId(), license));
        return found;
    }

    public int joins(String skillId) {
        return jdbc.sql("SELECT count(*) AS n FROM skill_library_join WHERE skill_id = :skill")
                .param("skill", skillId)
                .query((rs, rowNum) -> rs.getInt("n"))
                .single();
    }

    /** Live versions whose source still needs a fetch: pending, failed, or older than the cutoff. */
    public java.util.List<Pending> pending(String cutoff) {
        return jdbc.sql("SELECT src.skill_id, snap.skill_version_id"
                        + " FROM skill_source_snapshot snap"
                        + " JOIN skill_source src ON src.id = snap.source_id"
                        + " JOIN skill k ON k.id = src.skill_id"
                        + " WHERE k.current_version_id = snap.skill_version_id"
                        + " AND (snap.status IN ('pending', 'error', 'stale')"
                        + " OR snap.fetched_at < :cutoff)")
                .param("cutoff", cutoff)
                .query((rs, rowNum) -> new Pending(
                        rs.getString("skill_id"), rs.getString("skill_version_id")))
                .list();
    }

    public record Pending(String skillId, String versionId) {
    }
}
