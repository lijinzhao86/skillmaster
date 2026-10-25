package com.skillmasterai.modules.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The startup journey's text, read off the classpath and pointed at this deployment.
 *
 * <p><strong>It is a document, not a skill</strong> (ADR 0089). Until v17 this module published a
 * gateway skill into the database: a real skill with a version, a digest and a discovery index, whose
 * job was to be installed into the caller's own skills directory. That is gone, together with the
 * index (ADR 0093) — the product's claim is that no skill lives on the machine, so the one thing the
 * anonymous channel still serves is the text an agent reads once, at the start.
 *
 * <p><strong>The substitution happens once, when this object is built.</strong> The source carries a
 * literal {@code https://<host>} because the repository cannot know a deployment's address. Doing it
 * per request instead would make the bytes vary between two requests for no reason, and the same
 * class of mistake the published skill's digest rule was there to prevent.
 *
 * <p>The source is authoritative at {@code gateway/skillmaster/STARTUP.md} in the repository root;
 * the build copies that directory onto the classpath (see {@code pom.xml}) rather than committing a
 * second copy.
 */
public final class StartupBody {

    /** Where the journey body is served. Referenced by the controller so the two cannot drift. */
    public static final String PATH = "/gateway/STARTUP.md";

    /**
     * The placeholder the repository's copy carries.
     *
     * <p>The file is committed to a public repository and read by machines that have not yet talked
     * to any server, so it cannot name one. This is the string a deployment replaces.
     */
    public static final String HOST_PLACEHOLDER = "https://<host>";

    private static final String RESOURCE = "gateway/skillmaster/STARTUP.md";

    private final byte[] bytes;

    /**
     * @param publicBaseUrl the address clients reach this deployment at
     * @throws IllegalStateException when the source is missing or has lost its placeholder — loud,
     *         because the alternative is serving a document that tells every caller to connect to
     *         the literal string {@code https://<host>}
     */
    public StartupBody(String publicBaseUrl) {
        String raw = readResource();
        if (!raw.contains(HOST_PLACEHOLDER)) {
            throw new IllegalStateException("the startup source has no '" + HOST_PLACEHOLDER
                    + "' placeholder, so its address cannot be set to " + publicBaseUrl);
        }
        this.bytes = raw.replace(HOST_PLACEHOLDER, publicBaseUrl).getBytes(StandardCharsets.UTF_8);
    }

    /** The body as served. A copy, so a caller cannot change what the next caller receives. */
    public byte[] bytes() {
        return bytes.clone();
    }

    private static String readResource() {
        ClassLoader loader = StartupBody.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the startup source '" + RESOURCE
                        + "' is not on the classpath; the build copies it from the repository root");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the startup source '" + RESOURCE + "'", e);
        }
    }
}
