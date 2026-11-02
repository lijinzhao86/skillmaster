package com.skillmasterai.modules.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A binary probe that does not need the whole file in memory.
 *
 * <p>{@link IngestedFile#isBinary()} answers the same question for bytes already held; an upload
 * that is being streamed from the network has no such array, and loading one just to answer it
 * would defeat the streaming. This reads the bytes through a strict UTF-8 decoder and keeps nothing
 * but a small character buffer.
 *
 * <p>The answer is a hint stored in the manifest, not a rule about how bytes are served: a file
 * that fails the probe is still stored and downloaded unchanged.
 */
public final class Utf8Probe {

    private Utf8Probe() {
    }

    public static boolean isBinary(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return isBinary(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /**
     * A byte prefix cut in the middle of a code point is not invalid UTF-8 — it is a prefix.
     *
     * <p>{@code SKILL.md}'s frontmatter is parsed from a bounded prefix for very large files; that
     * prefix must end on a character boundary or the strict decoder would call a legal file
     * malformed. Returns the input unchanged when its tail is already complete.
     */
    public static byte[] cutIncompleteTail(byte[] bytes) {
        int index = bytes.length - 1;
        int continuations = 0;
        while (index >= 0 && (bytes[index] & 0xC0) == 0x80 && continuations < 3) {
            index--;
            continuations++;
        }
        if (index < 0) {
            return bytes;
        }
        int lead = bytes[index] & 0xFF;
        int needed = lead < 0x80 ? 1
                : (lead & 0xE0) == 0xC0 ? 2
                : (lead & 0xF0) == 0xE0 ? 3
                : (lead & 0xF8) == 0xF0 ? 4
                : 1;
        if (bytes.length - index >= needed) {
            return bytes;
        }
        return java.util.Arrays.copyOf(bytes, index);
    }

    public static boolean isBinary(InputStream in) {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
        char[] buffer = new char[8192];
        try {
            while (reader.read(buffer) != -1) {
                // Deliberately discarding the characters: only the decoder's verdict matters.
            }
            return false;
        } catch (CharacterCodingException e) {
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the upload", e);
        }
    }
}
