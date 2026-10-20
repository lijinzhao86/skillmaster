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
    private final DraftTextCache texts;

    public CountVersionUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, DraftTextCache texts) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.metadata = metadata;
        this.blobs = blobs;
        this.texts = texts;
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

    private CountResult.FileCount count(ManifestEntry entry) {
        // Bytes come from the manifest, always: it is the number the digest was computed over, and
        // it is available without opening the blob. Only the other two need the content.
        long bytes = entry.size();
        if (entry.isBinary()) {
            byte[] raw = blobs.get(entry.blobSha256());
            return new CountResult.FileCount(entry.relpath(), countNewlines(raw),
                    countAsciiWords(raw), bytes);
        }
        String text = texts.text(entry.blobSha256(), () -> blobs.get(entry.blobSha256()));
        return new CountResult.FileCount(entry.relpath(), countNewlines(text),
                countWords(text), bytes);
    }

    private static long countNewlines(byte[] raw) {
        long count = 0;
        for (byte b : raw) {
            if (b == '\n') {
                count++;
            }
        }
        return count;
    }

    private static long countNewlines(String text) {
        long count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** Text: runs of non-whitespace, by the same definition the local tool's locale gives. */
    private static long countWords(String text) {
        long words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            boolean whitespace = Character.isWhitespace(codePoint);
            if (!whitespace && !inWord) {
                words++;
            }
            inWord = !whitespace;
            i += Character.charCount(codePoint);
        }
        return words;
    }

    /**
     * Binary: ASCII whitespace bytes only (space, tab, newline, CR, form feed, vertical tab).
     *
     * <p>Deliberately not {@link Character#isWhitespace}: the bytes are not text, and decoding them
     * to ask would either fail or invent characters. The difference from the local tool is recorded
     * as a known limitation rather than hidden (v7 test-plan K4).
     */
    private static long countAsciiWords(byte[] raw) {
        long words = 0;
        boolean inWord = false;
        for (byte value : raw) {
            int b = value & 0xFF;
            boolean whitespace = b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0B
                    || b == 0x0C;
            if (!whitespace && !inWord) {
                words++;
            }
            inWord = !whitespace;
        }
        return words;
    }
}
