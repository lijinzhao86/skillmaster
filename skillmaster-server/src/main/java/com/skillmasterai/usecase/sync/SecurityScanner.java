package com.skillmasterai.usecase.sync;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the compiled rules over one entry's files (ADR 0155).
 *
 * <p>Only UTF-8 files without a NUL byte are read as text. That is not a limitation to work around:
 * a binary can be anything, and pretending to scan it would produce confidence the check cannot
 * back. Binary files are still reported as warnings by path and by their being binary.
 */
public final class SecurityScanner {

    private static final int MAX_FINDINGS = 50;

    private final SecurityRules rules;

    public SecurityScanner(SecurityRules rules) {
        this.rules = rules;
    }

    public SecurityReport scan(List<FetchedFile> files) {
        List<SecurityFinding> blocked = new ArrayList<>();
        List<SecurityFinding> warnings = new ArrayList<>();
        for (FetchedFile file : files) {
            String text = textOf(file.content());
            for (SecurityRule rule : rules.blocked()) {
                if (matches(rule, file, text)) {
                    blocked.add(new SecurityFinding(rule.id(), rule.label(), file.relpath()));
                }
            }
            for (SecurityRule rule : rules.warnings()) {
                if (matches(rule, file, text)) {
                    warnings.add(new SecurityFinding(rule.id(), rule.label(), file.relpath()));
                }
            }
            if (blocked.size() >= MAX_FINDINGS) {
                blocked.add(new SecurityFinding("too_many_findings", "命中过多", ""));
                return new SecurityReport(blocked, warnings);
            }
        }
        return new SecurityReport(blocked, warnings);
    }

    /** Adds the "close to a ceiling" warning the pipeline reports after the file set is known. */
    public static SecurityFinding nearLimit(String what) {
        return new SecurityFinding("near_limit", "接近上限：" + what, "");
    }

    private static boolean matches(SecurityRule rule, FetchedFile file, String text) {
        return switch (rule.kind()) {
            case PATH -> rule.pattern().matcher(file.relpath()).find();
            case TEXT -> text != null && rule.pattern().matcher(text).find();
            case BINARY -> text == null;
            case EXECUTABLE -> file.executable() && text != null;
            case EXECUTABLE_BINARY -> file.executable() && text == null;
        };
    }

    /** The file as text, or null when it is not valid UTF-8 or contains a NUL. */
    private static String textOf(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            return null;
        }
    }
}
