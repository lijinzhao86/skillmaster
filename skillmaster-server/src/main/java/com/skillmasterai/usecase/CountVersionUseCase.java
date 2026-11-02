package com.skillmasterai.usecase;

import com.skillmasterai.common.VersionFileNotFoundException;
import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.usecase.model.CountResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code skill wc}: counts for one version, computed where the bytes are (v7 做 #4).
 *
 * <p><strong>Only numbers cross the wire.</strong> {@code -c} is answered by the manifest alone —
 * no blob is opened — and {@code -l} / {@code -w} need the bytes, so they are read here and only the
 * counts are returned. That split is the performance budget's, not an optimisation to add later:
 * "how big is this file" must not cost a megabyte of transfer.
 *
 * <p>Visible on every state the caller's rung may read, the same rule the file list uses (a draft
 * needs {@code write}, a published version {@code browse}, a revoked or discarded one
 * {@code maintain}). Counting a frozen version is an ordinary author-plane read.
 */
@Component
public class CountVersionUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillMetadataService metadata;
    private final BlobStore blobs;

    public CountVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.blobs = blobs;
    }

    /**
     * @param paths exact relpaths to count; empty means the whole version
     */
    @Transactional(readOnly = true)
    public Optional<CountResult> count(String namespaceSlug, String name, VersionPin pin,
            List<String> paths, AuthenticatedSubject subject) {
        Optional<Access> resolved = namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> metadata.accessFor(namespace.id(), name,
                        Callers.of(namespaces, subject)));
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Access access = resolved.get();
        Optional<SkillSnapshot> snapshot = versions.authorSnapshot(access, pin);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        SkillSnapshot version = snapshot.get();

        List<ManifestEntry> selected = new ArrayList<>();
        if (paths == null || paths.isEmpty()) {
            selected.addAll(version.manifest().entries());
        } else {
            for (String path : paths) {
                selected.add(version.manifest().find(path)
                        .orElseThrow(() -> new VersionFileNotFoundException(path)));
            }
        }

        List<CountResult.FileCount> files = new ArrayList<>(selected.size());
        long lines = 0;
        long words = 0;
        long bytes = 0;
        for (ManifestEntry entry : selected) {
            CountResult.FileCount counted = count(entry);
            files.add(counted);
            lines += counted.lines();
            words += counted.words();
            bytes += counted.bytes();
        }
        // The digest comes from the manifest the numbers were counted from, not from the row read a
        // statement earlier: READ COMMITTED gives each statement its own snapshot, and a concurrent
        // write between the two would otherwise label these counts with the previous content.
        return Optional.of(new CountResult("sha256:" + version.manifest().digest(),
                pin.suffix().orElse("sha256:" + version.digest()), files,
                new CountResult.Totals(lines, words, bytes)));
    }

    /**
     * Counts without holding the file: a 100 MiB text file is a legal skill file, and a count is
     * exactly the operation that does not need it in memory (v39).
     */
    private CountResult.FileCount count(ManifestEntry entry) {
        // Bytes come from the manifest, always: it is the number the digest was computed over, and
        // it is available without opening the blob. Only the other two need the content.
        long bytes = entry.size();
        if (entry.isBinary()) {
            return streamBinary(entry, bytes);
        }
        return streamText(entry, bytes);
    }

    private CountResult.FileCount streamBinary(ManifestEntry entry, long bytes) {
        long lines = 0;
        long words = 0;
        boolean inWord = false;
        try (java.io.InputStream in = blobs.open(entry.blobSha256())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    int value = buffer[i] & 0xFF;
                    if (value == '\n') {
                        lines++;
                    }
                    boolean whitespace = value == 0x20 || value == 0x09 || value == 0x0A
                            || value == 0x0D || value == 0x0B || value == 0x0C;
                    if (!whitespace && !inWord) {
                        words++;
                    }
                    inWord = !whitespace;
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("could not read " + entry.relpath(), e);
        }
        return new CountResult.FileCount(entry.relpath(), lines, words, bytes);
    }

    private CountResult.FileCount streamText(ManifestEntry entry, long bytes) {
        long lines = 0;
        long words = 0;
        boolean inWord = false;
        try (java.io.Reader reader = new java.io.InputStreamReader(
                blobs.open(entry.blobSha256()), java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int carried = -1;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    char value = buffer[i];
                    if (value == '\n') {
                        lines++;
                    }
                    if (carried >= 0) {
                        int codePoint = Character.toCodePoint((char) carried, value);
                        boolean whitespace = Character.isWhitespace(codePoint);
                        if (!whitespace && !inWord) {
                            words++;
                        }
                        inWord = !whitespace;
                        carried = -1;
                        continue;
                    }
                    if (Character.isHighSurrogate(value)) {
                        carried = value;
                        continue;
                    }
                    boolean whitespace = Character.isWhitespace(value);
                    if (!whitespace && !inWord) {
                        words++;
                    }
                    inWord = !whitespace;
                }
            }
            if (carried >= 0) {
                boolean whitespace = Character.isWhitespace((char) carried);
                if (!whitespace && !inWord) {
                    words++;
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("could not read " + entry.relpath(), e);
        }
        return new CountResult.FileCount(entry.relpath(), lines, words, bytes);
    }
}
