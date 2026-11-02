package com.skillmasterai.api;

import com.skillmasterai.modules.distribution.SkillDistributionService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The one way a skill file leaves this service (v7, v39).
 *
 * <p><strong>Both planes share it, and that is the point.</strong> A draft's file and a published
 * file are the same bytes with the same digest; the only difference is which resolver found them.
 * Two writer functions would be two chances for the content type, the length, the disposition, or
 * the early disconnect to behave differently between them.
 *
 * <p>{@code Content-Length} comes from the manifest entry, never from reading the blob: the header
 * exists so the client can size the transfer, and reading the file to compute it would defeat the
 * streaming it sits beside. {@code ETag} is the version's digest — the value a write sends back as
 * {@code If-Match}, so one read and one write agree about what they were looking at.
 *
 * <p><strong>Inline is a small allowlist, not a default</strong> (ADR 0131). An image or a PDF may
 * be rendered in place; everything else is an attachment with {@code nosniff}, so an HTML or SVG
 * file cannot execute on this origin whatever its extension says.
 *
 * <p>The stream is opened <em>inside</em> the body, so a request that disconnects before the body
 * starts never opens one, and one that stops early closes it when the writer throws.
 */
final class FileStreams {

    private FileStreams() {
    }

    /**
     * The version's {@code SKILL.md}, streamed with the type the body endpoint has always had.
     *
     * <p>L2 is the one read that is not chosen by an extension: it is always markdown, and it is
     * streamed for the same reason every other file is — a body at the ceiling must not become a byte
     * array just because the route predates v39.
     */
    static ResponseEntity<StreamingResponseBody> streamBody(
            SkillDistributionService.StreamedFile file) {
        StreamingResponseBody body = out -> {
            try (InputStream in = file.open().get()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaTypes.MARKDOWN)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(file.entry().size()))
                .eTag(etag(file.digest()))
                .body(body);
    }

    static ResponseEntity<StreamingResponseBody> stream(SkillDistributionService.StreamedFile file) {
        String relpath = file.entry().relpath();
        String filename = MediaTypes.filenameOf(relpath);
        boolean inline = MediaTypes.isInline(relpath);

        ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(filename, StandardCharsets.UTF_8)
                .build();

        StreamingResponseBody body = out -> {
            try (InputStream in = file.open().get()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaTypes.contentTypeFor(relpath))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(file.entry().size()))
                .eTag(etag(file.digest()))
                .body(body);
    }

    /** The version digest, in the spelling {@code If-Match} and {@code ETag} both use. */
    static String etag(String digest) {
        return "\"sha256:" + digest + "\"";
    }
}
