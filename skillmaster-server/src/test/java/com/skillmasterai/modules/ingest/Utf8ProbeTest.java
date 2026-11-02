package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Utf8ProbeTest {

    @Test
    void validUtf8IsText() {
        assertThat(Utf8Probe.isBinary(stream("中文 and ascii"))).isFalse();
        assertThat(Utf8Probe.isBinary(stream(""))).isFalse();
    }

    @Test
    void invalidUtf8IsBinary() {
        // A lone continuation byte: no decoder accepts it as UTF-8.
        assertThat(Utf8Probe.isBinary(new ByteArrayInputStream(new byte[] {(byte) 0x80})))
                .isTrue();
        // A truncated multi-byte sequence is just as unusable.
        assertThat(Utf8Probe.isBinary(new ByteArrayInputStream(
                new byte[] {(byte) 0xE4, (byte) 0xB8}))).isTrue();
    }

    @Test
    void aPrefixCutInsideACharacterIsTrimmedToTheLastCompleteOne() {
        byte[] chinese = "中".getBytes(StandardCharsets.UTF_8);
        assertThat(Utf8Probe.cutIncompleteTail(java.util.Arrays.copyOf(chinese, 2))).isEmpty();
        assertThat(Utf8Probe.cutIncompleteTail(java.util.Arrays.copyOf(chinese, 3)))
                .isEqualTo(chinese);

        byte[] mixed = "ab中".getBytes(StandardCharsets.UTF_8);
        assertThat(Utf8Probe.cutIncompleteTail(
                java.util.Arrays.copyOf(mixed, mixed.length - 1)))
                .isEqualTo("ab".getBytes(StandardCharsets.UTF_8));
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
