package com.skillmasterai.modules.ingest;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * One file of an upload, held in memory.
 *
 * <p>In memory rather than spooled to a temp file: the zip reader holds every entry it returns,
 * and until v39 P3 streams that path its uncompressed total is capped at 128 MiB. Keeping the bytes
 * in hand is what makes "never rewrite content" (ADR 0005) easy to demonstrate. Writing them to
 * server-local disk would also create a second copy of the content, outside the transaction that is
 * supposed to own it.
 *
 * @param relpath POSIX separators, relative to the skill root
 * @param bytes   the exact bytes as uploaded — never normalised, reformatted or re-encoded
 */
public record IngestedFile(String relpath, byte[] bytes) {

    public long size() {
        return bytes.length;
    }

    /**
     * Whether the bytes are not valid UTF-8.
     *
     * <p>A decode probe rather than a NUL-byte or MIME sniff, and it is only a hint: the bytes are
     * stored and served unchanged either way. Reported so a client can decide not to try to render
     * a file as text.
     */
    public boolean isBinary() {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return false;
        } catch (CharacterCodingException e) {
            return true;
        }
    }
}
