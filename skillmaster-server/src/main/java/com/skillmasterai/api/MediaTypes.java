package com.skillmasterai.api;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a stored file is answered as (v39, ADR 0131).
 *
 * <p><strong>Two decisions, and they are deliberately separate.</strong> The inline allowlist decides
 * whether a browser may render a file in place; the fallback is always
 * {@code application/octet-stream}. Everything not on the allowlist is served as an attachment with
 * {@code X-Content-Type-Options: nosniff}, so a file's extension cannot turn it into a page that
 * executes script on this origin. SVG is not on the list even though it is an image: it is XML and
 * can carry script, so it is treated as an unknown file.
 *
 * <p>The table is deliberately short. It names only the types we are willing to render and the body
 * endpoint's markdown; it is not a general MIME map, and a file whose extension is absent is not a
 * failure — {@code octet-stream} is the honest answer for a byte sequence we do not interpret.
 */
final class MediaTypes {

    static final String OCTET_STREAM = "application/octet-stream";

    /** §4.2 fixes this one: the body endpoint always serves markdown. */
    static final String MARKDOWN = "text/markdown;charset=UTF-8";

    private static final Map<String, String> INLINE = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"));

    private MediaTypes() {
    }

    /** The type to render in place, or empty when the file must be downloaded. */
    static Optional<String> inlineTypeFor(String relpath) {
        return extensionOf(relpath).flatMap(extension -> Optional.ofNullable(INLINE.get(extension)));
    }

    /** What the response says, whether it is rendered or downloaded. */
    static String contentTypeFor(String relpath) {
        return inlineTypeFor(relpath).orElse(OCTET_STREAM);
    }

    static boolean isInline(String relpath) {
        return inlineTypeFor(relpath).isPresent();
    }

    /** The name to put in {@code Content-Disposition}: the last path segment. */
    static String filenameOf(String relpath) {
        int slash = relpath.lastIndexOf('/');
        return slash < 0 ? relpath : relpath.substring(slash + 1);
    }

    private static Optional<String> extensionOf(String relpath) {
        int dot = relpath.lastIndexOf('.');
        // A dot in a directory name is not an extension: "v1.2/notes" has none.
        int slash = relpath.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == relpath.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(relpath.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
