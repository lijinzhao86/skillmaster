package com.skillmasterai.api;

import com.skillmasterai.modules.distribution.ArchiveArtifact;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * How an exported version leaves the service (ADR 0068).
 *
 * <p><strong>Both planes share it.</strong> The author route and the browser route resolve the
 * same artifact; only the authentication differs. One writer keeps the headers from drifting
 * between them.
 *
 * <p><strong>The headers say what the file is, not what it contains.</strong> {@code ETag} is the
 * manifest digest in the system's {@code "sha256:…"} spelling — the same value a client can
 * recompute from the archive's contents, which is the promise the round-trip test depends on.
 * {@code Content-Length} comes from the finished file. {@code Content-Disposition} is built by
 * Spring rather than concatenated: a skill name may contain {@code "}, and the builder is what
 * produces both the ASCII fallback and the UTF-8 {@code filename*} form correctly.
 *
 * <p>The artifact is deleted when the body finishes, or when the client disconnects and the write
 * throws. A response the server never starts streaming is left to the temp-directory policy.
 */
final class ArchiveStreams {

    private ArchiveStreams() {
    }

    static ResponseEntity<StreamingResponseBody> stream(ArchiveArtifact artifact) {
        StreamingResponseBody body = out -> {
            try (artifact; InputStream in = Files.newInputStream(artifact.path())) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(artifact.size()))
                .eTag(FileStreams.etag(artifact.digest()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(artifact.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}
