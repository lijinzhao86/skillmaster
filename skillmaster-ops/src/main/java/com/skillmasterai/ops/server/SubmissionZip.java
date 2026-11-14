package com.skillmasterai.ops.server;

import com.skillmasterai.ops.imports.ExecutableMode;
import com.skillmasterai.ops.imports.FetchedFile;
import com.skillmasterai.ops.imports.SkillDocument;
import com.skillmasterai.ops.imports.SkillNameTransform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The archive a submission carries: one root directory named after the platform name.
 *
 * <p>The root is not cosmetic — the server's validator compares it with {@code SKILL.md}'s
 * {@code name}, so both are made to agree here. The mode is derived from the path, never from the
 * upstream archive (ADR 0166), so the same relpaths always mean the same modes.
 */
final class SubmissionZip {

    private SubmissionZip() {
    }

    static byte[] build(String platformName, List<FetchedFile> files) {
        String upstreamName = documentName(files);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (FetchedFile file : files.stream()
                    .sorted(Comparator.comparing(FetchedFile::relpath)).toList()) {
                byte[] content = file.content();
                if (file.relpath().equals("SKILL.md") && !platformName.equals(upstreamName)) {
                    content = SkillNameTransform.rewriteSkillMd(content, platformName);
                }
                ZipEntry entry = new ZipEntry(platformName + "/" + file.relpath());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("building the submission zip failed", failure);
        }
    }

    private static String documentName(List<FetchedFile> files) {
        return files.stream()
                .filter(file -> file.relpath().equals("SKILL.md"))
                .findFirst()
                .map(file -> SkillDocument.parse(file.content()).name())
                .orElseThrow(() -> new IllegalStateException("no SKILL.md at the archive root"));
    }
}
