package com.skillmasterai.support;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds a {@code multipart/form-data} body for an {@link java.net.http.HttpClient} request.
 *
 * <p>Hand-rolled because {@link java.net.http.HttpClient} has no multipart support and adding a
 * client library to send one part would cost more than the format does. That format is small and
 * fixed: a boundary, then per part a {@code Content-Disposition} header, a blank line, the bytes,
 * and a CRLF; then the same boundary with two trailing dashes.
 *
 * <p>One instance is one request — the boundary is generated per instance, so a body built by one
 * cannot be interpreted against another's {@code Content-Type}.
 */
public final class Multipart {

    private static final String CRLF = "\r\n";

    private final String boundary = "----skillmaster-" + UUID.randomUUID();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private boolean hasName;
    private boolean omitName;

    private Multipart() {
    }

    public static Multipart create() {
        return new Multipart();
    }

    public Multipart file(String field, String filename, byte[] content) {
        write("--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"" + CRLF
                + "Content-Type: application/octet-stream" + CRLF + CRLF);
        body.writeBytes(content);
        write(CRLF);
        return this;
    }

    /** A plain text part, such as the version's {@code name} or {@code note} (v6). */
    public Multipart field(String name, String value) {
        write("--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"" + name + "\"" + CRLF + CRLF);
        write(value);
        write(CRLF);
        hasName |= "name".equals(name);
        return this;
    }

    /**
     * Leaves out the required {@code name} part, so a test can ask what the server does without it.
     * Every other submission gets one automatically: since v6 a version must be named, and a helper
     * that silently produced nameless submissions would turn every test into the missing-name case.
     */
    public Multipart withoutName() {
        omitName = true;
        return this;
    }

    /** The value for the {@code Content-Type} header, boundary included. */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public HttpRequest.BodyPublisher publisher() {
        ByteArrayOutputStream complete = new ByteArrayOutputStream(body.size() + 128);
        complete.writeBytes(body.toByteArray());
        if (!hasName && !omitName) {
            complete.writeBytes(("--" + boundary + CRLF
                    + "Content-Disposition: form-data; name=\"name\"" + CRLF + CRLF
                    + "test version" + CRLF).getBytes(StandardCharsets.UTF_8));
        }
        complete.writeBytes(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArray(complete.toByteArray());
    }

    private void write(String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
