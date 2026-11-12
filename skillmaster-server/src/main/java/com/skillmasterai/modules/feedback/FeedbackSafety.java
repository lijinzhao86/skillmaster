package com.skillmasterai.modules.feedback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two content rules M18 applies to a feedback document: a length ceiling, and a scan for
 * values that should not leave a machine without a second look.
 *
 * <p><strong>The scan is a warning, not a redactor — except for secrets.</strong> A phone number,
 * an ID number or a card number may be exactly what the report is about, so the caller is asked to
 * confirm and the value is kept. A key prefix is never the point of a report and cannot be rotated
 * after it leaks, so it is replaced before the row is written.
 *
 * <p>The classification is deliberately coarse: digit runs are grouped by length rather than by
 * checksum. A false positive costs one confirmation; a silent miss costs a leaked value.
 */
public final class FeedbackSafety {

    /** The document ceiling, in Unicode code points (ADR 0181). */
    public static final int MAX_CODE_POINTS = 20_000;

    /**
     * A key prefix is only a key when nothing word-like precedes it: `disk-usage-full` and
     * `task-management` contain `sk-` but are ordinary English, and rewriting them would corrupt a
     * report that has nothing secret in it.
     */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(sk-[a-z0-9_-]{8,}|AKIA[0-9A-Z]{16}|skm_[a-z0-9]+)");

    /**
     * Every run of digits long enough to be one of the three, with the optional trailing X an ID
     * number may carry; classified by length below. The boundaries are the same as the CLI's
     * `\\b` form, so the preview and the server agree on what they see.
     */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\b\\d{11,19}[Xx]?\\b");

    /**
     * A space, tab, hyphen or dot between two digits on the same line is a formatting choice, not
     * a separator between two values: `138-0013-8000` is one phone number. **A newline is not one**:
     * removing it would glue the last number of one line to the first of the next and turn
     * `13800138000\n1. …` into a 12-digit run that no category names.
     */
    private static final Pattern DIGIT_SEPARATOR = Pattern.compile("(?<=\\d)[ \\t.-](?=\\d)");

    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");

    private static final String REDACTED = "[已隐藏]";

    private FeedbackSafety() {
    }

    /** @param counts category to how many occurrences; empty when nothing was found */
    public record Scan(Map<String, Integer> counts) {

        public Scan {
            counts = Map.copyOf(counts);
        }

        public boolean any() {
            return !counts.isEmpty();
        }
    }

    public static Scan scan(String content) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher secrets = SECRET.matcher(content);
        int secretCount = 0;
        while (secrets.find()) {
            secretCount++;
        }
        if (secretCount > 0) {
            counts.put("secret_prefix", secretCount);
        }

        Matcher digits = DIGIT_RUN.matcher(DIGIT_SEPARATOR.matcher(content).replaceAll(""));
        while (digits.find()) {
            String run = digits.group();
            boolean xSuffix = run.endsWith("X") || run.endsWith("x");
            int length = xSuffix ? run.length() - 1 : run.length();
            if (xSuffix && length == 17) {
                counts.merge("id_number", 1, Integer::sum);
                continue;
            }
            switch (length) {
                case 11 -> {
                    if (MOBILE.matcher(run).matches()) {
                        counts.merge("phone", 1, Integer::sum);
                    }
                }
                case 18 -> counts.merge("id_number", 1, Integer::sum);
                case 16, 17, 19 -> counts.merge("bank_card", 1, Integer::sum);
                default -> {
                    // 12-15 digits: no category we can name, so no warning.
                }
            }
        }
        return new Scan(counts);
    }

    /**
     * Replaces every secret-bearing prefix; everything else is left exactly as written.
     *
     * <p><strong>A false positive is invisible here.</strong> The scan's false positives cost a
     * confirmation; this method rewrites, so its pattern must not match a word that merely contains
     * `sk-`. The leading boundary above is that rule.
     */
    public static String redactSecrets(String content) {
        return SECRET.matcher(content).replaceAll(REDACTED);
    }

    /** The code-point count the ceiling is measured in (ADR 0181). */
    public static int codePoints(String content) {
        return content == null ? 0 : content.codePointCount(0, content.length());
    }
}
