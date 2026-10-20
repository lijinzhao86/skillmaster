package com.skillmasterai.api;

import com.skillmasterai.modules.distribution.SkillDistributionService;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The one way a skill file leaves this service (v7).
 *
 * <p><strong>Both planes share it, and that is the point.</strong> A draft's file and a published
 * file are the same bytes with the same digest; the only difference is which resolver found them.
 * Two writer functions would be two chances for the content type, the length, or the early
 * disconnect to behave differently between them.
 *
 * <p>{@code Content-Length} comes from the manifest entry, never from reading the blob: the header
 * exists so the client can size the transfer, and reading the file to compute it would defeat the
 * streaming it sits beside. {@code ETag} is the version's digest — the value a write sends back as
 * {@code If-Match}, so one read and one write agree about what they were looking at.
 *
 * <p>The stream is opened <em>inside</em> the body, so a request that disconnects before the body
 * starts never opens one, and one that stops early closes it when the writer throws.
 */
final class FileStreams {

    private FileStreams() {
    }

    static ResponseEntity<StreamingResponseBody> stream(SkillDistributionService.StreamedFile file) {
        StreamingResponseBody body = out -> {
            try (InputStream in = file.open().get()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        MediaTypes.forRelpath(file.entry().relpath()))
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(file.entry().size()))
                .eTag(etag(file.digest()))
                .body(body);
    }

    /** The version digest, in the spelling {@code If-Match} and {@code ETag} both use. */
    static String etag(String digest) {
        return "\"sha256:" + digest + "\"";
    }
}
